"""Keeps the local PC app in sync with the cloud (Railway) copy.

Cycle:  push outbox → (seed cloud if it is empty) → pull parts + movements → sync manual files.
Runs at start-up, right after every local change, whenever the cloud reports a change
(live event stream), and every `interval` seconds as a safety net.
"""
import json, os, ssl, threading, time, urllib.error, urllib.request
from pathlib import Path
from urllib.parse import quote

class RemoteError(Exception):
    pass

class Syncer:
    def __init__(self, store, remote_url, key, files_dir=None, interval=30, log=print):
        self.store = store
        self.remote = remote_url.rstrip("/")
        self.key = key
        self.files_dir = Path(files_dir) if files_dir else None
        self.interval = interval
        self.log = log
        self.wake = threading.Event()
        self.lock = threading.Lock()
        self.online = False
        self.last_ok = None
        self.last_error = ""
        self.last_files = 0
        self.listeners = []
        self.ctx = ssl.create_default_context()

    # ---------- HTTP ----------
    def _req(self, method, path, body=None, raw=None, headers=None, timeout=20):
        h = {"X-Api-Key": self.key, "User-Agent": "SpareParts-Sync/1.0"}
        data = None
        if raw is not None:
            data = raw; h["Content-Type"] = "application/octet-stream"
        elif body is not None:
            data = json.dumps(body).encode(); h["Content-Type"] = "application/json"
        h.update(headers or {})
        req = urllib.request.Request(self.remote + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=timeout, context=self.ctx) as r:
                payload = r.read()
                if "json" in (r.headers.get("Content-Type") or ""):
                    return json.loads(payload or b"null")
                return payload
        except urllib.error.HTTPError as e:
            try: msg = json.loads(e.read()).get("error", "")
            except Exception: msg = ""
            if e.code == 401:
                raise RemoteError("Cloud rejected the sync key (check sync.key in config.json)")
            raise RemoteError(f"HTTP {e.code} {msg}".strip()) from e
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise ConnectionError(str(getattr(e, "reason", e))) from e

    # ---------- state ----------
    def status(self):
        return {"mode": "replica", "remote": self.remote, "online": self.online,
                "last_sync": self.last_ok, "pending": self.store.outbox_count(),
                "error": self.last_error}

    def _set_state(self, online, error=""):
        changed = (online, error) != (self.online, self.last_error)
        self.online, self.last_error = online, error
        if online and not error:
            self.last_ok = time.time()
        if changed or True:
            for fn in self.listeners:
                try: fn({"type": "syncstatus", **self.status()})
                except Exception: pass

    # ---------- steps ----------
    def push(self):
        items = self.store.outbox()
        while items:
            adjust_batch = []
            for it in items:
                if it["kind"] != "adjust":
                    break
                adjust_batch.append(it)
            if adjust_batch:
                res = self._req("POST", "/api/adjust", {"items": [dict(b["body"], id=b["id"]) for b in adjust_batch]})
                for r in res["results"]:
                    if not r["ok"]:
                        self.log(f"sync: cloud rejected stock change {r.get('id')}: {r.get('error')}")
                self.store.outbox_done([b["id"] for b in adjust_batch])
            else:
                it = items[0]
                pn = quote(it["pn"], safe="")
                try:
                    if it["kind"] == "patch":
                        self._req("PATCH", f"/api/parts/{pn}", dict(it["body"]["changes"], _ts=it["body"]["ts"]))
                    elif it["kind"] == "create":
                        self._req("POST", "/api/parts", it["body"])
                    elif it["kind"] == "delete":
                        self._req("DELETE", f"/api/parts/{pn}")
                except RemoteError as e:
                    # 400/404 (already exists / already deleted) will never succeed – drop it
                    self.log(f"sync: dropped {it['kind']} {it['pn']}: {e}")
                self.store.outbox_done([it["id"]])
            items = self.store.outbox()

    def pull(self):
        since = self.store.meta("remote_version") or ""
        d = self._req("GET", "/api/parts" + (f"?since={since}" if since else ""), timeout=60)
        if d.get("parts") is not None:
            if not d["parts"] and self.store.count():
                self.seed()
                d = self._req("GET", "/api/parts", timeout=60)
            if not self.store.replace_from_remote(d["parts"]):
                self.wake.set()   # a local change arrived meanwhile – push it, pull next round
                return
            self.store.meta("remote_version", d["version"])
        after = int(self.store.meta("remote_mv_id") or 0)
        while True:
            rows = self._req("GET", f"/api/movements?after_id={after}&limit=500")
            if not rows:
                break
            self.store.merge_remote_movements(rows)
            after = rows[-1]["id"]
            self.store.meta("remote_mv_id", after)
            if len(rows) < 500:
                break

    def seed(self):
        self.log("sync: cloud is empty – uploading local inventory")
        parts = self.store.all()
        mv = self.store.movements(limit=100000, after_id=0)
        self._req("POST", "/api/sync/seed", {"parts": parts, "movements": mv}, timeout=120)

    def sync_files(self):
        if not self.files_dir:
            return
        self.files_dir.mkdir(parents=True, exist_ok=True)
        remote = {f["path"]: f["size"] for f in self._req("GET", "/api/files")}
        local = {}
        for f in self.files_dir.rglob("*"):
            if f.is_file() and not f.name.startswith((".", "~$")) and f.name != "desktop.ini":
                local[f.relative_to(self.files_dir).as_posix()] = f.stat().st_size
        up = [p for p in local if p not in remote]
        down = [p for p in remote if p not in local]
        for p in up:
            if local[p] > 100 * 1024 * 1024:
                self.log(f"sync: skipped {p} (over 100 MB)"); continue
            self._req("POST", f"/api/files?path={quote(p)}", raw=(self.files_dir / p).read_bytes(), timeout=300)
        for p in down:
            data = self._req("GET", "/files/" + quote(p), timeout=300)
            target = self.files_dir / p
            target.parent.mkdir(parents=True, exist_ok=True)
            tmp = target.with_suffix(target.suffix + ".part")
            tmp.write_bytes(data); os.replace(tmp, target)
        if up or down:
            self.log(f"sync: manuals – {len(up)} uploaded, {len(down)} downloaded")
        self.last_files = time.time()

    def run_once(self, files=False):
        with self.lock:
            try:
                self.push()
                self.pull()
                if files or time.time() - self.last_files > 300:
                    self.sync_files()
                self._set_state(True)
                return True
            except ConnectionError as e:
                self._set_state(False, f"Offline: {e}")
            except RemoteError as e:
                self._set_state(True, str(e)); self.log("sync:", e)
            except Exception as e:
                self._set_state(False, f"Sync error: {e}"); self.log("sync error:", repr(e))
            return False

    # ---------- threads ----------
    def start(self):
        threading.Thread(target=self._loop, daemon=True, name="sync").start()
        threading.Thread(target=self._listen, daemon=True, name="sync-live").start()

    def poke(self):
        self.wake.set()

    def _loop(self):
        while True:
            self.wake.wait(self.interval if self.online else 15)
            self.wake.clear()
            time.sleep(0.3)   # batch bursts of clicks
            self.run_once()

    def _listen(self):
        """Follow the cloud's live event stream so changes from phones/Slack show up instantly."""
        while True:
            try:
                req = urllib.request.Request(self.remote + "/api/events", headers={"X-Api-Key": self.key, "Accept": "text/event-stream"})
                with urllib.request.urlopen(req, timeout=60, context=self.ctx) as r:
                    for line in r:
                        if line.startswith(b"data:"):
                            ev = json.loads(line[5:])
                            if ev.get("type") != "hello" or str(ev.get("version")) != (self.store.meta("remote_version") or ""):
                                self.wake.set()
            except Exception:
                pass
            time.sleep(10)

"""Keeps the local PC app in sync with the cloud (Railway) copy.

Cycle:  push outbox → (seed cloud if it is empty) → pull parts + movements → sync manual files.
Runs at start-up, right after every local change, whenever the cloud reports a change
(live event stream), and every `interval` seconds as a safety net.
"""
import json, os, ssl, threading, time, urllib.error, urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import quote

def _win_long(p):
    """Windows cannot open paths whose folder name ends with a dot without the \\?\\ prefix."""
    return Path("\\\\?\\" + os.path.abspath(str(p)))

def windows_safe(rel):
    """Windows cannot create a folder or file whose name ends with a dot or a space."""
    return os.name != "nt" or not any(seg != seg.rstrip(". ") for seg in rel.split("/"))

def read_file(p):
    p = Path(p)
    try:
        return p.read_bytes()
    except OSError:
        if os.name == "nt":
            return _win_long(p).read_bytes()
        raise

def write_file(p, data):
    p = Path(p)
    try:
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_name(p.name + ".part")
        tmp.write_bytes(data)
        os.replace(tmp, p)
    except OSError:
        if os.name != "nt":
            raise
        lp = _win_long(p)
        lp.parent.mkdir(parents=True, exist_ok=True)
        tmp = lp.with_name(lp.name + ".part")
        tmp.write_bytes(data)
        os.replace(tmp, lp)

def file_size(p):
    p = Path(p)
    try:
        return p.stat().st_size
    except OSError:
        if os.name == "nt":
            return _win_long(p).stat().st_size
        raise

class RemoteError(Exception):
    pass

class Syncer:
    def __init__(self, store, remote_url, key, files_dir=None, interval=30, log=print, accounts=None):
        self.store = store
        self.accounts = accounts
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
        self.files_pending = 0
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
                "files_pending": self.files_pending, "error": self.last_error}

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
                    actor = {"X-Actor": quote(it["body"].get("user") or it["body"].get("_user") or "")}
                    if it["kind"] == "patch":
                        self._req("PATCH", f"/api/parts/{pn}", dict(it["body"]["changes"], _ts=it["body"]["ts"]),
                                  headers=actor)
                    elif it["kind"] == "create":
                        self._req("POST", "/api/parts", it["body"], headers=actor)
                    elif it["kind"] == "delete":
                        self._req("DELETE", f"/api/parts/{pn}", headers=actor)
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
        if self.accounts is not None:
            try:
                rows = self._req("GET", "/api/users/export").get("users", [])
                if rows:
                    self.accounts.replace_all(rows)
            except RemoteError as e:
                self.log("sync: could not fetch accounts:", e)
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
                local[f.relative_to(self.files_dir).as_posix()] = file_size(f)
        up = [p for p in local if p not in remote and local[p] <= 100 * 1024 * 1024]
        skipped = [p for p in local if p not in remote and local[p] > 100 * 1024 * 1024]
        down = [p for p in remote if p not in local and windows_safe(p)]
        for p in remote:
            if p not in local and not windows_safe(p):
                self.log(f"sync: cannot store {p} on Windows (name ends with a dot) - left in the cloud")
        for p in skipped:
            self.log(f"sync: skipped {p} (over 100 MB)")
        self.files_pending = len(up) + len(down)
        if not self.files_pending:
            self.last_files = time.time(); return
        self.log(f"sync: manuals - uploading {len(up)}, downloading {len(down)} …")
        done = [0]
        lock = threading.Lock()
        def upload(p):
            self._req("POST", f"/api/files?path={quote(p)}", raw=read_file(self.files_dir / p), timeout=600)
        def download(p):
            write_file(self.files_dir / p, self._req("GET", "/files/" + quote(p), timeout=600))
        def one(fn, p):
            for attempt in range(3):
                try:
                    fn(p); break
                except Exception as e:
                    if attempt == 2:
                        self.log(f"sync: {fn.__name__} failed for {p}: {e}")
                    else:
                        time.sleep(1.5 * (attempt + 1))
            with lock:
                done[0] += 1
                self.files_pending = max(0, len(up) + len(down) - done[0])
                if done[0] % 25 == 0:
                    self.log(f"sync: manuals {done[0]}/{len(up) + len(down)}")
                    self._set_state(self.online, self.last_error)
        with ThreadPoolExecutor(max_workers=6) as ex:
            for p in up: ex.submit(one, upload, p)
            for p in down: ex.submit(one, download, p)
        self.files_pending = 0
        self.log(f"sync: manuals done ({len(up)} uploaded, {len(down)} downloaded)")
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

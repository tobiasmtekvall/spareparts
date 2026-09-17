"""Spare Parts Inventory – server.

Runs in two modes:
  * cloud   – on Railway: the primary copy. Settings come from environment variables.
  * local   – on the site PC: a full copy that syncs with the cloud (config.json → sync).
Everything is Python standard library except the optional Slack bot (slack_bolt)
and workbook import (pandas + odfpy/openpyxl).
"""
import base64, hashlib, hmac, html, json, mimetypes, os, queue, re, socket, sys, threading, time, traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs, unquote, quote

ROOT = Path(__file__).resolve().parent.parent
WEB = Path(__file__).resolve().parent / "web"
sys.path.insert(0, str(Path(__file__).resolve().parent))
from store import Store  # noqa: E402

try:  # Windows consoles may not be UTF-8
    sys.stdout.reconfigure(errors="replace"); sys.stderr.reconfigure(errors="replace")
except Exception:
    pass

DEFAULT_CONFIG = {
    "host": "0.0.0.0",
    "port": 8765,
    "password": "",
    "api_key": "",
    "public_url": "",
    "database": "data/inventory.db",
    "source_workbook": "data/source.ods",
    "research_dir": "",
    "slack": {"bot_token": "", "app_token": "", "alert_channel": "", "command": "/parts"},
    "sync": {"remote_url": "", "key": "", "interval": 30},
}

def load_config():
    cfg = json.loads(json.dumps(DEFAULT_CONFIG))
    p = ROOT / "config.json"
    if p.exists():
        user = json.loads(p.read_text(encoding="utf-8"))
        for k in ("slack", "sync"):
            user[k] = {**cfg[k], **user.get(k, {})}
        cfg.update(user)
    env = os.environ.get
    # Cloud (Railway) settings
    if env("PORT"): cfg["port"] = int(env("PORT"))
    data_dir = env("DATA_DIR") or env("RAILWAY_VOLUME_MOUNT_PATH")
    if data_dir:
        cfg["database"] = str(Path(data_dir) / "inventory.db")
        cfg["research_dir"] = str(Path(data_dir) / "files")
    cfg["password"] = env("APP_PASSWORD", cfg["password"] or cfg["api_key"])
    if env("PUBLIC_URL"): cfg["public_url"] = env("PUBLIC_URL")
    elif env("RAILWAY_PUBLIC_DOMAIN"): cfg["public_url"] = "https://" + env("RAILWAY_PUBLIC_DOMAIN")
    for k, e in (("bot_token", "SLACK_BOT_TOKEN"), ("app_token", "SLACK_APP_TOKEN"),
                 ("alert_channel", "SLACK_ALERT_CHANNEL"), ("command", "SLACK_COMMAND")):
        cfg["slack"][k] = env(e, cfg["slack"][k])
    cfg["sync"]["remote_url"] = env("SYNC_REMOTE_URL", cfg["sync"]["remote_url"]).strip()
    cfg["sync"]["key"] = env("SYNC_KEY", cfg["sync"]["key"])
    return cfg

def resolve(p):
    p = Path(str(p).replace("\\", "/"))
    return p if p.is_absolute() else (ROOT / p).resolve()

CFG = load_config()
REPLICA = bool(CFG["sync"]["remote_url"])
PASSWORD = CFG["password"]
SECRET = hashlib.sha256(("sp-session:" + (os.environ.get("SESSION_SECRET") or PASSWORD)).encode()).digest()
STORE = Store(resolve(CFG["database"]), replica=REPLICA)
SYNC = None

def lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(("10.255.255.255", 1)); ip = s.getsockname()[0]; s.close()
        return ip
    except Exception:
        return "127.0.0.1"

def public_url():
    if CFG["public_url"]:
        return CFG["public_url"].rstrip("/")
    if REPLICA:
        return CFG["sync"]["remote_url"].rstrip("/")   # labels printed locally point at the cloud
    return f"http://{lan_ip()}:{CFG['port']}"

def research_dir():
    d = CFG.get("research_dir")
    return resolve(d) if d else None

def glob_escape(s):
    return re.sub(r"([*?\[])", r"[\1]", s)

def safe_name(s):
    return re.sub(r'[<>:"/\\|?*\x00-\x1f]+', " ", s).strip(" .")[:80] or "file"

def part_folder(p):
    """Existing '<category>/<pn> - …' folder for a part, or the one to create."""
    base = research_dir()
    for folder in base.glob(f"*/{glob_escape(p['pn'])} - *"):
        if folder.is_dir():
            return folder
    return base / safe_name(p["category"] or "Other") / safe_name(f"{p['pn']} - {p['name']}")

def local_files(pn):
    base = research_dir()
    if not base or not base.exists():
        return []
    out = []
    for folder in base.glob(f"*/{glob_escape(pn)} - *"):
        for f in sorted(folder.rglob("*")):
            if f.is_file() and not f.name.endswith(".part"):
                rel = f.relative_to(base).as_posix()
                out.append({"title": f.name, "url": "/files/" + quote(rel), "size": f.stat().st_size})
    return out

def all_files():
    base = research_dir()
    if not base or not base.exists():
        return []
    return [{"path": f.relative_to(base).as_posix(), "size": f.stat().st_size}
            for f in base.rglob("*") if f.is_file() and not f.name.endswith(".part")
            and not f.name.startswith((".", "~$")) and f.name != "desktop.ini"]

def safe_path(rel):
    base = research_dir().resolve()
    f = (base / rel).resolve()
    if base not in f.parents:
        raise ValueError("Invalid path")
    return f

def import_workbook(path, keep_stock=True):
    from importer import read_workbook, enrich
    parts = enrich(read_workbook(path), ROOT / "data" / "research_items.json")
    return STORE.import_parts(parts, keep_stock=keep_stock)

# ---------------- sessions ----------------
def make_session(days=30):
    exp = str(int(time.time()) + days * 86400)
    sig = hmac.new(SECRET, exp.encode(), hashlib.sha256).hexdigest()
    return f"{exp}.{sig}"

def valid_session(tok):
    try:
        exp, sig = tok.split(".", 1)
        return int(exp) > time.time() and hmac.compare_digest(sig, hmac.new(SECRET, exp.encode(), hashlib.sha256).hexdigest())
    except Exception:
        return False

def cookies(header):
    out = {}
    for part in (header or "").split(";"):
        if "=" in part:
            k, v = part.strip().split("=", 1); out[k] = v
    return out

LOGIN_HTML = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sign in · Spare Parts</title><style>
:root{--bg:#0f1216;--panel:#161a20;--line:#323a46;--text:#e7eaee;--muted:#8b95a3;--accent:#e8a33d;color-scheme:dark}
@media (prefers-color-scheme:light){:root{--bg:#f4f5f7;--panel:#fff;--line:#d4d8de;--text:#161a20;--muted:#667080;--accent:#c97f12;color-scheme:light}}
body{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--text);font:15px "Segoe UI",system-ui,sans-serif;padding:16px;box-sizing:border-box}
form{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:28px;width:min(340px,100%);box-sizing:border-box}
h1{font-size:20px;margin:0 0 4px}p{color:var(--muted);margin:0 0 20px}
label{display:block;font-size:13px;color:var(--muted);margin-bottom:12px}
input{display:block;width:100%;box-sizing:border-box;margin-top:4px;padding:10px 12px;border-radius:8px;border:1px solid var(--line);background:transparent;color:var(--text);font:inherit}
input:focus{outline:0;border-color:var(--accent)}
button{width:100%;padding:11px;border:0;border-radius:8px;background:var(--accent);color:#1a1206;font:600 15px inherit;cursor:pointer;margin-top:6px}
.err{color:#ef5d5d;font-size:13px;margin:-6px 0 12px}
</style></head><body><form method="post" action="/login">
<h1>Spare Parts</h1><p>Jönköping inventory</p>
__ERR__
<label>Your name<input name="user" autocomplete="name" value="__USER__" placeholder="Shown in the activity log"></label>
<label>Password<input name="password" type="password" autocomplete="current-password" autofocus required></label>
<input type="hidden" name="next" value="__NEXT__"><button>Sign in</button></form></body></html>"""

# ---------------- live events (Server-Sent Events) ----------------
SUBSCRIBERS = set()
SUB_LOCK = threading.Lock()

def broadcast(event):
    ev = {k: v for k, v in event.items() if k != "part"}
    data = json.dumps(ev)
    with SUB_LOCK:
        for q in list(SUBSCRIBERS):
            try: q.put_nowait(data)
            except queue.Full: pass
STORE.listeners.append(broadcast)

# ---------------- HTTP ----------------
class Handler(BaseHTTPRequestHandler):
    server_version = "SpareParts/2.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        line = fmt % args
        if "/api/events" not in line and "/api/sync/status" not in line:
            sys.stderr.write("%s  %s\n" % (time.strftime("%H:%M:%S"), line))

    # -- plumbing
    def _send(self, code, body=b"", ctype="application/json; charset=utf-8", headers=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        for k, v in (headers or {}).items():
            if isinstance(v, list):
                for x in v: self.send_header(k, x)
            else:
                self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _redirect(self, to, headers=None):
        self._send(303, b"", "text/plain", {"Location": to, **(headers or {})})

    def _body(self, limit=200 * 1024 * 1024):
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit:
            raise ValueError("Upload too large")
        return self.rfile.read(n) if n else b""

    def _json(self):
        b = self._body(20 * 1024 * 1024)
        return json.loads(b) if b else {}

    def _authed(self, qs):
        if not PASSWORD:
            return True
        key = self.headers.get("X-Api-Key") or qs.get("key", [""])[0]
        if key and hmac.compare_digest(key, PASSWORD):
            return True
        return valid_session(cookies(self.headers.get("Cookie")).get("sp_session", ""))

    def _https(self):
        return self.headers.get("X-Forwarded-Proto", "") == "https"

    def do_OPTIONS(self):
        self._send(204, b"", headers={"Access-Control-Allow-Methods": "GET,POST,PATCH,DELETE,OPTIONS",
                                      "Access-Control-Allow-Headers": "Content-Type,X-Api-Key,X-User,X-Filename"})

    def do_GET(self): self._route("GET")
    def do_HEAD(self): self._route("GET")
    def do_POST(self): self._route("POST")
    def do_PATCH(self): self._route("PATCH")
    def do_DELETE(self): self._route("DELETE")

    def _route(self, method):
        u = urlparse(self.path)
        path, qs = unquote(u.path), parse_qs(u.query)
        try:
            if path == "/login":
                return self._login(method)
            if path == "/logout":
                return self._redirect("/login", {"Set-Cookie": "sp_session=; Path=/; Max-Age=0"})
            if path == "/api/ping":
                return self._send(200, {"ok": True, "auth": bool(PASSWORD), "authed": self._authed(qs),
                                        "version": STORE.version, "mode": "local" if REPLICA else "cloud"})
            if path.startswith("/api/"):
                if not self._authed(qs):
                    return self._send(401, {"error": "Sign-in required"})
                return self._api(method, path[5:], qs)
            if path in ("/app.css", "/favicon.ico"):
                return self._static(path)
            if not self._authed(qs):
                return self._redirect("/login?next=" + quote(self.path))
            if path.startswith("/files/") and method == "GET":
                return self._file(path[7:])
            return self._static(path)
        except KeyError as e:
            self._send(404, {"error": f"Not found: {e}"})
        except ValueError as e:
            self._send(400, {"error": str(e)})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            traceback.print_exc()
            self._send(500, {"error": str(e)})

    def _login(self, method):
        if not PASSWORD:
            return self._redirect("/")
        err, user, nxt = "", "", "/"
        if method == "POST":
            form = parse_qs(self._body(10000).decode())
            user = form.get("user", [""])[0].strip()
            nxt = form.get("next", ["/"])[0] or "/"
            if not nxt.startswith("/") or nxt.startswith("//"):
                nxt = "/"
            if hmac.compare_digest(form.get("password", [""])[0], PASSWORD):
                secure = "; Secure" if self._https() else ""
                return self._redirect(nxt, {"Set-Cookie": [
                    f"sp_session={make_session()}; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax{secure}",
                    f"sp_user={quote(user)}; Path=/; Max-Age=31536000; SameSite=Lax{secure}"]})
            time.sleep(1.5)
            err = '<div class="err">Wrong password.</div>'
        else:
            nxt = parse_qs(urlparse(self.path).query).get("next", ["/"])[0]
            user = unquote(cookies(self.headers.get("Cookie")).get("sp_user", ""))
        page = (LOGIN_HTML.replace("__ERR__", err).replace("__USER__", html.escape(user))
                .replace("__NEXT__", html.escape(nxt if nxt.startswith("/") else "/")))
        self._send(401 if err else 200, page, "text/html; charset=utf-8")

    def _static(self, path):
        if path in ("/", "") or path.startswith("/p/") or path == "/index.html":
            f = WEB / "index.html"
        else:
            f = (WEB / path.lstrip("/")).resolve()
            if WEB.resolve() not in f.parents or not f.is_file():
                return self._send(404, {"error": "not found"})
        ctype = mimetypes.guess_type(f.name)[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype.endswith("javascript"):
            ctype += "; charset=utf-8"
        self._send(200, f.read_bytes(), ctype, {"Cache-Control": "no-cache"})

    def _file(self, rel):
        if not research_dir(): return self._send(404, {"error": "no file storage configured"})
        f = safe_path(rel)
        if not f.is_file():
            return self._send(404, {"error": "not found"})
        self._send(200, f.read_bytes(), mimetypes.guess_type(f.name)[0] or "application/octet-stream",
                   {"Content-Disposition": f'inline; filename="{quote(f.name)}"'})

    def _events(self):
        q = queue.Queue(maxsize=200)
        with SUB_LOCK: SUBSCRIBERS.add(q)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Accel-Buffering", "no")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        try:
            self.wfile.write(f"data: {json.dumps({'type': 'hello', 'version': STORE.version})}\n\n".encode()); self.wfile.flush()
            while True:
                try:
                    data = q.get(timeout=20)
                    self.wfile.write(f"data: {data}\n\n".encode())
                except queue.Empty:
                    self.wfile.write(b": ping\n\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            with SUB_LOCK: SUBSCRIBERS.discard(q)
            self.close_connection = True

    def _with_files(self, p):
        p = dict(p); p["files"] = local_files(p["pn"]); p["qr"] = f"{public_url()}/p/{quote(p['pn'])}"
        return p

    def _api(self, method, route, qs):
        seg = [s for s in route.split("/") if s]
        user = unquote(self.headers.get("X-User", ""))
        one = lambda k, d="": qs.get(k, [d])[0]
        if seg == ["events"]:
            return self._events()
        if seg == ["sync", "status"]:
            return self._send(200, SYNC.status() if SYNC else {"mode": "cloud" if os.environ.get("RAILWAY_ENVIRONMENT") else "standalone"})
        if seg == ["sync", "now"] and method == "POST":
            if SYNC: threading.Thread(target=SYNC.run_once, kwargs={"files": True}, daemon=True).start()
            return self._send(200, {"ok": True})
        if seg == ["sync", "seed"] and method == "POST":
            if STORE.count():
                raise ValueError("Cloud already has data – not seeding")
            b = self._json()
            STORE.import_parts(b.get("parts", []), keep_stock=False)
            STORE.seed_movements(b.get("movements", []))
            return self._send(200, {"ok": True, "parts": STORE.count()})
        if seg == ["parts"] and method == "GET":
            since = one("since")
            if since and since.isdigit() and int(since) == STORE.version:
                return self._send(200, {"version": STORE.version, "unchanged": True})
            return self._send(200, {"version": STORE.version, "public_url": public_url(), "parts": STORE.all(),
                                    "mode": "local" if REPLICA else "cloud"})
        if seg == ["parts"] and method == "POST":
            return self._send(201, STORE.create(self._json()))
        if len(seg) >= 2 and seg[0] == "parts":
            pn = seg[1]
            if len(seg) == 2:
                if method == "GET":
                    p = STORE.get(pn)
                    if not p: raise KeyError(pn)
                    return self._send(200, self._with_files(p))
                if method == "PATCH":
                    b = self._json()
                    ts = b.pop("_ts", None)
                    p = STORE.update(pn, b, ts=ts)
                    if not p: raise KeyError(pn)
                    return self._send(200, p)
                if method == "DELETE":
                    if not STORE.delete(pn): raise KeyError(pn)
                    return self._send(200, {"ok": True})
            if len(seg) == 3 and seg[2] == "adjust" and method == "POST":
                b = self._json()
                p = STORE.adjust(pn, delta=b.get("delta"), set_to=b.get("set"), reason=b.get("reason", ""),
                                 user=b.get("user") or user, source=b.get("source", "web"),
                                 client_id=b.get("client_id"), ts=b.get("ts"))
                return self._send(200, p)
            if len(seg) == 3 and seg[2] == "movements":
                return self._send(200, STORE.movements(pn, int(one("limit", "50"))))
            if len(seg) == 3 and seg[2] == "files" and method == "POST":
                p = STORE.get(pn)
                if not p: raise KeyError(pn)
                if not research_dir(): raise ValueError("No file storage configured")
                name = safe_name(unquote(self.headers.get("X-Filename", "file")))
                folder = part_folder(p); folder.mkdir(parents=True, exist_ok=True)
                target = folder / name
                i = 1
                while target.exists():
                    target = folder / f"{Path(name).stem} ({i}){Path(name).suffix}"; i += 1
                target.write_bytes(self._body())
                STORE._bump({"type": "files", "pn": p["pn"]})
                if SYNC: SYNC.poke_files()
                return self._send(201, {"ok": True, "files": local_files(p["pn"])})
        if seg == ["files"]:
            if method == "GET":
                return self._send(200, all_files())
            if method == "POST":
                if not research_dir(): raise ValueError("No file storage configured")
                f = safe_path(one("path"))
                f.parent.mkdir(parents=True, exist_ok=True)
                tmp = f.with_name(f.name + ".part")
                tmp.write_bytes(self._body()); os.replace(tmp, f)
                STORE._bump({"type": "files", "path": one("path")})
                return self._send(201, {"ok": True})
        if seg == ["adjust"] and method == "POST":   # batch – Android offline queue and PC sync
            results = []
            for b in self._json().get("items", []):
                try:
                    STORE.adjust(b["pn"], delta=b.get("delta"), set_to=b.get("set"), reason=b.get("reason", ""),
                                 user=b.get("user") or user, source=b.get("source", "android"),
                                 client_id=b.get("client_id"), ts=b.get("ts"))
                    results.append({"id": b.get("id"), "ok": True})
                except Exception as e:
                    results.append({"id": b.get("id"), "ok": False, "error": str(e)})
            return self._send(200, {"results": results, "version": STORE.version})
        if seg == ["search"]:
            return self._send(200, STORE.search(one("q"), int(one("limit", "25"))))
        if seg == ["lookup"]:
            text = one("q") if method == "GET" else self._json().get("text", "")
            return self._send(200, STORE.lookup(text))
        if seg == ["stats"]:
            return self._send(200, STORE.stats())
        if seg == ["low"]:
            return self._send(200, STORE.low_stock())
        if seg == ["movements"]:
            after = one("after_id")
            return self._send(200, STORE.movements(None, int(one("limit", "100")),
                                                   after_id=int(after) if after.isdigit() else None))
        if seg == ["export.csv"]:
            return self._send(200, STORE.export_csv(), "text/csv; charset=utf-8",
                              {"Content-Disposition": 'attachment; filename="spare-parts.csv"'})
        if seg == ["export.json"]:
            return self._send(200, STORE.all(), headers={"Content-Disposition": 'attachment; filename="spare-parts.json"'})
        if seg == ["import"] and method == "POST":
            name = self.headers.get("X-Filename", "upload.xlsx")
            ext = Path(name).suffix.lower()
            if ext not in (".ods", ".xlsx", ".xls"):
                raise ValueError("Upload an .ods or .xlsx workbook")
            data = self._body()
            if SYNC:   # the cloud is the primary copy – import there, then pull
                SYNC._req("POST", "/api/import" + ("?replace=1" if one("replace") == "1" else ""), raw=data,
                          headers={"X-Filename": name}, timeout=120)
                SYNC.run_once()
                return self._send(200, {"imported": STORE.count()})
            (ROOT / "data").mkdir(exist_ok=True)
            tmp = ROOT / "data" / f"_upload{ext}"
            tmp.write_bytes(data)
            count = import_workbook(tmp, keep_stock=one("replace") != "1")
            return self._send(200, {"imported": count})
        raise KeyError(route)

def main():
    global SYNC
    mode = "local copy synced with " + CFG["sync"]["remote_url"] if REPLICA else "primary"
    print(f"Mode: {mode}")
    if REPLICA:
        from sync import Syncer
        SYNC = Syncer(STORE, CFG["sync"]["remote_url"], CFG["sync"]["key"] or PASSWORD,
                      research_dir(), int(CFG["sync"].get("interval") or 30))
        SYNC.poke_files = lambda: (setattr(SYNC, "last_files", 0), SYNC.poke())
        SYNC.listeners.append(broadcast)
        STORE.on_outbox = SYNC.poke
        print("Syncing with the cloud …")
        print("  in sync" if SYNC.run_once(files=True) else f"  {SYNC.last_error} – working offline, will retry")
        SYNC.start()
    if STORE.count() == 0:
        src = resolve(CFG["source_workbook"])
        if src.exists():
            print(f"Empty database – importing {src.name} …")
            print(f"Imported {import_workbook(src)} parts")
            if SYNC:
                STORE.meta("remote_version", "")   # force a full comparison so an empty cloud gets seeded
                SYNC.poke()
    slack = CFG["slack"]
    if slack["bot_token"] and slack["app_token"] and not REPLICA:
        try:
            import slack_bot
            slack_bot.start(STORE, CFG, public_url)
        except Exception as e:
            print("Slack bot not started:", e)
    elif REPLICA:
        print("Slack bot runs in the cloud (disabled here)")
    else:
        print("Slack bot disabled (no tokens)")
    if not PASSWORD and not REPLICA and os.environ.get("RAILWAY_ENVIRONMENT"):
        print("WARNING: APP_PASSWORD is not set – the inventory is open to anyone with the link!")
    srv = ThreadingHTTPServer((CFG["host"], int(CFG["port"])), Handler)
    srv.daemon_threads = True
    print(f"\n  Spare Parts Inventory running\n  Local:   http://localhost:{CFG['port']}\n  Network: http://{lan_ip()}:{CFG['port']}\n  QR/phone address: {public_url()}\n", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass

if __name__ == "__main__":
    main()

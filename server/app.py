"""Spare Parts Inventory – server.

Runs in two modes:
  * cloud   – on Railway: the primary copy. Settings come from environment variables.
  * local   – on the site PC: a full copy that syncs with the cloud (config.json → sync).
Everything is Python standard library except the optional Slack bot (slack_bolt)
and workbook import (pandas + odfpy/openpyxl).
"""
import base64, hashlib, hmac, html, json, mimetypes, os, queue, re, secrets, socket, sys, threading, time, traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs, unquote, quote

ROOT = Path(__file__).resolve().parent.parent
WEB = Path(__file__).resolve().parent / "web"
sys.path.insert(0, str(Path(__file__).resolve().parent))
from store import Store  # noqa: E402
from accounts import Accounts, ROLES, ROLE_TEXT, LIGHT_FIELDS, check_username, check_password  # noqa: E402
from sync import read_file, write_file, file_size  # path helpers (Windows long paths)

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
    "slack": {"bot_token": "", "app_token": "", "alert_channel": "", "command": "/parts", "role": "staff"},
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
                 ("alert_channel", "SLACK_ALERT_CHANNEL"), ("command", "SLACK_COMMAND"), ("role", "SLACK_ROLE")):
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
ACC = Accounts(STORE)
SYNC = None

class Denied(Exception):
    """Signed in, but not allowed to do this."""

BOOTSTRAP_USER = {"id": 0, "username": "setup", "name": "Setup", "role": "admin", "kind": "person",
                  "active": 1, "must_change": 0, "sess_ver": 1,
                  "perms": sorted(__import__("accounts").ROLE_PERMS["admin"])}

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
                out.append({"title": f.name, "url": "/files/" + quote(rel), "size": file_size(f)})
    return out

def all_files():
    base = research_dir()
    if not base or not base.exists():
        return []
    return [{"path": f.relative_to(base).as_posix(), "size": file_size(f)}
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
def make_session(uid, sess_ver, days=30):
    body = f"{uid}.{sess_ver}.{int(time.time()) + days * 86400}"
    return f"{body}.{hmac.new(SECRET, body.encode(), hashlib.sha256).hexdigest()}"

def session_user(tok):
    """The signed-in account, or None. Disabling an account or changing its
    password raises sess_ver, which invalidates every session it had."""
    try:
        uid, ver, exp, sig = tok.split(".")
        body = f"{uid}.{ver}.{exp}"
        if not hmac.compare_digest(sig, hmac.new(SECRET, body.encode(), hashlib.sha256).hexdigest()):
            return None
        if int(exp) < time.time():
            return None
        user = ACC.by_id(int(uid))
        if user and user["active"] and str(user["sess_ver"]) == ver and user["kind"] == "person":
            return user
        return None
    except Exception:
        return None

def cookies(header):
    out = {}
    for part in (header or "").split(";"):
        if "=" in part:
            k, v = part.strip().split("=", 1); out[k] = v
    return out

PAGE_CSS = """
:root{--bg:#0f1216;--panel:#161a20;--line:#323a46;--text:#e7eaee;--muted:#8b95a3;--accent:#e8a33d;color-scheme:dark}
@media (prefers-color-scheme:light){:root{--bg:#f4f5f7;--panel:#fff;--line:#d4d8de;--text:#161a20;--muted:#667080;--accent:#c97f12;color-scheme:light}}
body{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--text);font:15px "Segoe UI",system-ui,sans-serif;padding:16px;box-sizing:border-box}
form{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:28px;width:min(360px,100%);box-sizing:border-box}
h1{font-size:20px;margin:0 0 4px}p{color:var(--muted);margin:0 0 20px;font-size:14px}
label{display:block;font-size:13px;color:var(--muted);margin-bottom:12px}
input{display:block;width:100%;box-sizing:border-box;margin-top:4px;padding:10px 12px;border-radius:8px;border:1px solid var(--line);background:transparent;color:var(--text);font:inherit}
input:focus{outline:0;border-color:var(--accent)}
button{width:100%;padding:11px;border:0;border-radius:8px;background:var(--accent);color:#1a1206;font:600 15px inherit;cursor:pointer;margin-top:6px}
.err{color:#ef5d5d;font-size:13px;margin:-6px 0 12px}
.note{color:var(--muted);font-size:12px;margin-top:14px;text-align:center}
"""

LOGIN_HTML = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sign in - Spare Parts</title><style>__CSS__</style></head><body><form method="post" action="/login">
<h1>Spare Parts</h1><p>J&#246;nk&#246;ping inventory</p>
__ERR__
<label>Username<input name="username" autocomplete="username" value="__USER__" autofocus required></label>
<label>Password<input name="password" type="password" autocomplete="current-password" required></label>
<input type="hidden" name="next" value="__NEXT__"><button>Sign in</button>
<div class="note">Forgotten your password? Ask an administrator to reset it.</div></form></body></html>"""

PASSWORD_HTML = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Choose a password - Spare Parts</title><style>__CSS__</style></head><body><form method="post" action="/password">
<h1>Choose a password</h1><p>__INTRO__</p>
__ERR__
<label>Current password<input name="current" type="password" autocomplete="current-password" autofocus required></label>
<label>New password<input name="new" type="password" autocomplete="new-password" minlength="8" required></label>
<label>Repeat new password<input name="again" type="password" autocomplete="new-password" minlength="8" required></label>
<input type="hidden" name="next" value="__NEXT__"><button>Save password</button>
<div class="note">At least 8 characters.</div></form></body></html>"""

def page(tmpl, err="", **kw):
    out = tmpl.replace("__CSS__", PAGE_CSS).replace("__ERR__", f'<div class="err">{html.escape(err)}</div>' if err else "")
    for k, v in kw.items():
        out = out.replace(f"__{k.upper()}__", html.escape(str(v)))
    return out

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

    def _user(self, qs):
        """Who is making this request: a device token, a signed-in session, or
        the bootstrap password while no accounts exist yet."""
        if getattr(self, "_cached_user", "?") != "?":
            return self._cached_user
        user = None
        key = self.headers.get("X-Api-Key") or qs.get("key", [""])[0]
        if key:
            user = ACC.by_token(key)
            if not user and PASSWORD and ACC.count() == 0 and hmac.compare_digest(key, PASSWORD):
                user = BOOTSTRAP_USER
        if not user:
            user = session_user(cookies(self.headers.get("Cookie")).get("sp_session", ""))
        if not user and not PASSWORD and ACC.count() == 0:
            user = BOOTSTRAP_USER              # brand new, unprotected install
        self._cached_user = user
        return user

    def _can(self, user, perm):
        return bool(user) and perm in user.get("perms", [])

    def _require(self, user, perm):
        if not self._can(user, perm):
            raise Denied(f"Your account ({user['role']}) is not allowed to do this" if user else "Sign-in required")

    def _client_ip(self):
        return (self.headers.get("X-Forwarded-For", "").split(",")[0].strip() or self.client_address[0])

    def _https(self):
        return self.headers.get("X-Forwarded-Proto", "") == "https"

    def do_OPTIONS(self):
        self._send(204, b"", headers={"Access-Control-Allow-Methods": "GET,POST,PATCH,DELETE,OPTIONS",
                                      "Access-Control-Allow-Headers": "Content-Type,X-Api-Key,X-User,X-Actor,X-Filename"})

    def do_GET(self): self._route("GET")
    def do_HEAD(self): self._route("GET")
    def do_POST(self): self._route("POST")
    def do_PATCH(self): self._route("PATCH")
    def do_DELETE(self): self._route("DELETE")

    def _route(self, method):
        self._cached_user = "?"      # keep-alive reuses this handler for several requests
        u = urlparse(self.path)
        path, qs = unquote(u.path), parse_qs(u.query)
        try:
            if path == "/login":
                return self._login(method)
            if path == "/logout":
                u = self._user(qs)
                if u: ACC.log(u["username"], "signout", u["username"], "", ip=self._client_ip())
                return self._redirect("/login", {"Set-Cookie": "sp_session=; Path=/; Max-Age=0"})
            if path == "/api/ping":
                u = self._user(qs)
                return self._send(200, {"ok": True, "auth": True, "authed": bool(u),
                                        "user": u["username"] if u else None,
                                        "accounts": ACC.count(),
                                        "version": STORE.version, "mode": "local" if REPLICA else "cloud"})
            user = self._user(qs)
            if path == "/password":
                return self._password_page(method, user)
            if path.startswith("/api/"):
                if not user:
                    return self._send(401, {"error": "Sign-in required"})
                return self._api(method, path[5:], qs, user)
            if path in ("/app.css", "/favicon.ico"):
                return self._static(path)
            if not user:
                return self._redirect("/login?next=" + quote(self.path))
            if user.get("must_change"):
                return self._redirect("/password?next=" + quote(self.path))
            if path.startswith("/files/") and method == "GET":
                return self._file(path[7:])
            return self._static(path)
        except Denied as e:
            self._send(403, {"error": str(e)})
        except KeyError as e:
            self._send(404, {"error": f"Not found: {e}"})
        except ValueError as e:
            self._send(400, {"error": str(e)})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            traceback.print_exc()
            self._send(500, {"error": str(e)})

    @staticmethod
    def _safe_next(nxt):
        nxt = nxt or "/"
        return nxt if nxt.startswith("/") and not nxt.startswith("//") else "/"

    def _cookie_bits(self, user, days=30):
        secure = "; Secure" if self._https() else ""
        return [f"sp_session={make_session(user['id'], user['sess_ver'], days)}; Path=/; Max-Age={days*86400}; HttpOnly; SameSite=Lax{secure}",
                f"sp_user={quote(user['username'])}; Path=/; Max-Age=31536000; SameSite=Lax{secure}"]

    def _login(self, method):
        err, username, nxt = "", "", "/"
        if method == "POST":
            form = parse_qs(self._body(10000).decode())
            username = form.get("username", [""])[0].strip()
            nxt = self._safe_next(form.get("next", ["/"])[0])
            user, err = ACC.verify(username, form.get("password", [""])[0])
            if user:
                ACC.log(user["username"], "signin", user["username"], "", ip=self._client_ip())
                target = "/password?next=" + quote(nxt) if user["must_change"] else nxt
                return self._redirect(target, {"Set-Cookie": self._cookie_bits(user)})
            time.sleep(1.0)
        else:
            nxt = self._safe_next(parse_qs(urlparse(self.path).query).get("next", ["/"])[0])
            username = unquote(cookies(self.headers.get("Cookie")).get("sp_user", ""))
        self._send(401 if err else 200, page(LOGIN_HTML, err, user=username, next=nxt), "text/html; charset=utf-8")

    def _password_page(self, method, user):
        if not user or user["kind"] == "device":
            return self._redirect("/login?next=/password")
        nxt, err = "/", ""
        intro = ("This account still uses the password an administrator gave you. Choose your own."
                 if user["must_change"] else "Change the password for " + user["username"] + ".")
        if method == "POST":
            form = parse_qs(self._body(10000).decode())
            nxt = self._safe_next(form.get("next", ["/"])[0])
            new, again = form.get("new", [""])[0], form.get("again", [""])[0]
            ok, verr = ACC.verify(user["username"], form.get("current", [""])[0])
            if not ok:
                err = verr or "Current password is wrong"
            elif new != again:
                err = "The two new passwords do not match"
            elif new == form.get("current", [""])[0]:
                err = "Choose a different password"
            else:
                try:
                    check_password(new)
                    ACC.set_password(user["id"], new, must_change=False, by=user["username"])
                    fresh = ACC.by_id(user["id"])
                    return self._redirect(nxt, {"Set-Cookie": self._cookie_bits(fresh)})
                except ValueError as e:
                    err = str(e)
        else:
            nxt = self._safe_next(parse_qs(urlparse(self.path).query).get("next", ["/"])[0])
        self._send(400 if err else 200, page(PASSWORD_HTML, err, intro=intro, next=nxt), "text/html; charset=utf-8")

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
        self._send(200, read_file(f), mimetypes.guess_type(f.name)[0] or "application/octet-stream",
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

    def _api(self, method, route, qs, user):
        seg = [s for s in route.split("/") if s]
        who = user["username"]
        actor = (unquote(self.headers.get("X-Actor", "")) or who) if user["kind"] == "device" else who
        one = lambda k, d="": qs.get(k, [d])[0]
        log = lambda action, target="", detail="": ACC.log(who, action, target, detail,
                                                           source="api", ip=self._client_ip())

        # ---- who am I ----
        if seg == ["me"]:
            if method == "GET":
                return self._send(200, {"user": {k: user[k] for k in ("id", "username", "name", "role", "kind", "perms", "must_change")},
                                        "roles": [{"id": r, "text": ROLE_TEXT[r]} for r in ROLES],
                                        "mode": "local" if REPLICA else "cloud", "can_manage_users": not REPLICA})
            if method == "POST":            # change own password from the app
                b = self._json()
                ok, err = ACC.verify(user["username"], b.get("current", ""))
                if not ok:
                    raise Denied(err or "Current password is wrong")
                ACC.set_password(user["id"], b.get("new", ""), must_change=False, by=who)
                fresh = ACC.by_id(user["id"])
                return self._send(200, {"ok": True}, headers={"Set-Cookie": self._cookie_bits(fresh)})

        # ---- accounts (admin) ----
        if seg and seg[0] == "users":
            self._require(user, "users")
            if REPLICA and method != "GET":
                raise Denied("Accounts are managed in the cloud copy, not here")
            if len(seg) == 1:
                if method == "GET":
                    return self._send(200, ACC.list())
                if method == "POST":
                    b = self._json()
                    created = ACC.add(check_username(b.get("username")), b.get("name", ""), b.get("role", "viewer"),
                                      password=b.get("password"), kind=b.get("kind", "person"), by=who)
                    return self._send(201, created)
            if seg == ["users", "export"]:          # for the PC copy
                return self._send(200, {"users": ACC.export()})
            if len(seg) >= 2 and seg[1].isdigit():
                uid = int(seg[1])
                if len(seg) == 2:
                    if method == "GET":
                        u = ACC.by_id(uid)
                        if not u: raise KeyError(uid)
                        return self._send(200, u)
                    if method == "PATCH":
                        return self._send(200, ACC.update(uid, self._json(), by=who))
                    if method == "DELETE":
                        if uid == user["id"]:
                            raise Denied("You cannot delete your own account")
                        ACC.delete(uid, by=who)
                        return self._send(200, {"ok": True})
                if seg[2:] == ["password"] and method == "POST":
                    pw = self._json().get("password") or secrets.token_urlsafe(9)
                    ACC.set_password(uid, pw, must_change=True, by=who)
                    return self._send(200, {"ok": True, "password": pw})
                if seg[2:] == ["token"]:
                    if method == "POST":
                        return self._send(200, {"token": ACC.rotate_token(uid, by=who)})
                    if method == "DELETE":
                        ACC.clear_token(uid, by=who)
                        return self._send(200, {"ok": True})
        if seg == ["audit"]:
            self._require(user, "users")
            after = one("after_id")
            return self._send(200, ACC.audit(int(one("limit", "200")), int(after) if after.isdigit() else None))

        # ---- live + sync ----
        if seg == ["events"]:
            return self._events()
        if seg == ["sync", "status"]:
            return self._send(200, SYNC.status() if SYNC else {"mode": "cloud" if os.environ.get("RAILWAY_ENVIRONMENT") else "standalone"})
        if seg == ["sync", "now"] and method == "POST":
            if SYNC: threading.Thread(target=SYNC.run_once, kwargs={"files": True}, daemon=True).start()
            return self._send(200, {"ok": True})
        if seg == ["sync", "seed"] and method == "POST":
            self._require(user, "import")
            if STORE.count():
                raise ValueError("Cloud already has data - not seeding")
            b = self._json()
            STORE.import_parts(b.get("parts", []), keep_stock=False)
            STORE.seed_movements(b.get("movements", []))
            log("seed", "", f"{STORE.count()} parts")
            return self._send(200, {"ok": True, "parts": STORE.count()})

        # ---- inventory ----
        self._require(user, "view")
        if seg == ["parts"] and method == "GET":
            since = one("since")
            if since and since.isdigit() and int(since) == STORE.version:
                return self._send(200, {"version": STORE.version, "unchanged": True})
            return self._send(200, {"version": STORE.version, "public_url": public_url(), "parts": STORE.all(),
                                    "mode": "local" if REPLICA else "cloud"})
        if seg == ["parts"] and method == "POST":
            self._require(user, "edit")
            body = self._json(); body.pop("_user", None)
            p = STORE.create(body, user=actor)
            ACC.log(actor, "part.create", p["pn"], p["name"], source="api", ip=self._client_ip())
            return self._send(201, p)
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
                    b.pop("_user", None)
                    fields = set(b)
                    self._require(user, "edit" if fields - LIGHT_FIELDS else "edit_light")
                    before = STORE.get(pn)
                    if not before: raise KeyError(pn)
                    p = STORE.update(pn, b, ts=ts, user=actor)
                    changed = ", ".join(f"{k}: {before.get(k)!r} -> {b[k]!r}" for k in b if before.get(k) != b[k])
                    if changed:
                        ACC.log(actor, "part.edit", pn, changed[:400], source="api", ip=self._client_ip())
                    return self._send(200, p)
                if method == "DELETE":
                    self._require(user, "delete")
                    p = STORE.get(pn)
                    if not STORE.delete(pn, user=actor): raise KeyError(pn)
                    ACC.log(actor, "part.delete", pn, (p or {}).get("name", ""), source="api", ip=self._client_ip())
                    return self._send(200, {"ok": True})
            if len(seg) == 3 and seg[2] == "adjust" and method == "POST":
                self._require(user, "adjust")
                b = self._json()
                by = (b.get("user") or actor) if user["kind"] == "device" else who
                p = STORE.adjust(pn, delta=b.get("delta"), set_to=b.get("set"), reason=b.get("reason", ""),
                                 user=by, source=b.get("source", "web"),
                                 client_id=b.get("client_id"), ts=b.get("ts"))
                ACC.log(by, "stock", pn, f"{'set to' if b.get('set') is not None else 'change'} "
                        f"{b.get('set') if b.get('set') is not None else b.get('delta')} -> {p['on_hand']:g}"
                        + (f" ({b['reason']})" if b.get("reason") else ""), source=b.get("source", "web"), ip=self._client_ip())
                return self._send(200, p)
            if len(seg) == 3 and seg[2] == "movements":
                return self._send(200, STORE.movements(pn, int(one("limit", "50"))))
            if len(seg) == 3 and seg[2] == "files" and method == "POST":
                self._require(user, "files")
                p = STORE.get(pn)
                if not p: raise KeyError(pn)
                if not research_dir(): raise ValueError("No file storage configured")
                name = safe_name(unquote(self.headers.get("X-Filename", "file")))
                folder = part_folder(p); folder.mkdir(parents=True, exist_ok=True)
                target = folder / name
                i = 1
                while target.exists():
                    target = folder / f"{Path(name).stem} ({i}){Path(name).suffix}"; i += 1
                write_file(target, self._body())
                STORE._bump({"type": "files", "pn": p["pn"]})
                log("file.upload", pn, target.name)
                if SYNC: SYNC.poke_files()
                return self._send(201, {"ok": True, "files": local_files(p["pn"])})
        if seg == ["files"]:
            if method == "GET":
                return self._send(200, all_files())
            if method == "POST":
                self._require(user, "files")
                if not research_dir(): raise ValueError("No file storage configured")
                write_file(safe_path(one("path")), self._body())
                STORE._bump({"type": "files", "path": one("path")})
                if user["kind"] != "device":
                    log("file.upload", one("path"), "")
                return self._send(201, {"ok": True})
            if method == "DELETE":
                self._require(user, "files")
                f = safe_path(one("path"))
                if not f.is_file(): raise KeyError(one("path"))
                f.unlink()
                for d in list(f.parents):           # tidy up folders left empty
                    if d == research_dir() or any(d.iterdir()): break
                    d.rmdir()
                STORE._bump({"type": "files", "path": one("path")})
                log("file.delete", one("path"), "")
                return self._send(200, {"ok": True})
        if seg == ["adjust"] and method == "POST":   # batch - Android offline queue and PC sync
            self._require(user, "adjust")
            results = []
            for b in self._json().get("items", []):
                try:
                    by = (b.get("user") or actor) if user["kind"] == "device" else who
                    p = STORE.adjust(b["pn"], delta=b.get("delta"), set_to=b.get("set"), reason=b.get("reason", ""),
                                     user=by, source=b.get("source", "android"),
                                     client_id=b.get("client_id"), ts=b.get("ts"))
                    ACC.log(by, "stock", b["pn"], f"{'set to' if b.get('set') is not None else 'change'} "
                            f"{b.get('set') if b.get('set') is not None else b.get('delta')} -> {p['on_hand']:g}"
                            + (f" ({b['reason']})" if b.get("reason") else ""),
                            source=b.get("source", "android"), ip=self._client_ip())
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
            log("export", "csv", "")
            return self._send(200, STORE.export_csv(), "text/csv; charset=utf-8",
                              {"Content-Disposition": 'attachment; filename="spare-parts.csv"'})
        if seg == ["export.json"]:
            log("export", "json", "")
            return self._send(200, STORE.all(), headers={"Content-Disposition": 'attachment; filename="spare-parts.json"'})
        if seg == ["import"] and method == "POST":
            self._require(user, "import")
            name = self.headers.get("X-Filename", "upload.xlsx")
            ext = Path(name).suffix.lower()
            if ext not in (".ods", ".xlsx", ".xls"):
                raise ValueError("Upload an .ods or .xlsx workbook")
            data = self._body()
            if SYNC:   # the cloud is the primary copy - import there, then pull
                SYNC._req("POST", "/api/import" + ("?replace=1" if one("replace") == "1" else ""), raw=data,
                          headers={"X-Filename": name}, timeout=120)
                SYNC.run_once()
                return self._send(200, {"imported": STORE.count()})
            (ROOT / "data").mkdir(exist_ok=True)
            tmp = ROOT / "data" / f"_upload{ext}"
            tmp.write_bytes(data)
            count = import_workbook(tmp, keep_stock=one("replace") != "1")
            log("import", name, f"{count} parts")
            return self._send(200, {"imported": count})
        raise KeyError(route)

def main():
    global SYNC
    mode = "local copy synced with " + CFG["sync"]["remote_url"] if REPLICA else "primary"
    print(f"Mode: {mode}")
    if REPLICA:
        from sync import Syncer
        SYNC = Syncer(STORE, CFG["sync"]["remote_url"], CFG["sync"]["key"] or PASSWORD,
                      research_dir(), int(CFG["sync"].get("interval") or 30), accounts=ACC)
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
    if ACC.count() == 0 and not REPLICA:
        admin_user = os.environ.get("ADMIN_USER", "admin")
        admin_pw = os.environ.get("ADMIN_PASSWORD") or PASSWORD or secrets.token_urlsafe(9)
        ACC.add(admin_user, "Administrator", "admin", password=admin_pw, by="setup")
        print(f"Created the first account: {admin_user}")
        if not (os.environ.get("ADMIN_PASSWORD") or PASSWORD):
            print(f"  temporary password: {admin_pw}")
        else:
            print("  password: the one from ADMIN_PASSWORD / APP_PASSWORD (you must change it at first sign-in)")
    slack = CFG["slack"]
    if slack["bot_token"] and slack["app_token"] and not REPLICA:
        try:
            import slack_bot
            from accounts import perms_for
            slack_bot.start(STORE, CFG, public_url, perms_for(slack.get("role") or "staff"))
            print(f"Slack role: {slack.get('role') or 'staff'}")
        except Exception as e:
            print("Slack bot not started:", e)
    elif REPLICA:
        print("Slack bot runs in the cloud (disabled here)")
    else:
        print("Slack bot disabled (no tokens)")
    if ACC.count() == 0:
        print("WARNING: no accounts yet - the inventory is open until the first account is created")
    srv = ThreadingHTTPServer((CFG["host"], int(CFG["port"])), Handler)
    srv.daemon_threads = True
    print(f"\n  Spare Parts Inventory running\n  Local:   http://localhost:{CFG['port']}\n  Network: http://{lan_ip()}:{CFG['port']}\n  QR/phone address: {public_url()}\n", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass

if __name__ == "__main__":
    main()

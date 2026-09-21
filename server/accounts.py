"""Accounts, roles and the audit trail.

Roles (fixed):
  admin    everything, including managing accounts
  manager  edit parts, import, delete parts, everything staff can do
  staff    book stock in and out, set location / notes / minimum, upload manuals
  viewer   look, but change nothing

People sign in with a username and password. Phones and the site PC use a
personal device token (sent as the X-Api-Key header) so every change is
attributed to an account and follows its permissions.
"""
import hashlib, hmac, json, os, re, secrets, sqlite3, time

ROLES = ("admin", "manager", "staff", "viewer")
ROLE_PERMS = {
    "admin":   {"view", "adjust", "edit_light", "edit", "files", "import", "delete", "users"},
    "manager": {"view", "adjust", "edit_light", "edit", "files", "import", "delete"},
    "staff":   {"view", "adjust", "edit_light", "files"},
    "viewer":  {"view"},
}
ROLE_TEXT = {
    "admin": "Everything, including accounts",
    "manager": "Edit parts, import, delete",
    "staff": "Book stock, set location and notes",
    "viewer": "Read only",
}
LIGHT_FIELDS = {"location", "notes", "min_qty"}      # what 'staff' may edit on a part
MAX_FAILED = 5
LOCK_SECONDS = 900
PBKDF_ROUNDS = 240_000

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE,
  name TEXT,
  role TEXT NOT NULL DEFAULT 'viewer',
  kind TEXT NOT NULL DEFAULT 'person',      -- person | device
  pw_hash TEXT, pw_salt TEXT,
  token_hash TEXT,
  active INTEGER NOT NULL DEFAULT 1,
  must_change INTEGER NOT NULL DEFAULT 0,
  sess_ver INTEGER NOT NULL DEFAULT 1,
  created REAL, updated REAL, last_login REAL,
  failed INTEGER NOT NULL DEFAULT 0, locked_until REAL NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL, username TEXT, action TEXT,
  target TEXT, detail TEXT, source TEXT, ip TEXT
);
CREATE INDEX IF NOT EXISTS audit_ts ON audit(ts);
"""

def now():
    return time.time()

def perms_for(role):
    return ROLE_PERMS.get(role, set())

def hash_password(password, salt=None):
    salt = salt or secrets.token_hex(16)
    h = hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), PBKDF_ROUNDS).hex()
    return h, salt

def token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()

def check_username(u):
    u = (u or "").strip().lower()
    if not re.fullmatch(r"[a-z0-9._-]{2,32}", u):
        raise ValueError("Username: 2-32 characters, letters, digits, dot, dash or underscore")
    return u

def check_password(p):
    if len(p or "") < 8:
        raise ValueError("Password must be at least 8 characters")
    return p

class Accounts:
    """Users + audit, stored in the same SQLite database as the inventory."""

    def __init__(self, store):
        self.store = store
        self.db = store.db
        self.lock = store.lock
        with self.lock:
            self.db.executescript(SCHEMA)
            self.db.commit()

    # ---------- reads ----------
    def _row(self, r, with_secrets=False):
        if not r:
            return None
        d = dict(r)
        if not with_secrets:
            for k in ("pw_hash", "pw_salt", "token_hash"):
                d.pop(k, None)
        d["perms"] = sorted(perms_for(d["role"]))
        d["has_token"] = bool(r["token_hash"]) if "token_hash" in r.keys() else False
        return d

    def count(self):
        return self.db.execute("SELECT COUNT(*) FROM users").fetchone()[0]

    def by_id(self, uid, with_secrets=False):
        return self._row(self.db.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone(), with_secrets)

    def by_username(self, username, with_secrets=False):
        return self._row(self.db.execute("SELECT * FROM users WHERE username=?",
                                         ((username or "").strip().lower(),)).fetchone(), with_secrets)

    def by_token(self, token):
        if not token:
            return None
        r = self.db.execute("SELECT * FROM users WHERE token_hash=? AND active=1", (token_hash(token),)).fetchone()
        return self._row(r)

    def list(self):
        rows = self.db.execute("SELECT * FROM users ORDER BY kind, username").fetchall()
        return [self._row(r) for r in rows]

    # ---------- writes ----------
    def add(self, username, name="", role="viewer", password=None, kind="person", by="system"):
        username = check_username(username)
        if role not in ROLES:
            raise ValueError(f"Unknown role {role}")
        if self.by_username(username):
            raise ValueError(f"'{username}' already exists")
        token = None
        pw_hash = pw_salt = None
        must_change = 0
        with self.lock:
            if kind == "device":
                token = secrets.token_urlsafe(24)
            else:
                pw_hash, pw_salt = hash_password(check_password(password))
                must_change = 1
            self.db.execute(
                "INSERT INTO users (username,name,role,kind,pw_hash,pw_salt,token_hash,active,must_change,created,updated)"
                " VALUES (?,?,?,?,?,?,?,1,?,?,?)",
                (username, name or username, role, kind, pw_hash, pw_salt,
                 token_hash(token) if token else None, must_change, now(), now()))
            self.db.commit()
        self.log(by, "user.add", username, f"role={role} kind={kind}")
        user = self.by_username(username)
        if token:
            user["token"] = token          # shown once
        return user

    def update(self, uid, changes, by="system"):
        user = self.by_id(uid)
        if not user:
            raise KeyError(uid)
        sets, vals, detail = [], [], []
        if "name" in changes:
            sets.append("name=?"); vals.append(str(changes["name"])[:80]); detail.append("name")
        if "role" in changes:
            if changes["role"] not in ROLES:
                raise ValueError("Unknown role")
            if user["kind"] == "person" and user["role"] == "admin" and changes["role"] != "admin" and self.admin_count() <= 1:
                raise ValueError("There must be at least one admin")
            sets.append("role=?"); vals.append(changes["role"]); detail.append(f"role={changes['role']}")
        if "active" in changes:
            active = 1 if changes["active"] else 0
            if not active and user["kind"] == "person" and user["role"] == "admin" and self.admin_count() <= 1:
                raise ValueError("There must be at least one active admin")
            sets.append("active=?"); vals.append(active); detail.append("active" if active else "disabled")
        if not sets:
            return user
        sets.append("updated=?"); vals.append(now())
        if "active" in changes and not changes["active"]:
            sets.append("sess_ver=sess_ver+1")            # sign the person out everywhere
        with self.lock:
            self.db.execute(f"UPDATE users SET {','.join(sets)} WHERE id=?", vals + [uid])
            self.db.commit()
        self.log(by, "user.update", user["username"], ", ".join(detail))
        return self.by_id(uid)

    def set_password(self, uid, password, must_change=False, by="system"):
        user = self.by_id(uid)
        if not user:
            raise KeyError(uid)
        if user["kind"] == "device":
            raise ValueError("Device accounts use a token, not a password")
        h, s = hash_password(check_password(password))
        with self.lock:
            self.db.execute("UPDATE users SET pw_hash=?,pw_salt=?,must_change=?,failed=0,locked_until=0,"
                            "sess_ver=sess_ver+1,updated=? WHERE id=?",
                            (h, s, 1 if must_change else 0, now(), uid))
            self.db.commit()
        self.log(by, "user.password", user["username"], "reset" if must_change else "changed")
        return self.by_id(uid)

    def rotate_token(self, uid, by="system"):
        user = self.by_id(uid)
        if not user:
            raise KeyError(uid)
        token = secrets.token_urlsafe(24)
        with self.lock:
            self.db.execute("UPDATE users SET token_hash=?,updated=? WHERE id=?", (token_hash(token), now(), uid))
            self.db.commit()
        self.log(by, "user.token", user["username"], "new device token")
        return token

    def clear_token(self, uid, by="system"):
        user = self.by_id(uid)
        with self.lock:
            self.db.execute("UPDATE users SET token_hash=NULL,updated=? WHERE id=?", (now(), uid))
            self.db.commit()
        self.log(by, "user.token", user["username"] if user else str(uid), "token removed")

    def delete(self, uid, by="system"):
        user = self.by_id(uid)
        if not user:
            raise KeyError(uid)
        if user["kind"] == "person" and user["role"] == "admin" and self.admin_count() <= 1:
            raise ValueError("There must be at least one admin")
        with self.lock:
            self.db.execute("DELETE FROM users WHERE id=?", (uid,))
            self.db.commit()
        self.log(by, "user.delete", user["username"], "")
        return True

    def admin_count(self):
        """Active people who are admins. Device tokens do not count - somebody has
        to be able to sign in and manage accounts."""
        return self.db.execute("SELECT COUNT(*) FROM users WHERE role='admin' AND active=1 AND kind='person'").fetchone()[0]

    # ---------- sign in ----------
    def verify(self, username, password):
        """Returns (user, error). Wrong passwords count towards a temporary lock."""
        user = self.by_username(username, with_secrets=True)
        if not user or user["kind"] == "device" or not user["pw_hash"]:
            hash_password(password or "x")          # keep the timing similar
            return None, "Wrong username or password"
        if not user["active"]:
            return None, "This account is disabled"
        if user["locked_until"] > now():
            mins = int((user["locked_until"] - now()) / 60) + 1
            return None, f"Too many attempts - try again in {mins} min"
        h, _ = hash_password(password or "", user["pw_salt"])
        if not hmac.compare_digest(h, user["pw_hash"]):
            with self.lock:
                failed = user["failed"] + 1
                locked = now() + LOCK_SECONDS if failed >= MAX_FAILED else 0
                self.db.execute("UPDATE users SET failed=?, locked_until=? WHERE id=?", (failed, locked, user["id"]))
                self.db.commit()
            self.log(username, "signin.fail", username, f"attempt {failed}")
            if locked:
                return None, "Too many attempts - locked for 15 minutes"
            return None, "Wrong username or password"
        with self.lock:
            self.db.execute("UPDATE users SET failed=0, locked_until=0, last_login=? WHERE id=?", (now(), user["id"]))
            self.db.commit()
        return self.by_id(user["id"]), None

    # ---------- audit ----------
    def log(self, username, action, target="", detail="", source="web", ip=""):
        try:
            with self.lock:
                self.db.execute("INSERT INTO audit (ts,username,action,target,detail,source,ip) VALUES (?,?,?,?,?,?,?)",
                                (now(), username or "", action, target, detail, source, ip))
                self.db.commit()
        except sqlite3.Error:
            pass

    def audit(self, limit=200, after_id=None):
        if after_id is not None:
            rows = self.db.execute("SELECT * FROM audit WHERE id>? ORDER BY id LIMIT ?", (after_id, limit))
        else:
            rows = self.db.execute("SELECT * FROM audit ORDER BY id DESC LIMIT ?", (limit,))
        return [dict(r) for r in rows.fetchall()]

    # ---------- replication (cloud -> PC copy) ----------
    def export(self):
        """Rows for the local copy: hashes only, never a plain password or token."""
        rows = self.db.execute("SELECT username,name,role,kind,pw_hash,pw_salt,token_hash,active,must_change,"
                               "sess_ver,created,updated FROM users").fetchall()
        return [dict(r) for r in rows]

    def replace_all(self, rows):
        with self.lock:
            self.db.execute("DELETE FROM users")
            for r in rows:
                self.db.execute("INSERT OR REPLACE INTO users (username,name,role,kind,pw_hash,pw_salt,token_hash,"
                                "active,must_change,sess_ver,created,updated) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                                (r["username"], r.get("name"), r.get("role", "viewer"), r.get("kind", "person"),
                                 r.get("pw_hash"), r.get("pw_salt"), r.get("token_hash"), r.get("active", 1),
                                 r.get("must_change", 0), r.get("sess_ver", 1), r.get("created"), r.get("updated")))
            self.db.commit()
        return len(rows)

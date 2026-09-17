"""SQLite-backed inventory store shared by the web API, the Slack bot and the sync engine.

In *replica* mode (the local PC app synced with the cloud) every local change is also
written to an outbox, which the sync engine sends to the cloud.
"""
import csv, io, json, sqlite3, threading, time, uuid
from pathlib import Path
from urllib.parse import unquote

FIELDS = ["pn", "name", "category", "manufacturer", "model", "mpn", "positions", "installed",
          "unit", "price", "qty_ordered", "qty_on_slip", "on_hand", "min_qty", "packing_slip",
          "order_no", "location", "notes"]
JSON_FIELDS = ["specs", "links"]
EDITABLE = set(FIELDS + JSON_FIELDS) - {"pn", "on_hand"}

SCHEMA = """
CREATE TABLE IF NOT EXISTS parts (
  pn TEXT PRIMARY KEY, name TEXT, category TEXT, manufacturer TEXT, model TEXT, mpn TEXT,
  positions TEXT, installed REAL, unit TEXT, price REAL, qty_ordered REAL, qty_on_slip REAL,
  on_hand REAL DEFAULT 0, min_qty REAL DEFAULT 0, packing_slip TEXT, order_no TEXT,
  location TEXT, notes TEXT, specs TEXT DEFAULT '[]', links TEXT DEFAULT '[]',
  updated REAL
);
CREATE TABLE IF NOT EXISTS movements (
  id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL, pn TEXT, delta REAL, after REAL,
  reason TEXT, user TEXT, source TEXT
);
CREATE INDEX IF NOT EXISTS mv_pn ON movements(pn, ts);
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
CREATE TABLE IF NOT EXISTS outbox (
  id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL, kind TEXT, pn TEXT, body TEXT
);
"""
MIGRATIONS = [
    "ALTER TABLE parts ADD COLUMN field_ts TEXT DEFAULT '{}'",
    "ALTER TABLE movements ADD COLUMN client_id TEXT",
    "ALTER TABLE movements ADD COLUMN remote_id INTEGER",
    "CREATE UNIQUE INDEX IF NOT EXISTS mv_client ON movements(client_id)",
    "CREATE UNIQUE INDEX IF NOT EXISTS mv_remote ON movements(remote_id)",
]

def _norm(s):
    return "".join(ch for ch in (s or "").upper() if ch.isalnum())

class Store:
    def __init__(self, path, replica=False):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.replica = replica
        self.db = sqlite3.connect(self.path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript(SCHEMA)
        for m in MIGRATIONS:
            try: self.db.execute(m)
            except sqlite3.OperationalError: pass
        self.db.commit()
        self.version = int(self._meta("version") or 0)
        self.listeners = []          # callables(event: dict)
        self.on_outbox = None        # called after a change is queued for the cloud
        self._cache = None

    # ---------- helpers ----------
    def _meta(self, k, v=None):
        if v is None:
            r = self.db.execute("SELECT v FROM meta WHERE k=?", (k,)).fetchone()
            return r[0] if r else None
        self.db.execute("INSERT OR REPLACE INTO meta VALUES (?,?)", (k, str(v)))

    def meta(self, k, v=None):
        with self.lock:
            r = self._meta(k, v)
            if v is not None: self.db.commit()
            return r

    def _bump(self, event):
        self.version += 1
        self._meta("version", self.version)
        self.db.commit()
        self._cache = None
        event["version"] = self.version
        for fn in list(self.listeners):
            try: fn(event)
            except Exception as e: print("listener error:", e)
        if event.get("queued") and self.on_outbox:
            try: self.on_outbox()
            except Exception as e: print("outbox hook error:", e)

    def _queue(self, kind, pn, body):
        if self.replica:
            self.db.execute("INSERT INTO outbox (ts,kind,pn,body) VALUES (?,?,?,?)",
                            (time.time(), kind, pn, json.dumps(body, ensure_ascii=False)))
            return True
        return False

    @staticmethod
    def _row(r):
        d = dict(r)
        for f in JSON_FIELDS:
            d[f] = json.loads(d.get(f) or "[]")
        d.pop("updated", None)
        d.pop("field_ts", None)
        return d

    # ---------- reads ----------
    def count(self):
        return self.db.execute("SELECT COUNT(*) FROM parts").fetchone()[0]

    def all(self):
        with self.lock:
            if self._cache is None:
                rows = self.db.execute("SELECT * FROM parts ORDER BY category, pn").fetchall()
                self._cache = [self._row(r) for r in rows]
            return self._cache

    def get(self, pn):
        with self.lock:
            r = self.db.execute("SELECT * FROM parts WHERE pn=? COLLATE NOCASE", (pn,)).fetchone()
            return self._row(r) if r else None

    def search(self, q, limit=25):
        """Ranked search over part number, model, MPN, name, manufacturer, location, specs."""
        q = (q or "").strip()
        if not q:
            return []
        terms = [t for t in q.lower().split() if t]
        nq = _norm(q)
        scored = []
        for p in self.all():
            npn, nmodel, nmpn = _norm(p["pn"]), _norm(p["model"]), _norm(p["mpn"])
            score = 0
            if nq and nq in (npn, nmodel, nmpn): score += 1000
            elif nq and len(nq) >= 4 and (npn.startswith(nq) or nmodel.startswith(nq) or nmpn.startswith(nq)): score += 400
            elif nq and len(nq) >= 4 and (nq in npn or nq in nmodel or nq in nmpn): score += 250
            hay = " ".join(str(x or "") for x in [p["pn"], p["name"], p["manufacturer"], p["model"], p["mpn"],
                            p["location"], p["category"], p["positions"], p["notes"],
                            " ".join(f"{k} {v}" for k, v in p["specs"])]).lower()
            hits = sum(1 for t in terms if t in hay)
            if hits == len(terms): score += 50 + 10 * hits
            elif not score: continue
            if score: scored.append((score, p))
        scored.sort(key=lambda x: (-x[0], x[1]["pn"]))
        return [p for _, p in scored[:limit]]

    def lookup(self, text, limit=10):
        """Match free text from a QR code, barcode or OCR'd label to parts."""
        text = (text or "").strip()
        if not text:
            return []
        for marker in ("/p/", "SP:"):
            if marker in text:
                cand = text.split(marker, 1)[1].split("?")[0].split("#")[0].strip("/ ")
                p = self.get(unquote(cand))
                if p: return [p]
        exact = self.get(text)
        if exact:
            return [exact]
        blob = _norm(text)
        found = []
        for p in self.all():
            best = 0
            for key, w in ((p["pn"], 3), (p["model"], 2), (p["mpn"], 2)):
                k = _norm(key)
                if len(k) >= 5 and k in blob:
                    best = max(best, w * len(k))
            if best:
                found.append((best, p))
        found.sort(key=lambda x: -x[0])
        if found:
            return [p for _, p in found[:limit]]
        toks = sorted({t for t in text.replace("\n", " ").split() if len(_norm(t)) >= 5 and any(c.isdigit() for c in t)},
                      key=len, reverse=True)
        out, seen = [], set()
        for t in toks[:8]:
            for p in self.search(t, 5):
                if p["pn"] not in seen:
                    seen.add(p["pn"]); out.append(p)
        return out[:limit]

    def low_stock(self):
        return [p for p in self.all() if (p["min_qty"] or 0) > 0 and (p["on_hand"] or 0) < p["min_qty"]]

    def stats(self):
        parts = self.all()
        return {
            "parts": len(parts),
            "units_on_hand": sum(p["on_hand"] or 0 for p in parts),
            "stock_value": round(sum((p["on_hand"] or 0) * (p["price"] or 0) for p in parts), 2),
            "ordered_value": round(sum((p["qty_ordered"] or 0) * (p["price"] or 0) for p in parts), 2),
            "low_stock": len(self.low_stock()),
            "no_location": sum(1 for p in parts if not p["location"]),
            "categories": sorted({p["category"] for p in parts}),
            "version": self.version,
        }

    def movements(self, pn=None, limit=100, after_id=None):
        with self.lock:
            if after_id is not None:
                rows = self.db.execute("SELECT * FROM movements WHERE id>? ORDER BY id LIMIT ?", (after_id, limit))
            elif pn:
                rows = self.db.execute("SELECT * FROM movements WHERE pn=? ORDER BY ts DESC, id DESC LIMIT ?", (pn, limit))
            else:
                rows = self.db.execute("SELECT m.*, p.name FROM movements m LEFT JOIN parts p ON p.pn=m.pn "
                                       "ORDER BY m.ts DESC, m.id DESC LIMIT ?", (limit,))
            return [dict(r) for r in rows.fetchall()]

    # ---------- writes ----------
    def upsert(self, part, keep_stock=True, bump=True, field_ts=None):
        with self.lock:
            existing = self.get(part["pn"])
            row = {f: part.get(f) for f in FIELDS}
            for f in JSON_FIELDS:
                row[f] = json.dumps(part.get(f) or [], ensure_ascii=False)
            if existing and keep_stock:
                row["on_hand"] = existing["on_hand"]
                for f in ("location", "min_qty"):
                    if existing.get(f) not in (None, ""): row[f] = existing[f]
                if existing["notes"] and existing["notes"] != (part.get("notes") or ""):
                    row["notes"] = existing["notes"]
                custom = [l for l in existing["links"] if l.get("custom")]
                row["links"] = json.dumps(custom + [l for l in (part.get("links") or []) if not l.get("custom")],
                                          ensure_ascii=False)
            row["on_hand"] = row["on_hand"] or 0
            row["updated"] = time.time()
            if field_ts is None:
                r = self.db.execute("SELECT field_ts FROM parts WHERE pn=?", (part["pn"],)).fetchone()
                field_ts = json.loads(r[0] or "{}") if r else {}
            row["field_ts"] = json.dumps(field_ts)
            cols = list(row)
            self.db.execute(f"INSERT OR REPLACE INTO parts ({','.join(cols)}) VALUES ({','.join('?'*len(cols))})",
                            [row[c] for c in cols])
            if bump:
                self._bump({"type": "part", "pn": part["pn"]})

    def import_parts(self, parts, keep_stock=True):
        with self.lock:
            for p in parts:
                self.upsert(p, keep_stock=keep_stock, bump=False)
            self._bump({"type": "import", "count": len(parts)})
            return len(parts)

    def update(self, pn, changes, ts=None):
        """Edit fields. With ts (sync), a field is only changed if nobody edited it later (last write wins)."""
        with self.lock:
            p = self.get(pn)
            if not p:
                return None
            r = self.db.execute("SELECT field_ts FROM parts WHERE pn=?", (p["pn"],)).fetchone()
            fts = json.loads(r[0] or "{}")
            ts = float(ts) if ts else time.time()
            applied = {}
            for k, v in changes.items():
                if k in EDITABLE and fts.get(k, 0) <= ts:
                    p[k] = v; fts[k] = ts; applied[k] = v
            if not applied:
                return p
            self.upsert(p, keep_stock=False, bump=False, field_ts=fts)
            queued = self._queue("patch", p["pn"], {"changes": applied, "ts": ts})
            self._bump({"type": "part", "pn": p["pn"], "queued": queued})
            return self.get(pn)

    def create(self, part):
        with self.lock:
            if not part.get("pn") or self.get(part["pn"]):
                raise ValueError("Part number missing or already exists")
            base = {f: "" for f in FIELDS}
            base.update({"on_hand": 0, "min_qty": 0, "specs": [], "links": []})
            base.update({k: v for k, v in part.items() if k in FIELDS or k in JSON_FIELDS})
            base["on_hand"] = 0
            self.upsert(base, keep_stock=False, bump=False, field_ts={})
            queued = self._queue("create", base["pn"], base)
            self._bump({"type": "part", "pn": base["pn"], "queued": queued})
            return self.get(part["pn"])

    def delete(self, pn):
        with self.lock:
            p = self.get(pn)
            if not p: return False
            self.db.execute("DELETE FROM parts WHERE pn=?", (p["pn"],))
            queued = self._queue("delete", p["pn"], {})
            self._bump({"type": "delete", "pn": p["pn"], "queued": queued})
            return True

    def adjust(self, pn, delta=None, set_to=None, reason="", user="", source="web", client_id=None, ts=None):
        with self.lock:
            p = self.get(pn)
            if not p:
                raise KeyError(pn)
            if client_id and self.db.execute("SELECT 1 FROM movements WHERE client_id=?", (client_id,)).fetchone():
                return p   # already applied (retried sync)
            before = p["on_hand"] or 0
            after = float(set_to) if set_to is not None else before + float(delta or 0)
            if after < 0:
                raise ValueError(f"Only {before:g} in stock")
            d = after - before
            client_id = client_id or (uuid.uuid4().hex if self.replica else None)
            ts = float(ts) if ts else time.time()
            self.db.execute("UPDATE parts SET on_hand=?, updated=? WHERE pn=?", (after, time.time(), p["pn"]))
            self.db.execute("INSERT INTO movements (ts,pn,delta,after,reason,user,source,client_id) VALUES (?,?,?,?,?,?,?,?)",
                            (ts, p["pn"], d, after, reason, user, source, client_id))
            body = {"client_id": client_id, "pn": p["pn"], "reason": reason, "user": user, "source": source, "ts": ts}
            body.update({"set": after} if set_to is not None else {"delta": d})
            queued = self._queue("adjust", p["pn"], body)
            p["on_hand"] = after
            low = (p["min_qty"] or 0) > 0 and after < p["min_qty"] and before >= p["min_qty"]
            self._bump({"type": "stock", "pn": p["pn"], "delta": d, "after": after, "user": user,
                        "source": source, "reason": reason, "became_low": low, "part": p, "queued": queued})
            return p

    # ---------- sync (replica side) ----------
    def outbox(self, limit=200):
        with self.lock:
            rows = self.db.execute("SELECT * FROM outbox ORDER BY id LIMIT ?", (limit,)).fetchall()
            return [{**dict(r), "body": json.loads(r["body"])} for r in rows]

    def outbox_count(self):
        return self.db.execute("SELECT COUNT(*) FROM outbox").fetchone()[0]

    def outbox_done(self, ids):
        with self.lock:
            self.db.executemany("DELETE FROM outbox WHERE id=?", [(i,) for i in ids])
            self.db.commit()

    def replace_from_remote(self, parts):
        """Make the local parts table an exact copy of the cloud (only called with an empty outbox)."""
        with self.lock:
            if self.outbox_count():
                return False
            self.db.execute("DELETE FROM parts")
            for p in parts:
                self.upsert(p, keep_stock=False, bump=False, field_ts={})
            self._bump({"type": "sync", "count": len(parts)})
            return True

    def merge_remote_movements(self, rows):
        with self.lock:
            n = 0
            for m in rows:
                if m.get("client_id") and self.db.execute("UPDATE movements SET remote_id=? WHERE client_id=? AND remote_id IS NULL",
                                                          (m["id"], m["client_id"])).rowcount:
                    continue
                if self.db.execute("SELECT 1 FROM movements WHERE remote_id=?", (m["id"],)).fetchone():
                    continue
                if m.get("client_id") and self.db.execute("SELECT 1 FROM movements WHERE client_id=?", (m["client_id"],)).fetchone():
                    continue
                self.db.execute("INSERT INTO movements (ts,pn,delta,after,reason,user,source,client_id,remote_id) VALUES (?,?,?,?,?,?,?,?,?)",
                                (m["ts"], m["pn"], m["delta"], m["after"], m.get("reason"), m.get("user"), m.get("source"),
                                 m.get("client_id"), m["id"]))
                n += 1
            self.db.commit()
            return n

    def seed_movements(self, rows):
        """Cloud side: accept the local history when seeding an empty cloud."""
        with self.lock:
            for m in rows:
                try:
                    self.db.execute("INSERT INTO movements (ts,pn,delta,after,reason,user,source,client_id) VALUES (?,?,?,?,?,?,?,?)",
                                    (m["ts"], m["pn"], m["delta"], m["after"], m.get("reason"), m.get("user"),
                                     m.get("source"), m.get("client_id") or uuid.uuid4().hex))
                except sqlite3.IntegrityError:
                    pass
            self.db.commit()

    def export_csv(self):
        buf = io.StringIO()
        w = csv.writer(buf, delimiter=";")
        w.writerow(FIELDS + ["specs"])
        for p in self.all():
            w.writerow([p.get(f) if p.get(f) is not None else "" for f in FIELDS] +
                       ["; ".join(f"{k}: {v}" for k, v in p["specs"])])
        return "﻿" + buf.getvalue()

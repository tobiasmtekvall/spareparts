"""Import the Jönköping spare-part workbook (.ods / .xlsx) into a list of part dicts.

Each part row starts with a part number; rows below it with an empty part
number are specification lines for that part ("Manufacturer Code : ...").
"""
import json, math, re, urllib.parse
from pathlib import Path

try:
    import pandas as pd
except ImportError:  # pragma: no cover
    pd = None

SKIP_SHEETS = {"Summary"}
CATEGORY = {"Mechanical": "Mechanical", "Electrical": "Electrical",
            "Curve belts": "Curve belts", "Belts": "Belts", "Gear motors": "Gear motors"}

def _num(v):
    if v is None: return None
    if isinstance(v, str):
        v = v.strip().replace(",", ".")
        try: v = float(v)
        except ValueError: return None
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return None if math.isnan(f) else f

def _str(v):
    if v is None: return ""
    if isinstance(v, float) and math.isnan(v): return ""
    s = re.sub(r"_x[0-9A-Fa-f]{4}_", "", str(v)).replace("\u203a\u00bc", "").strip()
    return "" if s in ("nan", "?", "#VALUE!") else s

def _col(df, *names):
    norm = {re.sub(r"\s+", " ", str(c)).strip().lower(): c for c in df.columns}
    for n in names:
        c = norm.get(n.lower())
        if c is not None: return c
    return None

def norm_siemens(mlfb):
    # 3RV20-11-1JA10 -> 3RV2011-1JA10 ; 3RT20-27-1BB44 -> 3RT2027-1BB44
    m = re.match(r"^(3R[A-Z]\d{2})-(\d{2})-(.+)$", mlfb)
    return f"{m.group(1)}{m.group(2)}-{m.group(3)}" if m else mlfb

MANUFACTURER_ALIASES = {
    "siemens ag": "Siemens", "sew": "SEW-EURODRIVE", "sick": "SICK", "pilz": "Pilz",
    "schneider electric": "Schneider Electric", "phoenix contact": "Phoenix Contact",
    "sigma control": "Sigmatek", "meanwell": "MEAN WELL", "hager": "Hager", "ikusi": "IKUSI",
    "forbosiegling": "Forbo Siegling", "forbo siegling": "Forbo Siegling", "forbo": "Forbo Siegling",
}
MANUFACTURER_SITES = {
    "Siemens": "siemens.com", "SEW-EURODRIVE": "sew-eurodrive.com", "SICK": "sick.com",
    "Pilz": "pilz.com", "Schneider Electric": "se.com", "Phoenix Contact": "phoenixcontact.com",
    "Sigmatek": "sigmatek-automation.com", "MEAN WELL": "meanwell.com", "Hager": "hager.com",
    "IKUSI": "ikusi.com", "Forbo Siegling": "forbo.com",
}

def doc_links(manufacturer, model):
    """Documentation links. Siemens gets direct SiePortal / SIOS links; others get
    targeted searches on the manufacturer's site (always valid, never a guessed PDF path)."""
    if not model:
        return []
    q = urllib.parse.quote_plus
    links = []
    if manufacturer == "Siemens":
        links.append({"title": "Siemens SiePortal (product page, datasheet, CAD)",
                      "url": f"https://sieportal.siemens.com/en-ww/products-services/detail/{urllib.parse.quote(model)}"})
        links.append({"title": "Siemens Industry Support – manuals",
                      "url": f"https://support.industry.siemens.com/cs/search?search={q(model)}&type=Manual"})
    site = MANUFACTURER_SITES.get(manufacturer)
    if site:
        links.append({"title": f"{manufacturer} manual (search)",
                      "url": f"https://www.google.com/search?q={q(f'site:{site} {model} manual')}"})
    links.append({"title": "Datasheet PDF (web search)",
                  "url": f"https://www.google.com/search?q={q(f'{manufacturer} {model} datasheet filetype:pdf'.strip())}"})
    return links

def _split_electrical(desc):
    parts = [p.strip() for p in desc.split(",")]
    # Descriptions look like "Name,MODEL,Manufacturer" but the name may contain commas (e.g. "0,55 kW")
    if len(parts) >= 3 and parts[-1].lower() in MANUFACTURER_ALIASES:
        manu = MANUFACTURER_ALIASES[parts[-1].lower()]
        model = parts[-2]
        name = ",".join(parts[:-2])
        return name, model, manu
    return desc, "", ""

SPEC_KV = re.compile(r"^\s*([^:]{2,60}?)\s*:\s*(.*)$")

def _spec_pairs(lines):
    out = []
    for ln in lines:
        m = SPEC_KV.match(ln)
        if m:
            k = m.group(1).strip()
            k = k[:1].upper() + k[1:].lower() if k.isupper() else k
            out.append([k, m.group(2).strip()])
        elif out:
            out[-1][1] = (out[-1][1] + " " + ln.strip()).strip()
        else:
            out.append(["Note", ln.strip()])
    return out

def _spec_value(specs, key):
    for k, v in specs:
        if k.lower().replace(" ", "") == key:
            return v
    return ""

def read_workbook(path):
    if pd is None:
        raise RuntimeError("pandas + odfpy/openpyxl are required to import a workbook")
    path = Path(path)
    engine = "odf" if path.suffix.lower() == ".ods" else None
    sheets = pd.read_excel(path, engine=engine, sheet_name=None, header=0)
    parts = []
    for sheet, df in sheets.items():
        if sheet in SKIP_SHEETS: continue
        c_pn = _col(df, "00 Part Number", "Part Number")
        c_desc = _col(df, "Description")
        if c_pn is None or c_desc is None: continue
        c_items = _col(df, "Item numbers"); c_mpn = _col(df, "MPN")
        c_total = _col(df, "Total net"); c_unit = _col(df, "Unit")
        c_price = _col(df, "Sell Price"); c_ord = _col(df, "Qty. Ordered")
        c_site = _col(df, "Qty. On site"); c_slip = _col(df, "Qty. On slip")
        c_ps = _col(df, "Packing Slip"); c_order = _col(df, "Order")
        c_comp = _col(df, "Compartment on site"); c_notes = _col(df, "Notes", "Questions")
        cur = None; orphan_specs = []
        for _, r in df.iterrows():
            pn = _str(r[c_pn]); desc = _str(r[c_desc])
            if not pn:
                if desc:
                    (cur["_spec_lines"] if cur else orphan_specs).append(desc)
                continue
            name, model, manu = (desc, "", "")
            if sheet == "Electrical":
                name, model, manu = _split_electrical(desc)
                if manu == "Siemens": model = norm_siemens(model)
            cur = {
                "pn": pn, "name": re.sub(r"\s+", " ", name).strip(), "category": CATEGORY.get(sheet, sheet),
                "manufacturer": manu, "model": model,
                "mpn": _str(r[c_mpn]) if c_mpn is not None else "",
                "positions": _str(r[c_items]) if c_items is not None else "",
                "installed": _num(r[c_total]) if (c_total is not None and sheet != "Curve belts") else None,
                "unit": _str(r[c_unit]) if c_unit is not None else "" ,
                "price": _num(r[c_price]) if c_price is not None else None,
                "qty_ordered": _num(r[c_ord]) if c_ord is not None else None,
                "qty_on_slip": _num(r[c_slip]) if c_slip is not None else None,
                "on_hand": _num(r[c_site]) if c_site is not None else None,
                "packing_slip": _str(r[c_ps]) if c_ps is not None else "",
                "order_no": _str(r[c_order]) if c_order is not None else "",
                "location": _str(r[c_comp]) if c_comp is not None else "",
                "notes": _str(r[c_notes]) if c_notes is not None else "",
                "_spec_lines": [],
            }
            parts.append(cur)
    return parts

def dedupe(parts):
    """Same part number listed twice (e.g. once with price, once with order info): merge."""
    out, seen = [], {}
    for p in parts:
        q = seen.get(p["pn"])
        if q is None:
            seen[p["pn"]] = p; out.append(p); continue
        for k, v in p.items():
            if k == "_spec_lines":
                q[k] += [l for l in v if l not in q[k]]
            elif k in ("qty_ordered", "qty_on_slip", "installed", "on_hand"):
                if v is not None and (q[k] is None or v > q[k]): q[k] = v
            elif q.get(k) in (None, "") and v not in (None, ""):
                q[k] = v
        if len(p["name"]) > len(q["name"]): q["name"] = p["name"]
    return out

def enrich(parts, research_json=None):
    parts = dedupe(parts)
    research = {}
    if research_json and Path(research_json).exists():
        for it in json.loads(Path(research_json).read_text(encoding="utf-8")):
            research[it["pn"]] = it
    by_pn = {p["pn"]: p for p in parts}

    # Belts tab: 7 spec blocks all sit under the last belt; each block ends with "Belt type" or "Manufacturer".
    belts = [p for p in parts if p["category"] == "Belts"]
    if belts and len(belts[-1]["_spec_lines"]) > 12:
        lines = belts[-1]["_spec_lines"]; blocks, blk = [], []
        for ln in lines:
            if re.match(r"(?i)^\s*manufacturer code\s*:", ln) and blk:
                blocks.append(blk); blk = []
            blk.append(ln)
        if blk: blocks.append(blk)
        targets = [p for p in belts if p["positions"]]
        if len(blocks) == len(targets):
            for p, b in zip(targets, blocks):
                p["_spec_lines"] = b
                p["notes"] = (p["notes"] + " " if p["notes"] else "") + "Belt spec assigned by row order in the Belts tab."
    # Mechanical: trailing spec rows under the last part belong to 44-80999 (belt) and A009673 (gear motor).
    for p in parts:
        if p["pn"].startswith("T-211242") and len(p["_spec_lines"]) > 10:
            lines = p["_spec_lines"]
            idx = next((i for i, l in enumerate(lines) if l.upper().startswith("MANUFACTURER CODE: KH97")), None)
            if idx:
                belt, motor = lines[:idx], lines[idx:]
                p["_spec_lines"] = []
                for pn, blk in (("44-80999", belt), ("A009673", motor)):
                    if pn in by_pn:
                        by_pn[pn]["_spec_lines"] = blk
                        by_pn[pn]["notes"] = "Spec lines were listed under T-211242-1390 in the sheet."

    for p in parts:
        specs = _spec_pairs(p.pop("_spec_lines"))
        p["specs"] = specs
        rs = research.get(p["pn"], {})
        if not p["manufacturer"]:
            m = _spec_value(specs, "manufacturer") or rs.get("manufacturer", "")
            p["manufacturer"] = MANUFACTURER_ALIASES.get(m.lower().replace(" ", ""), MANUFACTURER_ALIASES.get(m.lower(), m))
        if not p["model"]:
            p["model"] = _spec_value(specs, "manufacturercode") or rs.get("model", "")
        if p["category"] == "Gear motors" and not p["manufacturer"]:
            p["manufacturer"] = "SEW-EURODRIVE"
        p["links"] = doc_links(p["manufacturer"], p["model"] or p["mpn"])
        p["min_qty"] = p["qty_ordered"] or 0
        if p["on_hand"] is None:
            # Sheet says "16 gear motors received" – everything else is still on order.
            p["on_hand"] = p["qty_ordered"] if p["category"] == "Gear motors" else 0
        p["value"] = round((p["price"] or 0) * (p["qty_ordered"] or 0), 2)
    return parts

if __name__ == "__main__":
    import sys
    base = Path(__file__).resolve().parent.parent / "data"
    ps = enrich(read_workbook(sys.argv[1] if len(sys.argv) > 1 else base / "source.ods"), base / "research_items.json")
    print(len(ps))
    print(json.dumps([p for p in ps if p["pn"] in ("85-01441", "A009858", "A011229", "44-80999", "A011221")], indent=1, ensure_ascii=False)[:5000])

"""Slack integration (Socket Mode – no public URL needed).

Slash command (default /parts):
  /parts <search>                 find parts
  /parts <pn>                     show one part
  /parts take <pn> [qty] [reason] take from stock
  /parts add <pn> [qty] [reason]  put back / receive
  /parts count <pn> <qty>         set counted quantity
  /parts where <pn>               location only
  /parts low                      everything below minimum
  /parts help
Also: @mention the bot with a search, and buttons on each part (Take 1 / Add 1).
Low-stock alerts go to slack.alert_channel when a part drops below its minimum.

What Slack may do is set by slack.role in config.json / SLACK_ROLE (default "staff":
search and book stock). Set it to "viewer" to make Slack read-only.
"""
import re, threading

def _fmt(n):
    if n is None: return "—"
    return f"{n:g}"

def _status(p):
    oh, mn = p.get("on_hand") or 0, p.get("min_qty") or 0
    if not mn: return ":white_circle:" if not oh else ":large_green_circle:"
    return ":red_circle:" if oh <= 0 else ":large_orange_circle:" if oh < mn else ":large_green_circle:"

def part_line(p, base):
    loc = f"  ·  :round_pushpin: `{p['location']}`" if p.get("location") else ""
    return (f"{_status(p)} *<{base}/p/{p['pn']}|{p['pn']}>*  {p['name']}\n"
            f"      {_fmt(p['on_hand'])} on hand / min {_fmt(p['min_qty'])}{loc}"
            + (f"  ·  {p['manufacturer']} {p['model']}".rstrip() if p.get("manufacturer") else ""))

def part_blocks(p, base):
    fields = [
        f"*On hand*\n{_status(p)} {_fmt(p['on_hand'])} {p.get('unit') or ''} (min {_fmt(p['min_qty'])})",
        f"*Location*\n{('`' + p['location'] + '`') if p.get('location') else '_not set_'}",
        f"*Manufacturer / model*\n{p.get('manufacturer') or '—'} {p.get('model') or p.get('mpn') or ''}",
        f"*Unit price*\n{('€ %.2f' % p['price']) if p.get('price') is not None else '—'}",
        f"*Ordered*\n{_fmt(p.get('qty_ordered'))} · {p.get('order_no') or '—'}",
        f"*Category*\n{p['category']}",
    ]
    blocks = [
        {"type": "header", "text": {"type": "plain_text", "text": f"{p['pn']} – {p['name']}"[:150]}},
        {"type": "section", "fields": [{"type": "mrkdwn", "text": f} for f in fields]},
    ]
    if p.get("specs"):
        spec = "\n".join(f"• {k}: {v}" for k, v in p["specs"][:10])
        blocks.append({"type": "section", "text": {"type": "mrkdwn", "text": spec[:2900]}})
    if p.get("positions"):
        blocks.append({"type": "context", "elements": [{"type": "mrkdwn", "text": "Positions: " + p["positions"][:2800]}]})
    links = [f"<{l['url']}|{l['title']}>" for l in (p.get("links") or [])[:4]]
    if links:
        blocks.append({"type": "section", "text": {"type": "mrkdwn", "text": ":page_facing_up: " + "  ·  ".join(links)}})
    blocks.append({"type": "actions", "elements": [
        {"type": "button", "text": {"type": "plain_text", "text": "Take 1"}, "action_id": "sp_take", "value": p["pn"], "style": "danger"},
        {"type": "button", "text": {"type": "plain_text", "text": "Add 1"}, "action_id": "sp_add", "value": p["pn"]},
        {"type": "button", "text": {"type": "plain_text", "text": "Open"}, "url": f"{base}/p/{p['pn']}", "action_id": "sp_open"},
    ]})
    return blocks

def list_blocks(title, parts, base, more=0):
    text = "\n".join(part_line(p, base) for p in parts) or "_Nothing found._"
    blocks = [{"type": "section", "text": {"type": "mrkdwn", "text": f"*{title}*\n{text}"[:2990]}}]
    if more:
        blocks.append({"type": "context", "elements": [{"type": "mrkdwn", "text": f"+{more} more – refine your search or <{base}|open the inventory>"}]})
    return blocks

HELP = ("*Spare parts* – `{c} <search>` find parts · `{c} <part no>` details · "
        "`{c} take <pn> [qty] [reason]` · `{c} add <pn> [qty] [reason]` · `{c} count <pn> <qty>` · "
        "`{c} where <pn>` · `{c} low`")

def handle(store, text, user, base, command="/parts", perms=None, name=None):
    """Return (response_text, blocks, public: bool). Pure function - easy to test.

    `user` is the Slack user id (used to @mention them); `name` is their display
    name, which is what gets written into the stock history and the audit log."""
    perms = perms if perms is not None else {"view", "adjust"}
    who = name or user
    text = (text or "").strip()
    if not text or text.lower() in ("help", "?"):
        return HELP.format(c=command), None, False
    m = re.match(r"^(take|use|out|add|return|in|receive|count|set)\s+(\S+)(?:\s+(-?\d+(?:[.,]\d+)?))?(?:\s+(.*))?$", text, re.I)
    if m and "adjust" not in perms:
        return ":lock: Booking stock from Slack is turned off. Use the web app or the phone app.", None, False
    if m:
        verb, pn, qty, reason = m.group(1).lower(), m.group(2), m.group(3), m.group(4) or ""
        p = store.get(pn) or next(iter(store.lookup(pn)), None)
        if not p:
            return f"No part matches `{pn}`.", None, False
        q = float(qty.replace(",", ".")) if qty else 1.0
        try:
            if verb in ("count", "set"):
                if qty is None: return "Usage: `count <pn> <qty>`", None, False
                p = store.adjust(p["pn"], set_to=q, reason=reason or "Stock count", user=who, source="slack")
                verb_txt = f"counted *{_fmt(q)}*"
            elif verb in ("take", "use", "out"):
                p = store.adjust(p["pn"], delta=-q, reason=reason, user=who, source="slack")
                verb_txt = f"took *{_fmt(q)}*"
            else:
                p = store.adjust(p["pn"], delta=q, reason=reason, user=who, source="slack")
                verb_txt = f"added *{_fmt(q)}*"
        except ValueError as e:
            return f":warning: {e}", None, False
        msg = f"<@{user}> {verb_txt} × {p['pn']} {p['name']} → {_fmt(p['on_hand'])} left" + (f" ({reason})" if reason else "")
        return msg, list_blocks(msg, [p], base), True
    m = re.match(r"^(where|loc|location)\s+(.+)$", text, re.I)
    if m:
        hits = store.lookup(m.group(2)) or store.search(m.group(2), 5)
        if not hits: return f"No part matches `{m.group(2)}`.", None, False
        return "\n".join(f"*{p['pn']}* {p['name']}: " + (f"`{p['location']}`" if p['location'] else "_no location set_") for p in hits[:5]), None, False
    if text.lower() in ("low", "lowstock", "low stock", "reorder"):
        low = sorted(store.low_stock(), key=lambda p: p["pn"])
        return f"{len(low)} parts below minimum", list_blocks(f"{len(low)} parts below minimum", low[:25], base, max(0, len(low) - 25)), False
    exact = store.get(text)
    if exact:
        return f"{exact['pn']} {exact['name']}", part_blocks(exact, base), False
    hits = store.search(text, 50)
    if len(hits) == 1:
        return f"{hits[0]['pn']} {hits[0]['name']}", part_blocks(hits[0], base), False
    return f"{len(hits)} matches for {text}", list_blocks(f"{len(hits)} matches for “{text}”", hits[:15], base, max(0, len(hits) - 15)), False

def start(store, cfg, public_url, perms=None, log_action=None):
    from slack_bolt import App
    from slack_bolt.adapter.socket_mode import SocketModeHandler
    sc = cfg["slack"]
    command = sc.get("command") or "/parts"
    app = App(token=sc["bot_token"], token_verification_enabled=False)
    names = {}

    def display_name(uid):
        """Slack ids are meaningless in a stock history - look the person up once."""
        if uid not in names:
            try:
                info = app.client.users_info(user=uid)["user"]
                names[uid] = "slack:" + (info.get("profile", {}).get("display_name")
                                         or info.get("real_name") or uid)
            except Exception:
                names[uid] = "slack:" + uid
        return names[uid]

    def record(uid, action, target, detail):
        if log_action:
            try: log_action(display_name(uid), action, target, detail)
            except Exception as e: print("slack audit error:", e)

    @app.command(command)
    def on_command(ack, command: dict, respond):
        txt, blocks, public = handle(store, command.get("text"), command["user_id"], public_url(),
                                     command["command"], perms, display_name(command["user_id"]))
        ack()
        if public:                      # a stock change was made
            record(command["user_id"], "stock", "", re.sub(r"[*<>@]", "", txt)[:300])
        respond(text=txt, blocks=blocks, response_type="in_channel" if public else "ephemeral")

    @app.event("app_mention")
    def on_mention(event, say):
        q = re.sub(r"<@[^>]+>", "", event.get("text", "")).strip()
        txt, blocks, _ = handle(store, q, event["user"], public_url(), command, perms, display_name(event["user"]))
        say(text=txt, blocks=blocks, thread_ts=event.get("thread_ts") or event["ts"])

    @app.event("message")
    def on_dm(event, say):
        if event.get("channel_type") == "im" and not event.get("bot_id") and not event.get("subtype"):
            txt, blocks, _ = handle(store, event.get("text", ""), event["user"], public_url(), "", perms,
                                    display_name(event["user"]))
            say(text=txt, blocks=blocks)

    def _btn(delta):
        def fn(ack, body, respond):
            ack()
            if "adjust" not in (perms or set()):
                return respond(text=":lock: Booking stock from Slack is turned off.", replace_original=False)
            pn = body["actions"][0]["value"]; user = body["user"]["id"]
            try:
                p = store.adjust(pn, delta=delta, reason="Slack button", user=display_name(user), source="slack")
                record(user, "stock", pn, f"change {delta:+g} -> {_fmt(p['on_hand'])} (Slack button)")
                respond(text=f"<@{user}> {'took' if delta < 0 else 'added'} 1 × {pn} → {_fmt(p['on_hand'])} left",
                        response_type="in_channel", replace_original=False)
            except ValueError as e:
                respond(text=f":warning: {e}", replace_original=False)
        return fn
    app.action("sp_take")(_btn(-1))
    app.action("sp_add")(_btn(1))
    app.action("sp_open")(lambda ack: ack())

    channel = sc.get("alert_channel")
    if channel:
        def on_event(ev):
            if ev.get("type") == "stock" and ev.get("became_low"):
                p = ev["part"]
                txt = f":warning: *{p['pn']}* {p['name']} is below minimum: {_fmt(p['on_hand'])} left (min {_fmt(p['min_qty'])})"
                threading.Thread(target=lambda: app.client.chat_postMessage(channel=channel, text=txt,
                                 blocks=part_blocks(p, public_url())), daemon=True).start()
        store.listeners.append(on_event)

    handler = SocketModeHandler(app, sc["app_token"])
    threading.Thread(target=handler.start, daemon=True, name="slack").start()
    print(f"Slack bot connected (command {command}{', alerts → ' + channel if channel else ''})")

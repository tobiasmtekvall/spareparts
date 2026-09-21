# Spare Parts Inventory – Jönköping

Everything shares one inventory:

| Part | What it is |
|---|---|
| **Cloud app** (Railway) | The **primary copy**, live at **https://spareparts-production-7cb1.up.railway.app** — web app, API, Slack bot and manuals, reachable anywhere. Protected by a shared password. |
| **Local app** (site PC, `start.bat`) | A full copy that **syncs with the cloud**. It keeps working without internet and catches up when the connection is back. |
| **Android app** (`android/`) | Scans QR codes, barcodes and label text, and books stock. It connects to the cloud address. |
| **Slack bot** | `/parts …` in Slack. It runs in the cloud. |

---

## 1. Deploy to Railway (once)

1. Open https://railway.com → **New Project → Deploy from GitHub repo** and pick `tobiasmtekvall/spareparts`.
   - If you're asked, give the Railway GitHub app access to that repo.
   - Railway builds from the `Dockerfile` automatically.
2. **Add a volume.** In the project canvas, right-click the service → **Attach volume**. Set the mount path to **`/data`**.
   - The database and uploaded manuals live on the volume, so they survive redeploys.
3. **Variables.** Open the service → **Variables** and add:

   | Variable | Value |
   |---|---|
   | `APP_PASSWORD` | password for the first `admin` account (you change it at first sign-in) |
   | `SLACK_BOT_TOKEN` | `xoxb-…` (optional; see step 4 below) |
   | `SLACK_APP_TOKEN` | `xapp-…` (optional) |
   | `SLACK_ALERT_CHANNEL` | channel ID for low-stock alerts, e.g. `C0123ABCD` (optional) |

4. **Public address.** Go to **Settings → Networking → Generate Domain**. Railway asks for the port: enter **8080**, or whatever port the deploy log shows next to `Local: http://localhost:`.
   - You get an address like `https://spareparts-production.up.railway.app`.
   - The app detects this address itself and uses it for QR labels.
5. Wait for the deploy to turn green. Opening the address shows the sign-in page.
   - The cloud starts empty. The first time the local app connects, it uploads the inventory, history and manuals.

**Redeploying after a code change.** This service was created from the public repo URL, so Railway has no webhook on it and does **not** redeploy by itself. Either press **Deploy** (⋮ menu → Redeploy) on the service in Railway, or connect the repo properly: sign in to GitHub as `tobiasmtekvall`, open https://github.com/settings/installations → Railway App → Configure, add the `spareparts` repository, then in Railway open the service → Settings → Source and pick the repo. After that every push to `main` deploys automatically. Your data stays on the volume either way.

## 2. Local app (site PC)

1. Install **Python 3.10 or newer** from python.org, with "Add python.exe to PATH" ticked.
2. Copy `config.example.json` to **`config.json`** (it is never committed) and fill in `sync.remote_url` and `sync.key`.
3. Double-click **`start.bat`**. On start it:
   1. sends any changes made while offline to the cloud,
   2. downloads the latest data,
   3. syncs manuals both ways between `Spare Parts Research` and the cloud,
   4. follows the cloud live while it runs.
4. The bottom-left corner shows **Synced with cloud**, how many changes are waiting to upload, or **Offline**. Click it to sync now.

**How conflicts are settled**
- **Stock changes** are sent as "+2 / −1", so bookings made on the PC, on phones and in Slack all count.
- **Location, notes, minimum and other fields:** the most recent edit wins.
- **"Set count"** (stocktaking) sets the exact number.

Without `sync.remote_url`, the local app runs standalone as before.

## 3. Accounts and permissions

Everyone has their own account, so every booking and edit is recorded against a person.

| Role | May do |
|---|---|
| **Admin** | Everything, and manages accounts |
| **Manager** | Edit any field on a part, create and delete parts, import a workbook, plus everything staff may do |
| **Staff** | Book stock in and out, set location, notes and minimum, upload manuals |
| **Read-only** | Look, but change nothing |

- **First sign-in after deployment:** the app creates the account `admin` using `APP_PASSWORD` (or `ADMIN_USER` / `ADMIN_PASSWORD` if you set those). It asks for a new password straight away.
- **Adding people:** Accounts → New account. You give them a temporary password, and the app makes them choose their own at first sign-in.
- **If someone leaves:** Disable the account. That signs them out everywhere at once, and their history stays intact. Resetting a password does the same.
- **Wrong passwords** lock an account for 15 minutes after five tries.
- **Phones and the site PC** use a device token instead of a password (Accounts → Device token). Bookings made from a phone are recorded under the person's name when the app sends it, and the token's role limits what that device may do.
- **Audit log** (admins) lists every sign-in, booking and edit with the old and new value, who did it, and from which address. It cannot be edited from the app.
- **Slack** acts with the role in `slack.role` / `SLACK_ROLE` (default `staff`). Set it to `viewer` to make Slack read-only.
- **Your own password:** Settings → Your account.

## 4. Using the app

- **Sign in** with your username and password.
- **Keyboard:**
  - **/** search, then **↑ ↓** and **Enter** to open a part
  - **+ / −** change stock on the open part, **Esc** closes it
  - **1–5** switch views, **N** new part, **T** theme
- **Search** matches part numbers, model codes (`3RV2011`), MPNs, names, locations, machine positions and specs. Several words must all match (`breaker 32A`).
- **On a part:**
  - Take, Return, Receive order, Set count, Min
  - location and notes
  - manual links, and **Upload manual / photo** (the file goes to the part's folder, in the cloud and on the PC)
  - history and QR label
- **Low stock** lists everything under its minimum, with the cost to restock.
- **QR labels:** choose parts, pick a size (48×25, 63×38 or 99×57 mm) and print. The QR code opens the part via the cloud address.
- **Settings:** your name, sign out, the phone connection details, workbook re-import (runs in the cloud; stock, locations, minimums, notes and your own links are kept) and CSV/JSON export.

**About the imported data**
- **Minimum stock** starts at the quantity ordered.
- **On hand** starts at 0, except the 16 gear motors, which the sheet notes as received.
- **Electrical descriptions** are split into name, model and manufacturer. Siemens order numbers are normalised.
- **Misplaced spec lines** were reassigned: the belt specs by row order, and the trailing Mechanical specs to 44-80999 and A009673.
- **Duplicate part numbers** were merged.

## 5. Slack

1. Go to https://api.slack.com/apps → **Create New App → From an app manifest**, and paste **`slack-app-manifest.yml`**.
2. **Basic Information → App-Level Tokens** → generate a token with the scope `connections:write`. That's `SLACK_APP_TOKEN`.
3. **Install App.** The Bot User OAuth Token is `SLACK_BOT_TOKEN`.
4. Add both as Railway variables, plus `SLACK_ALERT_CHANNEL`, and invite the bot to that channel with `/invite @Spare Parts`.
   - The deploy log should show `Slack bot connected`.
   - The local app never runs the bot, because two bots would split the commands between them.

```
/parts 3RV2011                  search
/parts 85-01441                 part card with Take 1 / Add 1 buttons
/parts take 85-01441 2 Z040.151 take 2 with a reason
/parts add A009858 1            add / return / receive
/parts count 43-29876 4         stocktaking
/parts where 43-29876           location
/parts low                      below minimum
```
You can also **@Spare Parts** in a channel or message the bot directly.

## 6. Android app

- Install `SpareParts-arm64.apk`. To build it yourself, see `android/README.md`.
- In the app's **Settings**, fill in:
  - **Server URL:** the Railway address
  - **API key:** a device token from Accounts → Device token
  - **Your name**
- **Scan** QR labels, manufacturer barcodes or DataMatrix codes, or tap **Read label** to read the text on a label.
- Search and scanning still work offline, and stock changes are uploaded later.

## API

Send a device token as the `X-Api-Key` header, or use the browser sign-in cookie. A device token may add `X-Actor` (or `user` in an adjustment) to name the person behind a change. Every endpoint checks the account's role.

| | |
|---|---|
| `GET /api/parts[?since=v]` | all parts |
| `GET /api/parts/{pn}` | one part, plus its files and QR URL |
| `GET /api/search?q=`, `GET /api/lookup?q=` | search / match QR, barcode or OCR text |
| `POST /api/parts/{pn}/adjust` | `{"delta": -1}` or `{"set": 5}`, plus `reason`, `user`, optional `client_id` (makes retries safe) |
| `POST /api/adjust` | batch of adjustments |
| `PATCH /api/parts/{pn}` | edit fields (optional `_ts` gives last-write-wins) |
| `POST /api/parts`, `DELETE /api/parts/{pn}` | create / delete |
| `POST /api/parts/{pn}/files` | upload a manual (header `X-Filename`) |
| `GET /api/files`, `GET /files/<path>`, `DELETE /api/files?path=` | list / download / remove manuals |
| `GET /api/movements[?after_id=]`, `/api/low`, `/api/stats` | reports |
| `GET /api/export.csv`, `/api/export.json`, `POST /api/import` | export / import |
| `GET /api/events` | live event stream |
| `GET /api/sync/status`, `POST /api/sync/now` | local app sync state |
| `GET /api/me`, `POST /api/me` | who am I / change my own password |
| `GET/POST /api/users`, `PATCH/DELETE /api/users/{id}` | accounts (admin) |
| `POST /api/users/{id}/password`, `POST /api/users/{id}/token` | reset a password / issue a device token |
| `GET /api/audit` | audit log (admin) |

**Backups:** the cloud database is `/data/inventory.db` on the Railway volume. Use `GET /api/export.json` for a quick copy.

# Todo

A personal todo app with one core idea that off-the-shelf apps don't do well:
**recurrence anchored to when you actually finish a task, not its original due
date.** Get a haircut every 3 weeks, mark it done 5 days late, and the next
one is scheduled 3 weeks after the day you *did* it — not 3 weeks after the
day it was originally due. You can also backdate/edit when you actually
completed something after the fact, and the next occurrence reflows with it.

Not a habit tracker — no streaks, no gamification. Just todos, done properly.

## Features

- Recurring todos anchored to completion date (days/weeks/months), editable after the fact
- Due date + time, with due/overdue push reminders
- Priority, category, notes, optional subtasks per todo
- Search, filter (active/overdue/today/upcoming/completed/all), sort
- Soft-delete with a trash panel (restore or purge)
- Full JSON export/import (backup & restore)
- Light/dark theme
- Installable PWA — works as a home-screen app on Android and desktop, offline-capable UI shell

## Architecture

- **`server/`** — Node.js + Express + SQLite (`better-sqlite3`). Single process, no external DB to run.
- **`client/`** — Plain HTML/CSS/JS PWA (manifest + service worker), no build step, no framework.
- The Express server serves both the API (`/api/*`) and the static client, so it's one process to run.
- Reminders: a `node-cron` job checks due/overdue todos every minute and sends Web Push notifications via VAPID.

Data lives in one SQLite file (`server/data/todo.db`). No user accounts — this is meant to run on hardware only you can reach.

## Local development

```bash
cd todo-app/server
npm install
cp .env.example .env
npm run generate-vapid          # prints two keys — paste them into .env
npm start                       # http://localhost:4000
```

Open `http://localhost:4000` in a browser. Push notifications need HTTPS in
production (see below) but `localhost` is exempt, so they also work in this
local setup once VAPID keys are set.

## Deploying on your headless Fedora box (the Dell)

### 1. Runtime

```bash
sudo dnf install nodejs sqlite
```

`better-sqlite3` ships prebuilt binaries for common platforms; if `npm install`
falls back to compiling it, install build tools first:

```bash
sudo dnf groupinstall "Development Tools"
sudo dnf install python3
```

### 2. Get the code and install

```bash
git clone <this-repo-url> ~/todo-app-src   # or copy the todo-app/ folder over
cd ~/todo-app-src/todo-app/server
npm install --omit=dev
cp .env.example .env
npm run generate-vapid
```

Paste the printed `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` into `.env`.

### 3. Run it as a systemd service

`/etc/systemd/system/todo-server.service`:

```ini
[Unit]
Description=Todo app backend
After=network.target

[Service]
Type=simple
User=YOUR_USERNAME
WorkingDirectory=/home/YOUR_USERNAME/todo-app-src/todo-app/server
EnvironmentFile=/home/YOUR_USERNAME/todo-app-src/todo-app/server/.env
ExecStart=/usr/bin/node src/server.js
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now todo-server
```

The server serves the client too, so this alone gets you a working app at
`http://<dell-lan-ip>:4000`. The next step adds HTTPS, which you need for PWA
install and push notifications.

### 4. HTTPS via Caddy (required for PWA install + push)

Android Chrome won't install a PWA or deliver push over plain HTTP. Caddy's
`tls internal` mode gives you HTTPS on your LAN without a domain or public
cert authority — you just have to trust its self-signed CA once, on each
device.

Install Caddy (Fedora doesn't ship it in the base repos):

```bash
sudo dnf install 'dnf-command(copr)'
sudo dnf copr enable @caddy/caddy
sudo dnf install caddy
```

Copy the `Caddyfile` from this repo to `/etc/caddy/Caddyfile`, then:

```bash
sudo systemctl enable --now caddy
```

This answers HTTPS on port 443 for whatever host/IP you hit it on — your LAN
IP, an mDNS name, or a Tailscale IP — no editing required.

**Trust the CA on your Android phone:**

1. On the Dell, find Caddy's root cert (path depends on how Caddy runs as a
   service — commonly `/var/lib/caddy/.local/share/caddy/pki/authorities/local/root.crt`).
2. Get it onto your phone (email it to yourself, AirDrop-equivalent, USB, whatever).
3. On Android: **Settings → Security → Encryption & credentials → Install a
   certificate → CA certificate**, pick the file, confirm. (Requires a device
   PIN/pattern to be set.)

Chrome will now trust `https://<dell-lan-ip>` and its certificate.

### 5. Install the PWA on your phone

Open `https://<dell-lan-ip>` in Chrome on Android → menu (⋮) → **Install app**.
It now lives on your home screen like any other app.

### 6. Turn on reminders

Tap the 🔔 button in the app once, on each device — it asks for notification
permission and registers a push subscription with the server.

**Android battery optimization can delay background push on some OEMs.** If
reminders feel late: **Settings → Apps → Chrome → Battery → Unrestricted**.

## Backups

SQLite + your own hardware means backups are on you. A simple daily snapshot:

```bash
sudo cp todo-app/server/scripts/backup.sh /usr/local/bin/todo-backup.sh
```

`/etc/systemd/system/todo-backup.service`:

```ini
[Unit]
Description=Backup todo database

[Service]
Type=oneshot
Environment=DB_PATH=/home/YOUR_USERNAME/todo-app-src/todo-app/server/data/todo.db
Environment=BACKUP_DIR=/home/YOUR_USERNAME/todo-backups
ExecStart=/usr/local/bin/todo-backup.sh
```

`/etc/systemd/system/todo-backup.timer`:

```ini
[Unit]
Description=Run todo backup daily

[Timer]
OnCalendar=daily
Persistent=true

[Install]
WantedBy=timers.target
```

```bash
sudo systemctl enable --now todo-backup.timer
```

You also get an on-demand full backup any time via the **Export** button in
the app itself (downloads a JSON file you can stash anywhere — Restore via
**Import** replaces all current data with the file's contents).

## Accessing it away from home (optional)

You said you often leave Tailscale off since it slows things down on the same
WiFi — that's fine, nothing here depends on it being on at home. If you want
access when you're *not* on the home WiFi, the cleanest option is a
**Tailscale subnet route**: advertise the home LAN's CIDR from the Dell
(`tailscale up --advertise-routes=<lan-cidr>`) and accept it on your phone.
Then the same `https://<dell-lan-ip>` URL works both at home (direct LAN,
Tailscale irrelevant) and away (routed over the tailnet) — no separate PWA
install, no second hostname to manage. Set this up whenever you actually want
it; it's not required for the app to work at home.

## How the recurrence logic actually works

- Completing a recurring todo asks (or defaults to today) for the date you
  actually did it, then schedules the next occurrence at `completedAt +
  interval` — never from the original due date.
- Editing a completed todo's "done on" date afterward reschedules the
  already-generated next occurrence, as long as that next occurrence hasn't
  itself been completed yet.
- Un-completing a todo by mistake removes the auto-generated next occurrence
  it spawned (if that one is still untouched), so you don't end up with
  duplicates.
- Recurring todos carry their subtask checklist forward, reset to unchecked,
  into each new occurrence.

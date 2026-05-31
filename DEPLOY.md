# Byme Bank — Free Deploy Guide

Get the whole stack live on the public internet for **$0/month**, in
about 20 minutes (most of which is Render building).

You'll deploy 3 services + 1 database, all on [Render.com](https://render.com)'s
free tier, using a single blueprint file (`render.yaml`).

---

## Before you start

You need:

1. A **GitHub account** (free) — for hosting the repo
2. A **Render.com account** (free) — sign up with GitHub
3. **Git** installed locally — `git --version` to check
4. The following secrets ready to paste:
   - `VPS_ADMIN_KEY` — `zmANxbWsk52oNgG669Ahjqvi0a4IBgzuU6oa4Wd2I9c` (from `bot-bridge/.env`)
   - `VPS_CUSTOMER_KEY` — `sk_089ab624449043c21d0c2f4ad606c814` (from `application-local.yml`)
   - `EXTERNAL_ISSUE_SECRET` — `khmerbank_xK9p2mNqLvR8tH5wY3jZc7vBfDgT4` (from `bot-bridge/.env`)
   - `BOT_ADMIN_KEY` — same as `VPS_ADMIN_KEY`
   - `GMAIL_USERNAME` — your Gmail (for sending OTP codes)
   - `GMAIL_APP_PASSWORD` — 16-char Google App Password ([how to create one](https://support.google.com/accounts/answer/185833))
   - `INITIAL_ADMIN_EMAIL` — your email (first signup with this becomes admin)

You'll also generate one new value:
   - `BRIDGE_AUTH_TOKEN` — any random 40-character string. Run this in
     PowerShell to make one:
     ```powershell
     -join ((1..40) | ForEach-Object { [char[]](48..57+65..90+97..122) | Get-Random })
     ```

---

## Step 1 — Push the code to GitHub

From the project root:

```powershell
cd d:\structerbankfutur\khmer-bank-gateway

# Init git if not done already
git init
git add .
git commit -m "Prep for Render deploy"

# Create an empty repo on github.com (no README, no .gitignore)
# Then connect it:
git remote add origin https://github.com/YOUR_USERNAME/bymebank.git
git branch -M main
git push -u origin main
```

> **Heads up about secrets** — `bot-bridge/.env` and `server/src/main/resources/application-local.yml`
> contain real credentials. They're already in `.gitignore` for `.env`,
> but check before pushing:
> ```powershell
> git status
> ```
> If you see `.env` or `application-local.yml` listed, run:
> ```powershell
> git rm --cached bot-bridge/.env
> git rm --cached server/src/main/resources/application-local.yml
> ```
> then commit again.

## Step 2 — Open Render Blueprint flow

1. Go to https://dashboard.render.com/blueprints
2. Click **"New Blueprint Instance"**
3. Connect your GitHub account if you haven't (Render will ask)
4. Pick the **bymebank** repo from the list
5. Give the blueprint a name (e.g. `bymebank-prod`)
6. Click **"Apply"**

Render reads `render.yaml`, sees 3 services + 1 database, and asks you
to fill in the values marked `sync: false`. This is where you paste
the secrets from the list above.

## Step 3 — Paste the secrets

Render shows a form with one row per secret. Paste each value:

| Variable                   | Value                                                            |
|----------------------------|------------------------------------------------------------------|
| `BRIDGE_AUTH_TOKEN`        | (the 40-char random string you generated)                        |
| `VPS_ADMIN_KEY`            | `zmANxbWsk52oNgG669Ahjqvi0a4IBgzuU6oa4Wd2I9c`                    |
| `VPS_CUSTOMER_KEY`         | `sk_089ab624449043c21d0c2f4ad606c814`                            |
| `EXTERNAL_ISSUE_SECRET`    | `khmerbank_xK9p2mNqLvR8tH5wY3jZc7vBfDgT4`                        |
| `BOT_ADMIN_KEY`            | `zmANxbWsk52oNgG669Ahjqvi0a4IBgzuU6oa4Wd2I9c`                    |
| `GMAIL_USERNAME`           | (your Gmail address)                                             |
| `GMAIL_APP_PASSWORD`       | (16-char Google app password)                                    |
| `INITIAL_ADMIN_EMAIL`      | (your email)                                                     |

> **Important:** `BRIDGE_AUTH_TOKEN` must be the **exact same value** on
> both `bymebank-server` and `bymebank-bridge` services — paste it twice.

Click **"Apply"** at the bottom. Render starts building.

## Step 4 — Wait for the first build (~12 min)

Render now does, in parallel:

- Spins up Postgres (`bymebank-db`) — ~2 min
- Builds the Spring API (`bymebank-server`) — ~10 min (Maven cold cache)
- Builds the Python bridge (`bymebank-bridge`) — ~3 min
- Builds the React dashboard (`bymebank-dashboard`) — ~4 min

Watch the build logs. The Spring server is the slowest. If anything
fails, the **Logs** tab tells you what went wrong (usually a missing env
var — fix it on the service's "Environment" tab and redeploy).

## Step 5 — Find your URLs

Once all services show a green **"Live"** badge:

- Dashboard: `https://bymebank-dashboard.onrender.com`
- API: `https://bymebank-server.onrender.com`
- Bridge: `https://bymebank-bridge.onrender.com` (internal, you don't visit)

Open the dashboard URL. You should see the Byme Bank landing page.

## Step 6 — Sign up + verify

1. Click **"Sign in"** on the landing page
2. Enter your email (the one you put in `INITIAL_ADMIN_EMAIL`)
3. Check Gmail for the OTP code, paste it back
4. You're now logged in as **admin** in the live dashboard

Congratulations bro, it's live.

---

## Cold-start warning (free tier reality)

Render's free tier sleeps services after **15 minutes of no traffic**.
The next request takes ~30 seconds to wake it up — first-time visitors
will see a slow load. Three workarounds:

1. **Live with it** — fine for low-traffic sites
2. **Keep the API warm** — set up a free [UptimeRobot](https://uptimerobot.com)
   monitor to ping `https://bymebank-server.onrender.com/actuator/health`
   every 5 minutes
3. **Upgrade** — Render's "Starter" plan ($7/mo per service) is always-on

## Postgres expiry warning

Render's free Postgres expires after **90 days** and gets deleted. Before
that:

1. From the Render dashboard → `bymebank-db` → **"Backups"** tab
2. Download the latest dump
3. Either upgrade to paid Postgres ($7/mo) or spin up a new free DB and
   restore the dump

Set a calendar reminder for Day 80.

## How to update the deployed app

Just push to GitHub. With `autoDeploy: true` in `render.yaml`, every
push to `main` triggers a redeploy automatically.

```powershell
git add .
git commit -m "Update something"
git push
```

Render rebuilds in 5-12 minutes and swaps in the new version with zero
downtime.

## Roll back

If a deploy breaks something:

1. Go to the failing service → **Deploys** tab
2. Find the last green deploy
3. Click **"Rollback to this deploy"**

Done in ~30 seconds.

---

## Troubleshooting

### "Build failed: cannot find application-prod.yml"
You forgot to push `server/src/main/resources/application-prod.yml`.
Run `git status` to confirm it was committed.

### "Forbidden: X-Bridge-Token mismatch"
`BRIDGE_AUTH_TOKEN` differs between the server and bridge services.
Set it identical on both, then redeploy both.

### "OTP email not arriving"
- Wrong `GMAIL_APP_PASSWORD` — has to be the 16-char app password, NOT
  your normal Gmail password
- App password not generated yet — visit
  [myaccount.google.com](https://myaccount.google.com) → Security →
  2-Step Verification → App passwords

### "Bank logos showing 404"
Branding refreshes from the bot. Trigger it: open the dashboard, log in,
the `/api/branding/public` request will pull fresh logos from
`apicheckpayment.onrender.com`.

### "QR code says invalid format"
The customer key on Render doesn't match the upstream platform key.
Verify `VPS_CUSTOMER_KEY` matches what's in your local `.env`.

---

Live URL goes to: `https://bymebank-dashboard.onrender.com`


---

# Custom Domain Setup (after deploy is live)

Got `bymebank.com`? Wire it to Render in 5 min.

## Step 1 — Buy the domain

Cheapest legit options:

| Registrar  | Domain        | First-year price       |
|------------|---------------|------------------------|
| porkbun    | bymebank.xyz  | ~$1                    |
| porkbun    | bymebank.com  | ~$9                    |
| namecheap  | bymebank.com  | ~$9                    |
| cloudflare | any (at cost) | wholesale, no markup   |

Buy with your card. WHOIS privacy is usually free — turn it on so your
home address isn't public.

## Step 2 — Add the domain in Render

1. Render dashboard → `bymebank-dashboard` service → **Settings** tab
2. Scroll to **"Custom Domains"** → click **"Add Custom Domain"**
3. Type `bymebank.com` (apex / root) → Add
4. Render shows you DNS records to add — copy them. Something like:
   ```
   Type: A      Name: @     Value: 216.24.57.1
   Type: AAAA   Name: @     Value: 2a09:8280:1::6f1f:c43c
   ```
5. Click **"Add Custom Domain"** AGAIN, this time for `www.bymebank.com`
6. Render gives a CNAME record. Copy it:
   ```
   Type: CNAME  Name: www   Value: bymebank-dashboard.onrender.com
   ```

## Step 3 — Add the DNS records at your registrar

Open your registrar's DNS panel:
- **Porkbun**: Domain Management → click the domain → **DNS Records**
- **Namecheap**: Domain List → Manage → **Advanced DNS**
- **Cloudflare**: pick the zone → **DNS** tab

Delete any auto-generated `parking` or `forwarding` records. Then add:

| Type  | Host / Name | Value                              | TTL  |
|-------|-------------|------------------------------------|------|
| A     | `@`         | (the IP from Render)               | 300  |
| AAAA  | `@`         | (the IPv6 from Render, if shown)   | 300  |
| CNAME | `www`       | `bymebank-dashboard.onrender.com`  | 300  |

> **Cloudflare gotcha** — set proxy status to **DNS-only** (grey cloud,
> not orange) for both records. Render handles HTTPS itself; if
> Cloudflare proxies, the SSL cert won't issue.

Save the records.

## Step 4 — Wait, then verify

1. DNS propagation: 5-30 min usually, up to a few hours worst case
2. Back in Render → **Custom Domains** section, refresh — both rows
   should turn green ("Verified" + "Certificate issued")
3. Open `https://bymebank.com` → your dashboard, served on your domain,
   with auto HTTPS

## Step 5 — Update API CORS + dashboard config

The API calls go to `bymebank-server.onrender.com` by default. To use
your domain end-to-end, update:

1. (Optional) Add a custom domain to the server too: `api.bymebank.com`
   - Same flow as Step 2, on the `bymebank-server` service
   - DNS: `CNAME api → bymebank-server.onrender.com`
2. On the dashboard service → **Environment** tab → edit:
   ```
   VITE_API_BASE = https://api.bymebank.com
   ```
3. Save → Render redeploys the dashboard (~4 min) → done.

## Troubleshooting

**"Certificate pending" stuck for 30+ minutes**
DNS hasn't propagated. Run `nslookup bymebank.com` from PowerShell — if
it doesn't return Render's IP yet, just wait. If it returns the right IP
but Render is still pending, click "Verify" on Render's panel.

**"Mixed content" warnings**
Your dashboard calls a non-HTTPS API. Make sure `VITE_API_BASE` starts
with `https://`.

**Cloudflare orange cloud**
If you proxied via Cloudflare (orange icon), Render can't issue the
cert. Switch to grey cloud (DNS only), or set Cloudflare's SSL mode to
"Full (strict)" and use a Cloudflare-issued cert.

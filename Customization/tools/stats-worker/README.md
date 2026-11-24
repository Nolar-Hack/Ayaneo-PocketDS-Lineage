# Counter of the versions in use

PocketDS Extra sends, once a day, a random identifier and the version of the system to this Worker
(Cloudflare, free plan). It tells which versions are still in use, to choose how often to release
updates, how many incremental packages to keep (`OTA_KEEP_RELEASES`), and whether frequent updates are
worth it.

What is kept, for each console: its random identifier (made by the app, renewed every 12 months, lost
at a wipe), its version and the day it was last seen. No IP address, no serial number, no country, no
log. An identifier not seen for 30 days is deleted; a console that comes back later is counted again.
The user can switch it off (System update > Count this console): the app then deletes its identifier.

## Set it up (once)

    npm install -g wrangler            # or use npx wrangler ...
    wrangler login
    wrangler kv namespace create CONSOLES      # put the id it prints in wrangler.toml
    wrangler secret put STATS_TOKEN            # any long random text: protects /stats
    wrangler secret put DISCORD_WEBHOOK        # webhook of your private channel (optional)
    wrangler deploy                            # prints the address, https://pocketds-stats.<you>.workers.dev

Then set `STATS_URL="https://pocketds-stats.<you>.workers.dev/"` in `pocketds.conf` (or
`pocketds.local.conf`) and build: the address is built into the image (`ro.pocketds.stats_url`).
Without it the consoles send nothing and the switch is hidden.

## Read the count

    curl -s -H "Authorization: Bearer <STATS_TOKEN>" https://pocketds-stats.<you>.workers.dev/stats

`consoles` = consoles seen in the last 30 days; per version: `consoles` (30 days), `week` (seen in the
last 7 days), `today`.

## Discord

With `DISCORD_WEBHOOK` set, every day at 07:00 UTC the Worker edits one message in the channel (pin it
once) and every Monday posts a new one, which keeps the history. The consoles never see the webhook: they
only talk to this Worker, which writes the messages itself from counts and checked version names
(mentions disabled).

## What is accepted

A uuid and the version of a published release (list of the GitHub releases of `RELEASE_REPO`), 200 bytes
at most, one write per console a day. Also add, once, a rate limiting rule in the Cloudflare dashboard
(Security > WAF > Rate limiting rules: path `/`, method POST, 10 requests per minute per IP, block):
the IP is checked there and kept nowhere. Fake identifiers would show up as "new" consoles of one day.

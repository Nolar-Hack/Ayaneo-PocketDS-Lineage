// Counter of the PocketDS versions in use (Cloudflare Worker + KV), with a summary in Discord.
//
// POST /         {"id": "<random uuid>", "version": "2026.11.14"}   sent once a day by PocketDS Extra
// GET  /stats    Authorization: Bearer <STATS_TOKEN>                 the count, as JSON
// cron (daily)   edits the pinned summary in Discord; on Mondays also posts a new one (history)
//
// Kept for each console: its random identifier, its version, the day it was first and last seen.
// Nothing else: no address, no header, no log (observability is off in wrangler.toml). A console not
// seen for 30 days is deleted by KV itself (expirationTtl, renewed at each visit); if it comes back it
// is simply counted again.
//
// What is received is checked: a uuid and the version of a real release (the list of the GitHub
// releases), at most one write per console a day. The consoles never see the Discord webhook: it is a
// secret of this Worker (DISCORD_WEBHOOK), and the messages are made here from counts and checked
// version names only, mentions disabled.

const KEEP_SECONDS = 30 * 24 * 3600;
const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const VERSION = /^\d{4}\.\d{1,2}\.\d{1,3}$/;
const DAY = 86400000;

const today = () => new Date().toISOString().slice(0, 10);
const days = (from, to) => Math.round((Date.parse(to) - Date.parse(from)) / DAY);

// The published versions, newest first (GitHub releases of RELEASE_REPO), kept 6 hours.
async function releases(env) {
  const cached = await env.CONSOLES.get("_releases", "json");
  if (cached && Date.now() - cached.at < 6 * 3600 * 1000) return cached.list;
  try {
    const r = await fetch(`https://api.github.com/repos/${env.RELEASE_REPO}/releases?per_page=100`, {
      headers: { "User-Agent": "pocketds-stats", Accept: "application/vnd.github+json" },
    });
    if (!r.ok) throw new Error(`GitHub ${r.status}`);
    const list = (await r.json())
      .filter((x) => !x.draft)
      .map((x) => x.tag_name.replace(/^v/, ""))
      .filter((v) => VERSION.test(v));
    await env.CONSOLES.put("_releases", JSON.stringify({ at: Date.now(), list }));
    return list;
  } catch {
    return cached ? cached.list : null; // GitHub unreachable: the last list, else no check
  }
}

async function receive(request, env) {
  const text = await request.text();
  if (text.length > 300) return new Response("bad request", { status: 400 });
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    return new Response("bad request", { status: 400 });
  }
  if (!body || !ID.test(body.id || "") || !VERSION.test(body.version || "")) {
    return new Response("bad request", { status: 400 });
  }
  const known = await releases(env);
  if (known && !known.includes(body.version)) return new Response("unknown version", { status: 400 });

  // a console that renewed its number: the old one is dropped at once (no double count)
  if (body.replaces && ID.test(body.replaces) && body.replaces !== body.id) {
    await env.CONSOLES.delete("c:" + body.replaces);
  }
  const key = "c:" + body.id;
  const day = today();
  const { metadata: old } = await env.CONSOLES.getWithMetadata(key);
  if (old && old.d === day && old.v === body.version) return new Response("ok"); // already today
  await env.CONSOLES.put(key, "", {
    expirationTtl: KEEP_SECONDS,
    metadata: { v: body.version, d: day, f: old ? old.f : day },
  });
  return new Response("ok");
}

async function count(env) {
  const day = today();
  const versions = {};
  let total = 0, week = 0, fresh = 0, cursor;
  do {
    const page = await env.CONSOLES.list({ prefix: "c:", cursor });
    for (const { metadata: m } of page.keys) {
      if (!m) continue;
      const v = (versions[m.v] ||= { consoles: 0, week: 0, today: 0 });
      v.consoles++;
      total++;
      if (days(m.d, day) < 7) { v.week++; week++; }
      if (m.d === day) v.today++;
      if (days(m.f, day) < 7) fresh++;
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  return { day, total, week, fresh, versions };
}

function summary(stats, list, lastTotal) {
  const order = list || Object.keys(stats.versions).sort().reverse();
  const width = 20;
  const lines = [];
  let within = [], cumulative = 0;
  order.forEach((v, i) => {
    const n = stats.versions[v] ? stats.versions[v].consoles : 0;
    cumulative += n;
    within.push(cumulative);
    if (!n) return;
    const share = stats.total ? n / stats.total : 0;
    const bar = "█".repeat(Math.round(share * width)).padEnd(width, "░");
    const tag = (i === 0 ? "(latest)" : `(-${i})`).padEnd(9);
    lines.push(`${v.padEnd(11)} ${tag} ${bar} ${String(n).padStart(3)}  ${String(Math.round(share * 100)).padStart(3)} %`);
  });
  const pct = (k) => (stats.total ? Math.round((100 * (within[k - 1] ?? cumulative)) / stats.total) : 0);
  const keep = [3, 4, 5, 6, 8].find((k) => pct(k) >= 95) ?? 8;
  const gone = lastTotal == null ? "" : ` · -${Math.max(0, lastTotal + stats.fresh - stats.total)} gone`;
  return [
    "```",
    `PocketDS consoles                               updated ${stats.day}`,
    "",
    `${stats.total} consoles seen in the last 30 days  (${stats.week} this week · +${stats.fresh} new${gone})`,
    "",
    ...lines,
    "",
    `Within the last 3 releases: ${pct(3)} %  ·  4: ${pct(4)} %  ·  6: ${pct(6)} %`,
    `→ OTA_KEEP_RELEASES ${keep} covers 95 % of the consoles seen this month`,
    "```",
  ].join("\n");
}

async function discord(env, method, path, content) {
  const r = await fetch(`${env.DISCORD_WEBHOOK}${path}`, {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ content, allowed_mentions: { parse: [] } }),
  });
  return r.ok ? (method === "POST" ? r.json() : {}) : null;
}

async function report(env) {
  if (!env.DISCORD_WEBHOOK) return;
  const stats = await count(env);
  const list = await releases(env);
  const state = (await env.CONSOLES.get("_discord", "json")) || {};
  const text = summary(stats, list, state.lastTotal);

  // the pinned message, edited every day (made again if it was deleted)
  let edited = state.pinned && (await discord(env, "PATCH", `/messages/${state.pinned}`, text));
  if (!edited) {
    const made = await discord(env, "POST", "?wait=true", text);
    if (made) state.pinned = made.id;
  }
  // a new message every Monday: the history
  if (new Date().getUTCDay() === 1 && state.lastWeekly !== stats.day) {
    await discord(env, "POST", "", text);
    state.lastWeekly = stats.day;
    state.lastTotal = stats.total;
  }
  await env.CONSOLES.put("_discord", JSON.stringify(state));
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "POST" && url.pathname === "/") return receive(request, env);
    if (request.method === "GET" && url.pathname === "/stats") {
      if (!env.STATS_TOKEN || request.headers.get("Authorization") !== `Bearer ${env.STATS_TOKEN}`) {
        return new Response("forbidden", { status: 403 });
      }
      return Response.json(await count(env));
    }
    return new Response("not found", { status: 404 });
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(report(env));
  },
};

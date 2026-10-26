# Lumen key service

Lumen's trending rows (YouTube, Netflix, Prime Video), "where to watch" and Lumen Window need API keys from YouTube, TMDB and Pixabay. Keys built into an app can be pulled out by anyone who has the APK, so **Lumen ships with none**. Instead, projectors call this small Cloudflare Worker, and the Worker adds the keys on its side.

- The keys live only in the Worker's **encrypted secrets**. They aren't in this repo, the APK or the projector, and once saved, Cloudflare never shows them again (not even to you).
- The Worker only forwards the exact calls Lumen makes (YouTube's most-popular chart, TMDB discover/search/providers, Pixabay video search) and strips the keys out of anything it sends back.
- Answers are cached, so your API quotas barely move however many projectors use it. The free Cloudflare plan is more than enough.
- People can still enter their own keys on the phone remote; Lumen then calls the APIs directly.

## Set it up (about 10 minutes, once)
1. **Get fresh keys** (free). If you've ever shared keys anywhere, delete those and make new ones:
   - YouTube: console.cloud.google.com → APIs & Services → enable **YouTube Data API v3** → Credentials → **Create credentials → API key**. Edit the key → API restrictions → **YouTube Data API v3** only.
   - TMDB: themoviedb.org → Settings → API → the **API Read Access Token** (or the API key).
   - Pixabay: pixabay.com/api/docs → your key is shown when you're signed in.
2. **Create the Worker:** dash.cloudflare.com (free account) → **Workers & Pages → Create → Create Worker** → name it `lumen-keys` → **Deploy** → **Edit code** → replace everything with [`worker.js`](worker.js) → **Deploy**.
3. **Add the keys as secrets:** the Worker → **Settings → Variables and Secrets → Add**, type **Secret**:
   `YT_KEY`, `TMDB_KEY`, `PIXABAY_KEY`. Paste each key yourself and **Deploy**. Don't paste them into a chat, an issue or a file.
4. **Cache (recommended):** **Storage & Databases → KV → Create** a namespace `lumen-cache`. Then the Worker → **Settings → Bindings → Add → KV namespace**, variable name `CACHE`, namespace `lumen-cache` → **Deploy**.
5. **Point Lumen at it:** copy the Worker's address (like `https://lumen-keys.<you>.workers.dev`) into `"keyProxy"` in [`ota/update.json`](../ota/update.json) and push. The address isn't secret. Every projector picks it up at its next update check (startup or within 6 hours), with no new APK.

Check it works: open `https://lumen-keys.<you>.workers.dev/` (shows `"ok": true`), then
`…/yt/videos?part=snippet&chart=mostPopular&maxResults=1&regionCode=AE` (a YouTube video, no key in the reply).

## Limits
Anyone who finds the Worker's address can make the same few calls Lumen makes. That's by design (it's what lets any Lumen work out of the box), and it's limited: only Lumen's own calls, cached, and your keys are never revealed. If someone abuses it, rotate a key in the Worker's secrets: projectors don't need to change anything.

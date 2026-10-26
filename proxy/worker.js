// Lumen key service: a Cloudflare Worker that forwards Lumen's API calls and adds the API keys on its
// side. The keys live only in this Worker's encrypted secrets (YT_KEY, TMDB_KEY, PIXABAY_KEY): they are
// never in the repo, the APK or the projector, and once saved Cloudflare doesn't show them again.
//
// Only the exact calls Lumen makes are allowed, the keys are stripped from anything sent back, and
// answers are cached (KV binding "CACHE", optional but recommended) so the API quotas stay tiny however
// many projectors use it.

const ROUTES = {
  yt: {
    origin: "https://www.googleapis.com/youtube/v3/",
    allow: /^videos$/,
    params: ["part", "chart", "maxResults", "regionCode"],
    check: (q) => q.get("chart") === "mostPopular",
    secret: "YT_KEY",
    ttl: 3 * 3600,
  },
  tmdb: {
    origin: "https://api.themoviedb.org/3/",
    allow: /^(discover\/(movie|tv)|search\/multi|(movie|tv)\/\d{1,9}\/watch\/providers)$/,
    params: ["watch_region", "with_watch_providers", "with_watch_monetization_types", "sort_by",
      "include_adult", "language", "page", "query"],
    check: () => true,
    secret: "TMDB_KEY",
    ttl: 12 * 3600,
  },
  pixabay: {
    origin: "https://pixabay.com/api/",
    allow: /^videos\/?$/,
    params: ["q", "video_type", "min_width", "safesearch", "order", "per_page", "page"],
    check: () => true,
    secret: "PIXABAY_KEY",
    ttl: 24 * 3600,
  },
};

const json = (status, obj, extra = {}) =>
  new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...extra },
  });

export default {
  async fetch(request, env, ctx) {
    if (request.method !== "GET") return json(405, { error: "GET only" });
    const url = new URL(request.url);
    const m = url.pathname.match(/^\/(yt|tmdb|pixabay)\/(.*)$/);
    if (!m) return json(200, { service: "Lumen key service", ok: true });

    const route = ROUTES[m[1]];
    const path = m[2];
    if (!route.allow.test(path)) return json(403, { error: "Not a Lumen call" });

    // Only the parameters Lumen uses, in a fixed order (also the cache key).
    const q = new URLSearchParams();
    for (const p of route.params) {
      const v = url.searchParams.get(p);
      if (v !== null && v.length <= 200) q.set(p, v);
    }
    if (!route.check(q)) return json(403, { error: "Not a Lumen call" });

    const secret = env[route.secret];
    if (!secret) return json(503, { error: "This service has no " + m[1] + " key yet" });

    const cacheKey = m[1] + "/" + path + "?" + q.toString();
    if (env.CACHE) {
      const hit = await env.CACHE.get(cacheKey);
      if (hit !== null) {
        return new Response(hit, {
          headers: { "Content-Type": "application/json; charset=utf-8", "X-Lumen-Cache": "hit" },
        });
      }
    }

    const up = new URL(route.origin + path);
    q.forEach((v, k) => up.searchParams.set(k, v));
    const headers = { Accept: "application/json", "User-Agent": "Lumen-key-service" };
    if (m[1] === "tmdb" && secret.startsWith("eyJ")) headers.Authorization = "Bearer " + secret;
    else up.searchParams.set(m[1] === "tmdb" ? "api_key" : "key", secret);

    let res;
    try {
      res = await fetch(up.toString(), { headers });
    } catch (e) {
      return json(502, { error: "Upstream unreachable" });
    }
    // Never send a key back, even inside an error message.
    const body = (await res.text()).split(secret).join("***");
    if (res.ok && env.CACHE) ctx.waitUntil(env.CACHE.put(cacheKey, body, { expirationTtl: route.ttl }));
    return new Response(body, {
      status: res.status,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": res.ok ? "public, max-age=" + route.ttl : "no-store",
      },
    });
  },
};

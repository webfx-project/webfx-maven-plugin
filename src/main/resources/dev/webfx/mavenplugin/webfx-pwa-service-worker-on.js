const MAVEN_BUILD_TIMESTAMP = "${mavenBuildTimestamp}";

console.log("PWA mode is on - mavenBuildTimestamp = " + MAVEN_BUILD_TIMESTAMP);

const CACHE_NAME = "webfx-pwa-cache";
const DEFAULT_PRE_CACHE = false;

// Single asset map that PwaMojo will populate: { "/file1": { preCache: true|false, hash: "XXXX" }, "/file2": "YYYY", ... }
// If preCache is missing, or the value is a string (treated as a hash), we consider preCache = DEFAULT_PRE_CACHE
const ASSET = {};

function normalizeAsset(assetLike) {
    const hashToInfo = {};
    const pathToHash = {};
    try {
        for (const [path, v] of Object.entries(assetLike || {})) {
            let hash, preCache;
            if (typeof v === "string") { hash = v; preCache = DEFAULT_PRE_CACHE; }
            else if (v && typeof v.hash === "string") { hash = v.hash; preCache = (typeof v.preCache !== "undefined") ? !!v.preCache : DEFAULT_PRE_CACHE; }
            if (typeof path === "string" && hash) {
                hashToInfo[hash] = { path, preCache };
                pathToHash[path] = hash;
            }
        }
    } catch (e) { }
    return { hashToInfo, pathToHash };
}

const { hashToInfo: HASH_TO_INFO, pathToHash: PATH_TO_HASH } = normalizeAsset(ASSET);


// Build a cache Request for a given content hash
function toHashRequest(hash) {
    // Use a simple, prefix-free synthetic request URL based on scope + hash
    // This keeps keys unique per scope while avoiding the __asset_hash__/ prefix
    const scope = getScope();
    const u = scope + hash;
    return new Request(u);
}

// Remove any cached hash entries that are not present in the provided allowedHashes set
async function deleteHashesNotIn(allowedHashes, cache) {
    if (!cache) cache = await caches.open(CACHE_NAME)
    const keys = await cache.keys();
    let deletedCount = 0;
    await Promise.all(keys.map(async (req) => {
        // Only consider entries that were stored via toHashRequest(hash).
        // We assume such entries end exactly with the hash (scope + hash) and that hash is a 64-char hex string (sha-256)
        try {
            const url = new URL(req.url);
            const path = url.pathname || "";
            // scope path + 64 hex chars
            const scopePath = getScopePathname();
            const suffix = path.startsWith(scopePath) ? path.substring(scopePath.length) : null;
            const isHex64 = suffix && /^[a-f0-9]{64}$/i.test(suffix);
            const hash = isHex64 ? suffix : null;
            if (!hash) return;
            if (!allowedHashes.has(hash)) {
                const ok = await cache.delete(req);
                if (ok) deletedCount++;
            }
        } catch (e) { /* ignore parse errors */ }
    }));
    return deletedCount;
}

function getPathFromRequest(request) {
    try {
        const url = new URL(request.url);
        return url.pathname;
    } catch (e) {
        return request.url; // fall back (should already be a path for same-origin requests)
    }
}

function getScope() {
    return self.registration && self.registration.scope ? self.registration.scope : self.location.origin + "/";
}

function getScopePathname() {
    try {
        return new URL(getScope()).pathname;
    } catch (e) {
        return "/";
    }
}

function toScopedRequest(path) {
    // Ensure leading slash for consistency with manifest keys
    const p = path.startsWith("/") ? path : "/" + path;
    const scopedUrl = new URL(p, getScope()).toString();
    return new Request(scopedUrl);
}

function toManifestPathFromRequest(request) {
    const reqPath = getPathFromRequest(request) || "/";
    const scopePath = getScopePathname();
    if (scopePath && scopePath !== "/" && reqPath.startsWith(scopePath)) {
        const sub = reqPath.substring(scopePath.length);
        return "/" + (sub.startsWith("/") ? sub.substring(1) : sub);
    }
    return reqPath;
}

// Install: take control ASAP, actual caching is done during "activate" based on hash diff
self.addEventListener("install", event => {
    event.waitUntil(self.skipWaiting());
});

// Activate: ensure pre-cached assets are present (by hash) and remove stale hash entries
self.addEventListener("activate", event => {
    event.waitUntil((async () => {
        // Better to disable navigation preload for now
        if (self.registration.navigationPreload) {
            await self.registration.navigationPreload.disable();
        }
        const cache = await caches.open(CACHE_NAME);

        // Remove any cached hashes not present in current ASSET (best-effort)
        try {
            const allowed = new Set(Object.keys(HASH_TO_INFO));
            await deleteHashesNotIn(allowed, cache);
        } catch (e) { }

        // Pre-cache assets marked preCache=true
        await Promise.all(Object.entries(HASH_TO_INFO).filter(([, info]) => info && info.preCache === true).map(async ([hash, info]) => {
            const req = toHashRequest(hash);
            const existing = await cache.match(req);
            if (!existing) {
                try {
                    const resp = await fetch(info.path, { cache: "no-cache" });
                    if (resp && resp.ok) await cache.put(req, resp.clone());
                } catch (e) { }
            }
        }));

        // Become active immediately for open clients
        await self.clients.claim();
    })());
});

// Fetch: serve from cache first, then network as fallback
self.addEventListener("fetch", event => {
    console.log("Fetch request: " + event.request.url);
    event.respondWith((async () => {
        // Special case: the main page should be network-first (with cache fallback)
        try {
            let url2;
            try {
                url2 = new URL(event.request.url);
            } catch {
            }
            const sameOrigin2 = url2 && url2.origin === self.location.origin;
            const manifestPath2 = toManifestPathFromRequest(event.request);
            if (sameOrigin2 && (manifestPath2 === "/" || manifestPath2 === "/index.html")) {
                try {
                    const networkResponse = await fetch(event.request, {cache: "no-cache"});
                    if (networkResponse && networkResponse.ok) {
                        // Inspect the fetched index.html to detect version changes via mavenBuildTimestamp meta
                        try {
                            const clonedForMeta = networkResponse.clone();
                            const text = await clonedForMeta.text();
                            const match = text.match(/<meta\s+name=["']mavenBuildTimestamp["']\s+content=["']([^"']+)["']\s*\/?>(?:\s*<\/meta>)?/i);
                            if (match && typeof MAVEN_BUILD_TIMESTAMP !== "undefined") {
                                const fetchedTs = match[1];
                                if (fetchedTs !== MAVEN_BUILD_TIMESTAMP) {
                                    console.log("🔆🔆🔆🔆🔆 Detected index.html version change: fetched=" + fetchedTs + ", build=" + MAVEN_BUILD_TIMESTAMP);
                                    // Immediately fetch the new PWA manifest and clean caches for changed/removed assets
                                    try {
                                        const cleanupPromise = (async () => {
                                            try {
                                                const resp = await fetch("/webfx-pwa-asset.json", {cache: "no-cache"});
                                                if (resp && resp.ok) {
                                                    const json = await resp.json();
                                                    const newAsset = (json && (json.assetManifest || json)) || {};
                                                    const newHashes = new Set(Object.values(newAsset || {}).map(v => (typeof v === "string" ? v : (v && v.hash)) ).filter(Boolean));
                                                    const cache = await caches.open(CACHE_NAME);
                                                    const deletedCount = await deleteHashesNotIn(newHashes, cache);
                                                    if (deletedCount > 0) {
                                                        console.log("🧹 Cleaned " + deletedCount + " cached entries due to version change");
                                                    } else {
                                                        console.log("🧹 No cached entries required cleaning for version change");
                                                    }
                                                } else {
                                                    console.log("ℹ️ Could not fetch /webfx-pwa-asset.json (status " + (resp && resp.status) + ")");
                                                }
                                            } catch (eFetchMan) {
                                                console.log("ℹ️ Error while fetching/processing new manifest: " + (eFetchMan && eFetchMan.message ? eFetchMan.message : eFetchMan));
                                            }
                                        })();
                                        // Ensure the cleanup continues even if we return the response
                                        event.waitUntil(cleanupPromise);
                                    } catch (eCleanup) { /* ignore cleanup trigger errors */
                                    }
                                } else {
                                    console.log("✳️✳️✳️✳️✳️ index.html version still matches " + MAVEN_BUILD_TIMESTAMP);
                                }
                            }
                        } catch (eMeta) { /* ignore meta inspection errors */
                        }
                        try {
                            const cache = await caches.open(CACHE_NAME);
                            const h = PATH_TO_HASH[manifestPath2];
                            if (manifestPath2 === "/" || manifestPath2 === "/index.html") {
                                // Special: store index.html under its path key for offline fallback
                                await cache.put(toScopedRequest("/index.html"), networkResponse.clone());
                            } else if (h) {
                                await cache.put(toHashRequest(h), networkResponse.clone());
                            }
                        } catch (eCache) { /* ignore cache put errors */
                        }
                        return networkResponse;
                    }
                } catch (eNet) {
                    // Network failed; will try cache fallback below
                }
                const h2 = PATH_TO_HASH[manifestPath2];
                const fallback = (manifestPath2 === "/" || manifestPath2 === "/index.html")
                    ? (await caches.match(toScopedRequest("/index.html"))
                        || (h2 && await caches.match(toHashRequest(h2)))
                        || await caches.match(event.request)
                        || await caches.match(toScopedRequest(manifestPath2)))
                    : ((h2 && await caches.match(toHashRequest(h2))) || await caches.match(event.request) || await caches.match(toScopedRequest(manifestPath2)));
                if (fallback) {
                    return fallback;
                }
                // No cached main page; continue with the general strategy below
            }
        } catch (e) { /* ignore */
        }

        // 1) If same-origin and known asset, try by hash key first
        let url;
        try { url = new URL(event.request.url); } catch {}
        const sameOrigin = url && url.origin === self.location.origin;
        let cachedResponse;
        if (sameOrigin) {
            const manifestPath = toManifestPathFromRequest(event.request);
            const knownHash = PATH_TO_HASH[manifestPath];
            if (knownHash) {
                cachedResponse = await caches.match(toHashRequest(knownHash));
                if (cachedResponse) {
                    console.log("Found in cache by hash: " + knownHash + " for path " + manifestPath);
                    return cachedResponse;
                }
            }
        }

        // 2) Legacy fallbacks: try exact and scoped pathname (for compatibility with older caches)
        cachedResponse = await caches.match(event.request);
        if (cachedResponse) {
            console.log("Found in cache (legacy exact): " + event.request.url);
            return cachedResponse;
        }
        if (sameOrigin) {
            cachedResponse = await caches.match(toScopedRequest(url.pathname));
            if (cachedResponse) {
                console.log("Found in cache (legacy pathname): " + url.pathname);
                return cachedResponse;
            }
        }

        // 3) If this is a navigation-like request, attempt SPA fallbacks ('/' and '/index.html')
        const acceptsHtml = (() => {
            try {
                return (event.request.headers.get("accept") || "").includes("text/html");
            } catch {
                return false;
            }
        })();
        const isNavLike = (event.request.mode === "navigate")
            || (sameOrigin && (event.request.destination === "document"))
            || (sameOrigin && event.request.method === "GET" && acceptsHtml);
        if (isNavLike) {
            const candidates = ["/", "/index.html"];
            for (const p of candidates) {
                const h = PATH_TO_HASH[p];
                let resp = h ? await caches.match(toHashRequest(h)) : null;
                if (!resp) resp = await caches.match(toScopedRequest(p)); // legacy
                if (resp) {
                    console.log("📄 Serving navigation from cache: " + p);
                    return resp;
                }
            }
        }

        // 4) Network fallback with logging (and lazy cache-as-you-go)
        try {
            const networkResponse = await fetch(event.request);
            console.log("✅ Fetch succeeded: " + event.request.url + " (status " + networkResponse.status + ")");
            // If this resource is marked for lazy caching, store it now under its hash key
            try {
                if (sameOrigin) {
                    const manifestPath = toManifestPathFromRequest(event.request);
                    const knownHash = PATH_TO_HASH[manifestPath];
                    const info = knownHash ? HASH_TO_INFO[knownHash] : null;
                    if (networkResponse && networkResponse.ok && info && info.preCache === false) {
                        const cache = await caches.open(CACHE_NAME);
                        await cache.put(toHashRequest(knownHash), networkResponse.clone());
                    }
                }
            } catch (e2) {
                // ignore caching errors
            }
            return networkResponse;
        } catch (e) {
            console.error("❌ Fetch failed: " + event.request.url + " - " + (e && e.message ? e.message : e));
            // As a last resort for navigation-like requests, try cached index again
            if (isNavLike) {
                const resp = await caches.match(toScopedRequest("/index.html")) || await caches.match(toScopedRequest("/"));
                if (resp) return resp;
            }
            throw e;
        }
    })());
});

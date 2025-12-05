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
            else if (v && typeof v.hash === "string") {
                hash = v.hash;
                preCache = (typeof v.preCache !== "undefined") ? !!v.preCache : DEFAULT_PRE_CACHE;
                // Capture size and strategy
                if (v.size) hashToInfo[hash] = { path, preCache, size: v.size, gzipSize: v.gzipSize, strategy: v.strategy };
                else hashToInfo[hash] = { path, preCache, strategy: v.strategy };
            }
            if (typeof path === "string" && hash && !hashToInfo[hash]) {
                hashToInfo[hash] = { path, preCache };
                pathToHash[path] = hash;
            } else if (typeof path === "string" && hash) {
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
    // Ensure path is relative to scope (remove leading slash if present)
    const p = path.startsWith("/") ? path.substring(1) : path;
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

// State tracking
const activeDownloads = new Map(); // URL -> Promise<Response>
let totalBytes = 0;
let downloadedBytes = 0;
let isCriticalDone = false;

// Helper to report progress
const reportProgress = async (completed = false) => {
    if (completed) console.log("🎉 Completed");
    const clients = await self.clients.matchAll({ includeUncontrolled: true });
    clients.forEach(client => {
        client.postMessage({
            type: 'loading_progress',
            current: downloadedBytes,
            total: totalBytes,
            completed: completed,
            criticalCompleted: isCriticalDone
        });
    });
};

// Helper to check if critical assets are already in cache (for SW restarts)
const checkCriticalAssets = async () => {
    if (isCriticalDone) return true;
    try {
        const cache = await caches.open(CACHE_NAME);
        const critical = Object.entries(HASH_TO_INFO).filter(([, info]) => info && info.strategy === 'CRITICAL');

        // If no critical assets, we are done
        if (critical.length === 0) {
            isCriticalDone = true;
            return true;
        }

        // Check if all exist in cache
        const allCached = await Promise.all(critical.map(async ([hash]) => {
            const req = toHashRequest(hash);
            const match = await cache.match(req);
            return !!match;
        }));

        if (allCached.every(Boolean)) {
            isCriticalDone = true;
            return true;
        }
    } catch (e) {
        console.error("Error checking critical assets", e);
    }
    return false;
};

// Handle client messages
self.addEventListener('message', event => {
    if (event.data && event.data.type === 'check_status') {
        (async () => {
            if (!isCriticalDone) {
                await checkCriticalAssets();
            }
            event.source.postMessage({
                type: 'status',
                criticalCompleted: isCriticalDone
            });
        })();
    }
});

// Helper to fetch with progress (Streaming)
const fetchWithProgress = async (path, info) => {
    // Resolve manifest path to absolute URL relative to scope
    const request = toScopedRequest(path);
    const response = await fetch(request, { cache: "no-cache" });
    if (!response.ok) return response;

    const reader = response.body.getReader();
    const contentLength = +response.headers.get('Content-Length');
    const encoding = response.headers.get('Content-Encoding');

    // Dynamic Total Adjustment
    // We initially assumed a size (info.assumedSize). Now we know better.
    let actualSize = 0;
    if (contentLength > 0) {
        actualSize = contentLength;
    } else {
        // If no Content-Length, guess based on encoding
        if (encoding && encoding.includes('gzip') && info.gzipSize) actualSize = info.gzipSize;
        else if (info.size) actualSize = info.size;
    }

    if (actualSize > 0 && info.assumedSize !== actualSize) {
        const diff = actualSize - (info.assumedSize || 0);
        totalBytes += diff;
        info.assumedSize = actualSize; // Update assumed size to avoid double counting if called again (unlikely for same object instance but good practice)
        reportProgress();
    }

    const stream = new ReadableStream({
        async start(controller) {
            while (true) {
                // Simulate slow network for testing
                // if (true) await new Promise(r => setTimeout(r, 500));

                const { done, value } = await reader.read();
                if (done) {
                    controller.close();
                    break;
                }
                downloadedBytes += value.length;
                if (downloadedBytes > totalBytes) totalBytes = downloadedBytes;
                controller.enqueue(value);
                reportProgress();
            }
        }
    });

    return new Response(stream, {
        status: response.status,
        statusText: response.statusText,
        headers: response.headers
    });
};

// Helper to get or fetch (Deduplication)
const getOrFetch = (url, info) => {
    if (activeDownloads.has(url)) {
        return activeDownloads.get(url).then(res => res.clone());
    }
    const promise = fetchWithProgress(url, info).then(res => {
        // We don't remove from map immediately, we want to share the response
        // But Response can only be cloned? Yes.
        return res;
    });
    activeDownloads.set(url, promise);
    // Cleanup map when done? No, we might need to clone again? 
    // Actually, once consumed/cancelled, it's done. 
    // But for simplicity, we keep it during the session or cleanup later.
    // Better: cleanup when stream is done? Hard to hook.
    // For now, simple map is fine for the install phase.
    return promise.then(res => res.clone());
};

// Install: Fast Install + Background Prefetch
self.addEventListener("install", event => {
    console.log("PWA install event");

    // 1. Fast Install: Cache ONLY index.html and manifest
    const installPromise = (async () => {
        const cache = await caches.open(CACHE_NAME);
        await cache.addAll(['index.html', 'pwa-manifest.json']);
        await self.skipWaiting();
    })();

    event.waitUntil(installPromise);

    // 2. Background Prefetch (Fire and Forget)
    (async () => {
        await installPromise; // Wait for fast install to finish

        const cache = await caches.open(CACHE_NAME);
        const assetsToCache = Object.entries(HASH_TO_INFO).filter(([, info]) => info && (info.preCache === true || info.strategy));

        // Calculate total bytes (Initial Guess)
        // Default to gzipSize if available (optimistic for production), else size
        assetsToCache.forEach(([, info]) => {
            const size = info.gzipSize || info.size || 0;
            info.assumedSize = size; // Store what we assumed so we can correct it later
            totalBytes += size;
        });
        if (totalBytes === 0) totalBytes = assetsToCache.length * 10000;

        await reportProgress();

        // Split into Critical and Background
        const critical = assetsToCache.filter(([, info]) => info.strategy === 'CRITICAL');
        const background = assetsToCache.filter(([, info]) => info.strategy !== 'CRITICAL');

        // Helper to process a list
        const processList = async (list) => {
            await Promise.all(list.map(async ([hash, info]) => {
                const req = toHashRequest(hash);
                const existing = await cache.match(req);
                if (!existing) {
                    try {
                        const resp = await getOrFetch(info.path, info);
                        if (resp && resp.ok) await cache.put(req, resp);
                    } catch (e) {
                        console.error("Failed to fetch " + info.path, e);
                    }
                } else {
                    // If existing, we count it as downloaded. 
                    // But wait, we added it to totalBytes. So we must add to downloadedBytes too.
                    // Use the same assumed size logic to keep progress consistent.
                    const size = info.assumedSize || 0;
                    downloadedBytes += size;
                    reportProgress();
                }
            }));
        };

        // 2a. Download Critical
        await processList(critical);
        isCriticalDone = true;
        reportProgress(); // Will send criticalCompleted: true

        // 2b. Download Background
        await processList(background);

        await reportProgress(true);
    })();
});

// Activate: remove stale hash entries
self.addEventListener("activate", event => {
    console.log("PWA activate event");
    event.waitUntil((async () => {
        // Better to disable navigation preload for now
        if (self.registration.navigationPreload) {
            await self.registration.navigationPreload.disable();
        }

        const keys = await caches.keys();
        await Promise.all(keys.map(async key => {
            if (key === CACHE_NAME) {
                const cache = await caches.open(key);
                const requests = await cache.keys();
                const validHashes = new Set(Object.keys(HASH_TO_INFO));
                await Promise.all(requests.map(req => {
                    const url = new URL(req.url);
                    // Check if it's a hash-based entry (e.g., from toHashRequest)
                    const scopePath = getScopePathname();
                    const path = url.pathname || "";
                    const suffix = path.startsWith(scopePath) ? path.substring(scopePath.length) : null;
                    const isHex64 = suffix && /^[a-f0-9]{64}$/i.test(suffix);
                    const hash = isHex64 ? suffix : null;

                    if (hash && !validHashes.has(hash)) {
                        return cache.delete(req);
                    }
                    // Also delete old index.html entries if they are not the current one
                    if ((url.pathname === scopePath + "index.html" || url.pathname === scopePath) && !validHashes.has(PATH_TO_HASH["/index.html"])) {
                        return cache.delete(req);
                    }
                }));
            } else {
                await caches.delete(key);
            }
        }));
        await self.clients.claim();
    })());
});

// Fetch: Serve from cache or network (with progress)
self.addEventListener("fetch", event => {
    if (event.request.method !== 'GET') return;

    const url = new URL(event.request.url);
    const sameOrigin = url.origin === self.location.origin;
    const manifestPath = toManifestPathFromRequest(event.request);

    // 1. Index.html & GWT Entry Point (.nocache.js) Update Check (Network First)
    if (sameOrigin && (manifestPath === "/" || manifestPath === "/index.html" || manifestPath.endsWith(".nocache.js"))) {
        event.respondWith((async () => {
            try {
                const networkResponse = await fetch(event.request, { cache: "no-cache" });
                if (networkResponse && networkResponse.ok) {
                    // Inspect the fetched index.html to detect version changes via mavenBuildTimestamp meta
                    if (manifestPath === "/" || manifestPath === "/index.html") {
                        try {
                            const clonedForMeta = networkResponse.clone();
                            const text = await clonedForMeta.text();
                            const match = text.match(/<meta\s+name=["']mavenBuildTimestamp["']\s+content=["']([^"']+)["']\s*\/?>(?:\s*<\/meta>)?/i);
                            if (match && typeof MAVEN_BUILD_TIMESTAMP !== "undefined") {
                                const fetchedTs = match[1];
                                if (fetchedTs !== MAVEN_BUILD_TIMESTAMP) {
                                    console.log("🔆🔆🔆🔆🔆 Detected index.html version change: fetched=" + fetchedTs + ", build=" + MAVEN_BUILD_TIMESTAMP);
                                    if (self.registration && self.registration.update) {
                                        self.registration.update().catch(() => { });
                                    }
                                } else {
                                    console.log("✳️✳️✳️✳️✳️ index.html version still matches " + MAVEN_BUILD_TIMESTAMP);
                                }
                            }
                        } catch (eMeta) { }
                    }

                    // Update cache for offline support
                    try {
                        const cache = await caches.open(CACHE_NAME);
                        await cache.put(event.request, networkResponse.clone());
                    } catch (eCache) { }

                    return networkResponse;
                }
            } catch (eNet) { }

            // Fallback to cache
            // For index.html, we check specific keys. For nocache.js, we check request or hash.
            let cached = await caches.match(event.request);
            if (cached) return cached;

            if (manifestPath === "/" || manifestPath === "/index.html") {
                const h = PATH_TO_HASH["/index.html"] || PATH_TO_HASH["/"];
                if (h) {
                    const resp = await caches.match(toHashRequest(h));
                    if (resp) return resp;
                }
                return (await caches.match(toScopedRequest("/index.html"))) || (await caches.match(toScopedRequest("/")));
            }

            // For nocache.js, try hash fallback
            const knownHash = PATH_TO_HASH[manifestPath];
            if (knownHash) {
                return await caches.match(toHashRequest(knownHash));
            }
            return null;
        })());
        return;
    }

    // 2. General Asset Handling
    event.respondWith((async () => {
        // A) Try cache first (exact match)
        let cachedResponse = await caches.match(event.request);
        if (cachedResponse) return cachedResponse;

        // B) Try cache by hash (if known asset)
        if (sameOrigin) {
            const knownHash = PATH_TO_HASH[manifestPath];
            if (knownHash) {
                cachedResponse = await caches.match(toHashRequest(knownHash));
                if (cachedResponse) return cachedResponse;

                // C) Not in cache? Check if we are downloading it (Streaming/Deduplication)
                const info = HASH_TO_INFO[knownHash];
                if (info) {
                    // This taps into the ongoing download or starts a new one with progress
                    try {
                        const res = await getOrFetch(info.path, info);
                        if (res) return res;
                    } catch (e) {
                        // Ignore network errors (e.g. offline) and proceed to fallbacks
                    }
                }
            }
        }

        // D) Navigation Fallback (SPA)
        const acceptsHtml = (() => {
            try { return (event.request.headers.get("accept") || "").includes("text/html"); } catch { return false; }
        })();
        const isNavLike = (event.request.mode === "navigate")
            || (sameOrigin && (event.request.destination === "document"))
            || (sameOrigin && event.request.method === "GET" && acceptsHtml);

        if (isNavLike) {
            const candidates = ["/", "/index.html"];
            for (const p of candidates) {
                const h = PATH_TO_HASH[p];
                let resp = h ? await caches.match(toHashRequest(h)) : null;
                if (!resp) resp = await caches.match(toScopedRequest(p));
                if (resp) return resp;
            }
        }

        // E) Network Fallback
        try {
            const networkResponse = await fetchWithRetry(event.request);
            // Lazy caching logic (if needed for non-essential assets)
            // ... (omitted for simplicity, focusing on essential assets)
            return networkResponse;
        } catch (e) {
            if (isNavLike) {
                const fallback = (await caches.match(toScopedRequest("/index.html"))) || (await caches.match(toScopedRequest("/")));
                if (fallback) return fallback;
            }
            throw e;
        }
    })());
});

// Helper: fetch with a fallback retry using a reconstructed Request and no-store cache mode
async function fetchWithRetry(request) {
    try {
        return await fetch(request);
    } catch (e1) {
        try {
            const init = {
                method: request.method,
                headers: request.headers,
                mode: request.mode,
                credentials: request.credentials,
                cache: "no-store",
                redirect: "follow",
                integrity: request.integrity,
                referrer: request.referrer,
                referrerPolicy: request.referrerPolicy,
                keepalive: request.method === "GET" ? true : undefined,
                signal: request.signal
            };
            const retryReq = new Request(request.url, init);
            return await fetch(retryReq);
        } catch (e2) {
            throw e1;
        }
    }
}

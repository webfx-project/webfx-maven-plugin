console.log("Switching off PWA mode => running kill-switch script");

self.addEventListener("install", () => {
    self.skipWaiting();
});

self.addEventListener("activate", event => {
    event.waitUntil(
        (async () => {
            await self.registration.unregister();
            const cacheNames = await caches.keys();
            await Promise.all(cacheNames.map(name => caches.delete(name)));
        })()
    );
});

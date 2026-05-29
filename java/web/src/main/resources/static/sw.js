// Strapontin — service worker

const CACHE_NOM = "strapontin-v11";
const RESSOURCES_STATIQUES = [
  "/offline.html",
  "/css/base.css",
  "/css/layout.css",
  "/css/components.css",
  "/css/pages.css",
  "/js/app.js",
  "/images/favicon.png",
  "/images/icon-192.png",
  "/images/icon-512.png",
  "/images/logo.png",
  "/icons/lucide-sprite.svg",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE_NOM)
      .then((cache) => cache.addAll(RESSOURCES_STATIQUES))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((noms) =>
        Promise.all(
          noms.filter((n) => n !== CACHE_NOM).map((n) => caches.delete(n)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  const url = new URL(request.url);

  if (request.method !== "GET") return;
  if (url.pathname.startsWith("/api/")) return;

  // JS et CSS : network-first (toujours la version fraîche, cache en fallback offline)
  if (url.pathname.startsWith("/css/") || url.pathname.startsWith("/js/")) {
    event.respondWith(
      fetch(request)
        .then(function (response) {
          if (response.ok) {
            caches.open(CACHE_NOM).then(function (cache) {
              cache.put(request, response.clone());
            });
          }
          return response;
        })
        .catch(function () {
          return caches.match(request);
        }),
    );
    return;
  }

  // Images et icônes : stale-while-revalidate (réponse cache immédiate + mise à jour en fond)
  if (
    url.pathname.startsWith("/images/") ||
    url.pathname.startsWith("/icons/")
  ) {
    event.respondWith(
      caches.open(CACHE_NOM).then(function (cache) {
        return cache.match(request).then(function (cached) {
          var networkFetch = fetch(request).then(function (response) {
            if (response.ok) cache.put(request, response.clone());
            return response;
          });
          return cached || networkFetch;
        });
      }),
    );
    return;
  }

  // Pages HTML : réseau en priorité, page offline si indisponible
  if (request.headers.get("accept")?.includes("text/html")) {
    event.respondWith(
      fetch(request).catch(() => caches.match("/offline.html")),
    );
  }
});

self.addEventListener("push", (event) => {
  if (!event.data) return;
  let data;
  try {
    data = event.data.json();
  } catch (_) {
    return;
  }
  event.waitUntil(
    self.registration.showNotification(data.title || "Strapontin", {
      body: data.body || "",
      icon: data.icon || "/images/icon-192.png",
      badge: "/images/icon-192.png",
      data: { url: data.url || "/" },
    }),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const url = event.notification.data?.url || "/";
  event.waitUntil(
    clients
      .matchAll({ type: "window", includeUncontrolled: true })
      .then((liste) => {
        for (const client of liste) {
          if (client.url === url && "focus" in client) return client.focus();
        }
        if (clients.openWindow) return clients.openWindow(url);
      }),
  );
});

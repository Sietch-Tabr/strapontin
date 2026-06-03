// Strapontin — service worker

const CACHE_NOM = "strapontin-v19";
// CSS et JS exclus : fingerprinting Spring → URL avec hash, précachage inutile.
// Ils sont gérés en network-first dans le handler fetch.
const RESSOURCES_STATIQUES = [
  "/fonts/geist-variable.woff2",
  "/webjars/htmx.org/2.0.6/dist/htmx.min.js",
  "/images/favicon.png",
  "/images/icon-192.png",
  "/images/icon-512.png",
  "/images/logo.webp",
  "/icons/lucide-sprite.svg",
  "/offline.html",
];

self.addEventListener("error", function (e) {
  self.clients
    .matchAll({ includeUncontrolled: true })
    .then(function (clients) {
      clients.forEach(function (c) {
        c.postMessage({ type: "sw-error", message: e.message || "erreur SW" });
      });
    });
});

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE_NOM)
      .then((cache) => cache.addAll(RESSOURCES_STATIQUES))
      .then(() => self.skipWaiting())
      .catch(function (e) {
        self.clients
          .matchAll({ includeUncontrolled: true })
          .then(function (clients) {
            clients.forEach(function (c) {
              c.postMessage({
                type: "sw-error",
                message: "install: " + (e && e.message ? e.message : "échec précachage"),
              });
            });
          });
        throw e;
      }),
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
      .then(() => {
        if (self.registration.navigationPreload) {
          return self.registration.navigationPreload.enable();
        }
      })
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

  // Navigation browser : NavigationPreload + timeout 6 s, offline.html depuis le cache sinon
  if (request.mode === "navigate") {
    event.respondWith(
      (async () => {
        try {
          const preload = await event.preloadResponse;
          if (preload) return preload;
        } catch (_) {
          // preload indisponible, on continue avec fetch normal
        }
        const minuterie = new Promise((_, reject) =>
          setTimeout(() => reject(new Error("timeout")), 6000),
        );
        return Promise.race([fetch(request), minuterie]).catch(() =>
          caches.open(CACHE_NOM).then((c) => c.match("/offline.html")),
        );
      })(),
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
  var count = typeof data.count === "number" ? data.count : 0;
  var taches = [
    self.registration.showNotification(data.title || "Strapontin", {
      body: data.body || "",
      icon: data.icon || "/images/icon-192.png",
      badge: "/images/icon-192.png",
      data: { url: data.url || "/" },
    }),
  ];
  if ("setAppBadge" in self.registration && count > 0) {
    taches.push(self.registration.setAppBadge(count));
  }
  event.waitUntil(Promise.all(taches));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const url = event.notification.data?.url || "/";
  event.waitUntil(
    clients
      .matchAll({ type: "window", includeUncontrolled: true })
      .then((liste) => {
        for (const client of liste) {
          if ("focus" in client) {
            return client
              .focus()
              .then(() => client.postMessage({ type: "nav-ancre", url }));
          }
        }
        if (clients.openWindow) return clients.openWindow(url);
      }),
  );
});

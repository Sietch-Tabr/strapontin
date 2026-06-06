// Strapontin — service worker

const CACHE_NOM = "strapontin-v25";
// CSS et JS exclus : fingerprinting Spring → URL avec hash, précachage inutile.
// Ils sont gérés en network-first dans le handler fetch.
const RESSOURCES_STATIQUES = [
  "/fonts/geist-variable.woff2",
  "/images/favicon.png",
  "/images/icon-192.png",
  "/images/icon-512.png",
  "/images/logo.webp",
  "/icons/lucide-sprite.svg",
];

// Page offline inlinée pour éviter les problèmes iOS PWA :
// - pas de CSP serveur (headers mis en cache), pas de CSS externe, pas de race condition.
// Le thème est lu depuis le cookie de la requête de navigation.
function reponseOffline(request) {
  const cookie = (request && request.headers.get("cookie")) || "";
  const m = cookie.match(/(?:^|; )theme=([^;]*)/);
  const theme = m ? decodeURIComponent(m[1]) : "auto";
  const classeHtml =
    theme === "sombre"
      ? ' class="dark"'
      : theme === "clair"
        ? ' class="clair"'
        : "";

  const html = `<!doctype html>
<html lang="fr"${classeHtml}>
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <meta name="theme-color" content="#ffffff" media="(prefers-color-scheme: light)" />
    <meta name="theme-color" content="#0a0a0a" media="(prefers-color-scheme: dark)" />
    <link rel="icon" type="image/png" href="/images/favicon.png" />
    <title>Hors ligne — Strapontin</title>
    <style>
      :root{--bg:#ffffff;--fg:#111827;--fg-muted:#6b7280;--border:#e5e7eb;--accent:#f3f4f6;--accent-fg:#111827;--radius:0.5rem}
      @media(prefers-color-scheme:dark){html:not(.clair){--bg:#0a0a0a;--fg:#fafafa;--fg-muted:#a1a1aa;--border:#27272a;--accent:#27272a;--accent-fg:#fafafa}}
      html.dark{--bg:#0a0a0a;--fg:#fafafa;--fg-muted:#a1a1aa;--border:#27272a;--accent:#27272a;--accent-fg:#fafafa}
      *,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
      body{font-family:system-ui,-apple-system,sans-serif;background:var(--bg);color:var(--fg);min-height:100vh}
      .conteneur{display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh;gap:1rem;text-align:center;padding:2rem}
      h1{font-size:1.25rem;font-weight:600}
      p{color:var(--fg-muted);max-width:24rem}
      .btn{display:inline-flex;align-items:center;justify-content:center;padding:0.5rem 1rem;border-radius:var(--radius);font-size:0.875rem;font-weight:500;border:1px solid var(--border);background:var(--accent);color:var(--accent-fg);cursor:pointer;transition:opacity 0.15s}
      .btn:hover{opacity:0.85}
      .btn:disabled{opacity:0.5;cursor:not-allowed}
    </style>
  </head>
  <body>
    <div class="conteneur">
      <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" style="color:var(--fg-muted)" aria-hidden="true">
        <line x1="1" y1="1" x2="23" y2="23"/>
        <path d="M16.72 11.06A10.94 10.94 0 0 1 19 12.55"/>
        <path d="M5 12.55a10.94 10.94 0 0 1 5.17-2.39"/>
        <path d="M10.71 5.05A16 16 0 0 1 22.56 9"/>
        <path d="M1.42 9a15.91 15.91 0 0 1 4.7-2.88"/>
        <path d="M8.53 16.11a6 6 0 0 1 6.95 0"/>
        <line x1="12" y1="20" x2="12.01" y2="20"/>
      </svg>
      <h1>Vous êtes hors ligne</h1>
      <p>Reconnectez-vous à Internet pour accéder à vos concerts et à votre liste de suivi.</p>
      <p id="msg-reconnexion" style="display:none"></p>
      <button id="btn-reessayer" class="btn">Réessayer</button>
    </div>
    <script>
      function afficherMsg(t){var e=document.getElementById("msg-reconnexion");if(!e)return;e.textContent=t;e.style.display=""}
      function reessayer(){
        var btn=document.getElementById("btn-reessayer");
        if(btn)btn.disabled=true;
        afficherMsg("Vérification de la connexion…");
        fetch("/manifest.json",{method:"HEAD",cache:"no-store"})
          .then(function(){afficherMsg("Connexion rétablie, rechargement…");window.location.href="/?t="+Date.now()})
          .catch(function(){afficherMsg("Toujours hors ligne — réessayez dans un instant.");if(btn)btn.disabled=false});
      }
      document.getElementById("btn-reessayer").addEventListener("click",reessayer);
      window.addEventListener("online",function(){setTimeout(reessayer,400)});
    <\/script>
  </body>
</html>`;

  return new Response(html, {
    headers: { "Content-Type": "text/html;charset=utf-8" },
  });
}

self.addEventListener("error", function (e) {
  self.clients.matchAll({ includeUncontrolled: true }).then(function (clients) {
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
                message:
                  "install: " +
                  (e && e.message ? e.message : "échec précachage"),
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
  // /assets/ couvre les bundles Vite (noms hashés), /css/ et /js/ les assets legacy.
  if (
    url.pathname.startsWith("/css/") ||
    url.pathname.startsWith("/js/") ||
    url.pathname.startsWith("/assets/")
  ) {
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

  // Icônes (sprites SVG) : network-first — mise à jour immédiate, offline via cache
  if (url.pathname.startsWith("/icons/")) {
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

  // Images : stale-while-revalidate (changent rarement)
  if (url.pathname.startsWith("/images/")) {
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

  // Navigation browser : NavigationPreload + timeout 6 s, page offline inlinée sinon
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
          reponseOffline(request),
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
      tag: "strapontin-concerts",
      renotify: count > 0,
      data: { url: data.url || "/" },
      actions: [{ action: "voir", title: "Voir les concerts" }],
    }),
  ];
  if ("setAppBadge" in self.registration && count > 0) {
    taches.push(self.registration.setAppBadge(count));
  }
  event.waitUntil(Promise.all(taches));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const url =
    event.action === "voir"
      ? "/spectacles"
      : event.notification.data?.url || "/";
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

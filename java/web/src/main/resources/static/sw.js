// Strapontin — service worker

const CACHE_NOM = "strapontin-v16";
const RESSOURCES_STATIQUES = [
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

function pageOffline(theme) {
  const sombre = theme === "sombre";
  const clair = theme === "clair";
  const classeHtml = sombre ? "dark" : clair ? "clair" : "";
  return `<!doctype html>
<html lang="fr"${classeHtml ? ' class="' + classeHtml + '"' : ""}>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#ffffff" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#0a0a0a" media="(prefers-color-scheme: dark)">
<title>Hors ligne — Strapontin</title>
<style>
:root{--bg:#ffffff;--fg:#111827;--fg-muted:#6b7280;--border:#e5e7eb;--accent:#f3f4f6;--accent-fg:#111827;--radius:.5rem}
@media(prefers-color-scheme:dark){html:not(.clair){--bg:#0a0a0a;--fg:#fafafa;--fg-muted:#a1a1aa;--border:#27272a;--accent:#27272a;--accent-fg:#fafafa}}
html.dark{--bg:#0a0a0a;--fg:#fafafa;--fg-muted:#a1a1aa;--border:#27272a;--accent:#27272a;--accent-fg:#fafafa}
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,-apple-system,sans-serif;background:var(--bg);color:var(--fg);min-height:100vh}
.c{display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh;gap:1rem;text-align:center;padding:2rem}
h1{font-size:1.25rem;font-weight:600}
p{color:var(--fg-muted);max-width:24rem}
button{display:inline-flex;align-items:center;justify-content:center;padding:.5rem 1rem;border-radius:var(--radius);font-size:.875rem;font-weight:500;border:1px solid var(--border);background:var(--accent);color:var(--accent-fg);cursor:pointer;transition:opacity .15s}
button:hover{opacity:.85}
button:disabled{opacity:.5;cursor:not-allowed}
</style>
</head>
<body>
<div class="c">
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
<p id="msg" style="display:none"></p>
<button onclick="reessayer()">Réessayer</button>
</div>
<script>
function reessayer() {
  var btn = document.querySelector("button");
  if (btn) btn.disabled = true;
  var msg = document.getElementById("msg");
  if (msg) { msg.textContent = "Vérification de la connexion…"; msg.style.display = ""; }
  fetch("/manifest.json", { method: "HEAD", cache: "no-store" })
    .then(function() {
      if (msg) msg.textContent = "Connexion rétablie, rechargement…";
      window.location.href = "/?t=" + Date.now();
    })
    .catch(function() {
      if (msg) msg.textContent = "Toujours hors ligne — réessayez dans un instant.";
      if (btn) btn.disabled = false;
    });
}
window.addEventListener("online", function() { setTimeout(reessayer, 400); });
</script>
</body>
</html>`;
}

function themeDepuisCookie(cookieHeader) {
  const m = (cookieHeader || "").match(/(?:^|; )theme=([^;]*)/);
  return m ? decodeURIComponent(m[1]) : "auto";
}

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

  // Pages HTML : réseau en priorité, page offline générée si indisponible
  if (request.headers.get("accept")?.includes("text/html")) {
    event.respondWith(
      fetch(request).catch(() => {
        const theme = themeDepuisCookie(request.headers.get("cookie"));
        return new Response(pageOffline(theme), {
          headers: { "Content-Type": "text/html; charset=utf-8" },
        });
      }),
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
          if (client.url === url && "focus" in client) return client.focus();
        }
        if (clients.openWindow) return clients.openWindow(url);
      }),
  );
});

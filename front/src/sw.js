import { precacheAndRoute, cleanupOutdatedCaches } from "@vite-pwa/workbox-swkit/precaching";

// Nettoie les entrées de précache de l'ancienne version à l'activation.
cleanupOutdatedCaches();

// Précache tous les assets listés dans le manifeste injecté par @unplugin-pwa/vite :
// bundles JS/CSS Vite (fingerprinted), images, icônes.
// eslint-disable-next-line no-undef
precacheAndRoute(self.__WB_MANIFEST);

// Page offline inlinée pour éviter les problèmes iOS PWA :
// pas de CSP serveur (headers mis en cache), pas de CSS externe, pas de race condition.
// Le thème est lu depuis le cookie de la requête de navigation.
function reponseOffline(request) {
  const cookie = (request && request.headers.get("cookie")) || "";
  const m = cookie.match(/(?:^|; )theme=([^;]*)/);
  const theme = m ? decodeURIComponent(m[1]) : "auto";
  const classeHtml =
    theme === "sombre"
      ? ' class="sombre"'
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
      html.sombre{--bg:#0a0a0a;--fg:#fafafa;--fg-muted:#a1a1aa;--border:#27272a;--accent:#27272a;--accent-fg:#fafafa}
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

self.addEventListener("install", () => self.skipWaiting());

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim());
});

// Navigation browser : timeout 6 s, page offline inlinée sinon.
// Les assets précachés (bundles JS/CSS, images, icônes) sont gérés par precacheAndRoute.
//
// NavigationPreload volontairement désactivé (retiré en v2.6.1) : `event.preloadResponse`
// n'est protégée par aucun timeout dans les implémentations navigateur, et son support s'est
// révélé incomplet sur WebKit — la promesse peut ne jamais se résoudre, bloquant la navigation
// indéfiniment (`page.goto` sans réponse, aucune requête réseau visible). Le gain de
// NavigationPreload (paralléliser le fetch avec le démarrage du SW) est négligeable pour une
// SPA de cette taille au regard du risque de blocage total qu'il introduit.
self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET") return;
  if (new URL(event.request.url).pathname.startsWith("/api/")) return;
  if (event.request.mode !== "navigate") return;

  event.respondWith(
    (async () => {
      const minuterie = new Promise((_, reject) =>
        setTimeout(() => reject(new Error("timeout")), 6000),
      );
      return Promise.race([fetch(event.request), minuterie]).catch(() =>
        reponseOffline(event.request),
      );
    })(),
  );
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
  if ("clearAppBadge" in self.registration) {
    self.registration.clearAppBadge();
  }
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

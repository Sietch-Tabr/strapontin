/// <reference types="@sveltejs/kit" />
/// <reference lib="webworker" />

declare const self: ServiceWorkerGlobalScope;

import { build, files, version } from "$service-worker";

const NOM_CACHE = `strapontin-${version}`;
const FICHIERS_CACHE = [...build, ...files];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(NOM_CACHE)
      .then((cache) => cache.addAll(FICHIERS_CACHE))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((cles) =>
        Promise.all(
          cles
            .filter((cle) => cle !== NOM_CACHE)
            .map((cle) => caches.delete(cle)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET") return;

  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) return;

  event.respondWith(
    caches.match(event.request).then((cached) => {
      if (cached) return cached;
      return fetch(event.request);
    }),
  );
});

self.addEventListener("push", (event) => {
  if (!event.data) return;

  let donnees: { title: string; body: string; icon?: string; url?: string };
  try {
    donnees = event.data.json() as typeof donnees;
  } catch {
    return;
  }

  event.waitUntil(
    self.registration.showNotification(donnees.title, {
      body: donnees.body,
      icon: donnees.icon ?? "/icon-192.png",
      badge: "/icon-192.png",
      data: { url: donnees.url ?? "/spectacles" },
    }),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();

  const cible = (event.notification.data as { url?: string }).url ?? "/spectacles";

  event.waitUntil(
    self.clients
      .matchAll({ type: "window", includeUncontrolled: true })
      .then((fenetres) => {
        for (const fenetre of fenetres) {
          if (new URL(fenetre.url).origin === self.location.origin) {
            void fenetre.focus();
            void fenetre.navigate(cible);
            return;
          }
        }
        return self.clients.openWindow(cible);
      }),
  );
});

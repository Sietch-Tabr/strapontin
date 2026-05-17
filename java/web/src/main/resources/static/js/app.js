// Strapontin v2 — JS minimal (thème, push)

// Applique le thème depuis le cookie au chargement pour éviter le FOUC
(function () {
  const theme = document.cookie.match(/(?:^|; )theme=([^;]*)/)?.[1];
  if (theme === "sombre") document.documentElement.classList.add("dark");
  else document.documentElement.classList.remove("dark");
})();

// Service worker + abonnement push
if ('serviceWorker' in navigator) {
  navigator.serviceWorker
    .register('/sw.js')
    .then(async registration => {
      const rep = await fetch('/api/push');
      const { disponible, clePublique } = await rep.json();
      if (!disponible) return;
      if (Notification.permission === 'denied') return;

      const existant = await registration.pushManager.getSubscription();
      if (existant) return;

      const abonnement = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64VersUint8Array(clePublique)
      });

      await fetch('/api/push', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          endpoint: abonnement.endpoint,
          keys: {
            p256dh: arrayBufferVersBase64(abonnement.getKey('p256dh')),
            auth: arrayBufferVersBase64(abonnement.getKey('auth'))
          }
        })
      });
    })
    .catch(() => {});
}

function urlBase64VersUint8Array(base64) {
  const rembourrage = '='.repeat((4 - (base64.length % 4)) % 4);
  const b64 = (base64 + rembourrage).replace(/-/g, '+').replace(/_/g, '/');
  const brut = atob(b64);
  return Uint8Array.from(brut, c => c.charCodeAt(0));
}

function arrayBufferVersBase64(buffer) {
  return btoa(String.fromCharCode(...new Uint8Array(buffer)));
}

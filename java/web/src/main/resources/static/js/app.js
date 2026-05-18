// Strapontin v2 — JS minimal (thème, push)

// Applique le thème depuis le cookie au chargement pour éviter le FOUC
(function () {
  const theme = document.cookie.match(/(?:^|; )theme=([^;]*)/)?.[1] ?? "auto";
  const prefSombre = window.matchMedia("(prefers-color-scheme: dark)");

  function appliquerTheme(t) {
    const sombre = t === "sombre" || (t === "auto" && prefSombre.matches);
    document.documentElement.classList.toggle("dark", sombre);
  }

  appliquerTheme(theme);

  if (theme === "auto") {
    prefSombre.addEventListener("change", () => appliquerTheme("auto"));
  }
})();

// Service worker + abonnement push
if ("serviceWorker" in navigator) {
  navigator.serviceWorker
    .register("/sw.js")
    .then(async (registration) => {
      const rep = await fetch("/api/push");
      const { disponible, clePublique } = await rep.json();
      if (!disponible) return;
      if (Notification.permission === "denied") return;

      const existant = await registration.pushManager.getSubscription();
      if (existant) return;

      const abonnement = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64VersUint8Array(clePublique),
      });

      await fetch("/api/push", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          endpoint: abonnement.endpoint,
          keys: {
            p256dh: arrayBufferVersBase64(abonnement.getKey("p256dh")),
            auth: arrayBufferVersBase64(abonnement.getKey("auth")),
          },
        }),
      });
    })
    .catch(() => {});
}

// Toasts (Popover API — l'attribut est posé par JS pour contourner le filtrage Thymeleaf)
(function () {
  if (!("showPopover" in HTMLElement.prototype)) return;
  const alertes = document.querySelectorAll(".alerte");

  alertes.forEach((el, i) => {
    el.style.setProperty("--indice-toast", i);

    const fermer = document.createElement("button");
    fermer.textContent = "×";
    fermer.setAttribute("aria-label", "Fermer");
    fermer.className = "toast-fermer";
    el.appendChild(fermer);

    el.setAttribute("popover", "manual");
    el.showPopover();

    let delai = 4000;
    if (el.dataset.delai) {
      delai = parseInt(el.dataset.delai, 10);
    } else if (el.querySelector("a")) {
      delai = 15000;
    }

    const minuterie = setTimeout(() => el.hidePopover(), delai);
    fermer.addEventListener("click", () => {
      clearTimeout(minuterie);
      el.hidePopover();
    });
  });
})();

function urlBase64VersUint8Array(base64) {
  const rembourrage = "=".repeat((4 - (base64.length % 4)) % 4);
  const b64 = (base64 + rembourrage).replace(/-/g, "+").replace(/_/g, "/");
  const brut = atob(b64);
  return Uint8Array.from(brut, (c) => c.charCodeAt(0));
}

function arrayBufferVersBase64(buffer) {
  return btoa(String.fromCharCode(...new Uint8Array(buffer)));
}

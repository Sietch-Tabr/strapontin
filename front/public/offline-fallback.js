// #766 : filet de secours hors bundle, jamais bloqué par un échec de chargement du module
// principal (premier lancement PWA hors-ligne avant toute synchronisation — le service worker
// n'a pas encore eu l'occasion de précacher les assets). Sans ce script, un échec réseau sur le
// bundle JS principal laisse #app vide indéfiniment : écran noir sans message.
// Script externe (pas inline) et sans attribut onclick : la Content Security Policy de
// l'application impose script-src 'self', qui bloque tout script/gestionnaire inline sans
// nonce/hash — DOM construit via createElement/addEventListener plutôt que via innerHTML.
(function () {
  function appMonte() {
    var app = document.getElementById("app");
    return !!app && app.childElementCount > 0;
  }

  function creerConteneur() {
    var conteneur = document.createElement("div");
    conteneur.style.cssText =
      "display:flex;flex-direction:column;align-items:center;justify-content:center;" +
      "min-height:100vh;gap:1rem;text-align:center;padding:2rem;" +
      "font-family:system-ui,-apple-system,sans-serif;color:#111827;background:#fff";

    var titre = document.createElement("h1");
    titre.style.cssText = "font-size:1.25rem;font-weight:600;margin:0";
    titre.textContent = "Vous êtes hors ligne";

    var texte = document.createElement("p");
    texte.style.cssText = "color:#6b7280;max-width:24rem;margin:0";
    texte.textContent =
      "Impossible de charger Strapontin. Reconnectez-vous à Internet, puis réessayez.";

    var bouton = document.createElement("button");
    bouton.type = "button";
    bouton.style.cssText =
      "padding:0.5rem 1rem;border-radius:0.5rem;border:1px solid #e5e7eb;" +
      "background:#f3f4f6;color:#111827;cursor:pointer;font-size:0.875rem";
    bouton.textContent = "Réessayer";
    bouton.addEventListener("click", function () {
      location.reload();
    });

    conteneur.appendChild(titre);
    conteneur.appendChild(texte);
    conteneur.appendChild(bouton);
    return conteneur;
  }

  setTimeout(function () {
    if (appMonte()) return;
    var app = document.getElementById("app");
    if (!app) return;
    app.replaceChildren(creerConteneur());
  }, 4000);
})();

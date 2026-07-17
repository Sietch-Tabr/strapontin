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

  // #938 : même lecture du cookie theme que sw.js#reponseOffline, pour éviter un flash blanc
  // en thème sombre sur ce chemin de secours rare (premier lancement PWA hors-ligne).
  function themeSombre() {
    var m = document.cookie.match(/(?:^|; )theme=([^;]*)/);
    var theme = m ? decodeURIComponent(m[1]) : "auto";
    if (theme === "sombre") return true;
    if (theme === "clair") return false;
    return (
      typeof window.matchMedia === "function" &&
      window.matchMedia("(prefers-color-scheme: dark)").matches
    );
  }

  function creerConteneur() {
    var sombre = themeSombre();
    var bg = sombre ? "#0a0a0a" : "#fff";
    var fg = sombre ? "#fafafa" : "#111827";
    var fgMuted = sombre ? "#a1a1aa" : "#6b7280";
    var border = sombre ? "#27272a" : "#e5e7eb";
    var accent = sombre ? "#27272a" : "#f3f4f6";
    var accentFg = sombre ? "#fafafa" : "#111827";

    var conteneur = document.createElement("div");
    conteneur.style.cssText =
      "display:flex;flex-direction:column;align-items:center;justify-content:center;" +
      "min-height:100vh;gap:1rem;text-align:center;padding:2rem;" +
      "font-family:system-ui,-apple-system,sans-serif;color:" +
      fg +
      ";background:" +
      bg;

    var titre = document.createElement("h1");
    titre.style.cssText = "font-size:1.25rem;font-weight:600;margin:0";
    titre.textContent = "Vous êtes hors ligne";

    var texte = document.createElement("p");
    texte.style.cssText = "color:" + fgMuted + ";max-width:24rem;margin:0";
    texte.textContent =
      "Impossible de charger Strapontin. Reconnectez-vous à Internet, puis réessayez.";

    var bouton = document.createElement("button");
    bouton.type = "button";
    bouton.style.cssText =
      "padding:0.5rem 1rem;border-radius:0.5rem;border:1px solid " +
      border +
      ";background:" +
      accent +
      ";color:" +
      accentFg +
      ";cursor:pointer;font-size:0.875rem";
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

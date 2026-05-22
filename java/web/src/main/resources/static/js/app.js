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
      if (document.cookie.match(/(?:^|; )push_opt_out=1/)) return;
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

// Bouton de recherche — activation dynamique selon la longueur du champ
(function () {
  const input =
    document.getElementById("q-artiste") ?? document.getElementById("q-salle");
  if (!input) return;
  const btn = input.closest("form")?.querySelector('button[type="submit"]');
  if (!btn) return;
  const majEtat = () => {
    btn.disabled = input.value.trim().length < 2;
  };
  input.addEventListener("input", majEtat);
  majEtat();
})();

// Réaffichage de concerts masqués (accueil)
document.addEventListener("DOMContentLoaded", function () {
  document.querySelectorAll("[data-reafficher-id]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var id = btn.getAttribute("data-reafficher-id");
      btn.disabled = true;
      fetch("/api/spectacles/matches/" + id, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ masquer: false }),
      })
        .then(function () {
          window.location.reload();
        })
        .catch(function () {
          btn.disabled = false;
        });
    });
  });
});

// Navigation par onglets — repositionnement en haut à chaque changement
// Les sections ont display:none avant d'être ciblées ; on évite le scroll
// natif (comportement aléatoire) en remontant en haut après le repaint.
(function () {
  document
    .querySelectorAll(".param-onglet[href^='#']")
    .forEach(function (lien) {
      lien.addEventListener("click", function (e) {
        e.preventDefault();
        location.hash = lien.getAttribute("href");
        requestAnimationFrame(function () {
          window.scrollTo({ top: 0 });
        });
      });
    });
})();

// Tiroir de navigation mobile
(function () {
  var btnPlus = document.getElementById("btn-nav-plus");
  var tiroir = document.getElementById("tiroir-nav");
  var fond = document.getElementById("tiroir-fond");
  if (!btnPlus || !tiroir || !fond) return;

  function ouvrir() {
    tiroir.classList.add("ouvert");
    fond.classList.add("ouvert");
    btnPlus.setAttribute("aria-expanded", "true");
    tiroir.setAttribute("aria-hidden", "false");
    fond.setAttribute("aria-hidden", "false");
  }

  function fermer() {
    tiroir.classList.remove("ouvert");
    fond.classList.remove("ouvert");
    btnPlus.setAttribute("aria-expanded", "false");
    tiroir.setAttribute("aria-hidden", "true");
    fond.setAttribute("aria-hidden", "true");
  }

  btnPlus.addEventListener("click", function () {
    tiroir.classList.contains("ouvert") ? fermer() : ouvrir();
  });
  fond.addEventListener("click", fermer);
  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape") fermer();
  });
})();

// Modale de signalement (page aide)
(function () {
  var dialog = document.getElementById("dialog-signaler");
  if (!dialog) return;

  var btnOuvrir = document.getElementById("btn-ouvrir-signalement");
  var btnFermer = document.getElementById("btn-fermer-signalement");
  var btnAnnuler = document.getElementById("btn-annuler-signalement");
  var typeSelect = document.getElementById("type-signalement");
  var messageArea = document.getElementById("message-signalement");

  var canevas = {
    bug: "Décrivez le problème\n\nÉtapes pour reproduire :\n1. \n2. \n\nComportement attendu :\n\nComportement observé :",
    suggestion:
      "Décrivez votre suggestion\n\nContexte (pourquoi c'est utile) :\n\nComportement souhaité :",
    question: "Votre question",
  };

  function ouvrir() {
    if (messageArea && !messageArea.value.trim()) {
      messageArea.value = canevas["bug"] || "";
    }
    dialog.showModal();
  }

  function fermer() {
    dialog.close();
  }

  if (btnOuvrir) btnOuvrir.addEventListener("click", ouvrir);
  if (btnFermer) btnFermer.addEventListener("click", fermer);
  if (btnAnnuler) btnAnnuler.addEventListener("click", fermer);

  dialog.addEventListener("click", function (e) {
    if (e.target === dialog) fermer();
  });

  if (typeSelect && messageArea) {
    typeSelect.addEventListener("change", function () {
      messageArea.value = canevas[typeSelect.value] || "";
    });
  }
})();

// Bouton "Relancer les fetches" (admin) — AJAX + polling indicateur visuel
(function () {
  var btn = document.getElementById("btn-relancer");
  if (!btn) return;
  var statut = document.getElementById("relancer-statut");
  var intervalId = null;

  function mettreAJourBadges(jobsEnCours) {
    document.querySelectorAll(".job-en-cours[data-job]").forEach(function (el) {
      el.style.display = "none";
    });
    (jobsEnCours || []).forEach(function (nom) {
      var badge = document.querySelector(
        ".job-en-cours[data-job='" + nom + "']",
      );
      if (badge) badge.style.display = "";
    });
  }

  function arreterPolling() {
    if (intervalId) {
      clearInterval(intervalId);
      intervalId = null;
    }
  }

  function demarrerPolling() {
    arreterPolling();
    intervalId = setInterval(function () {
      fetch("/api/admin/jobs")
        .then(function (rep) {
          return rep.json();
        })
        .then(function (data) {
          var actifs = data.jobsEnCours || [];
          mettreAJourBadges(actifs);
          if (actifs.length === 0) {
            arreterPolling();
            btn.disabled = false;
            if (statut) statut.textContent = "";
            window.location.reload();
          }
        })
        .catch(arreterPolling);
    }, 3000);
  }

  btn.addEventListener("click", function () {
    btn.disabled = true;
    if (statut) statut.textContent = "Fetches en cours…";
    fetch("/api/admin/relancer", { method: "POST" })
      .then(function () {
        demarrerPolling();
      })
      .catch(function () {
        btn.disabled = false;
        if (statut) statut.textContent = "Erreur — worker inaccessible.";
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

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

// Mise à jour de l'onglet actif de la nav mobile lors des navigations Turbo
// (le #bottom-nav est data-turbo-permanent : il n'est pas re-rendu par le serveur)
function majNavActif(chemin) {
  document
    .querySelectorAll("#bottom-nav a[href], #bottom-nav button[data-nav-path]")
    .forEach(function (el) {
      var href = el.getAttribute("href") || el.getAttribute("data-nav-path") || "";
      var actif =
        href === "/"
          ? chemin === "/"
          : chemin === href || chemin.startsWith(href + "/");
      el.classList.toggle("actif", actif);
    });
  var btnPlus = document.getElementById("btn-nav-plus");
  if (btnPlus) {
    var pagesPlus = [
      "/artistes",
      "/salles",
      "/parametres",
      "/admin",
      "/aide",
      "/changelog",
    ];
    btnPlus.classList.toggle(
      "actif",
      pagesPlus.some(function (p) {
        return chemin === p || chemin.startsWith(p + "/");
      })
    );
  }
}

majNavActif(window.location.pathname);
document.addEventListener("turbo:load", function () {
  majNavActif(window.location.pathname);
});

// Réaffichage de concerts masqués (accueil)
document.addEventListener("turbo:load", function () {
  document.querySelectorAll("[data-reafficher-id]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      const id = btn.getAttribute("data-reafficher-id");
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

// Toggle filtres spectacles (mobile)
(function () {
  const btn = document.getElementById("btn-filtres");
  const form = document.getElementById("filtres-form");
  if (!btn || !form) return;

  const NOMS_FILTRES = ["q", "mois", "genre", "classification", "budget"];

  function compterActifs() {
    return NOMS_FILTRES.reduce(function (n, nom) {
      const el = form.querySelector("[name=" + nom + "]");
      return n + (el && el.value !== "" ? 1 : 0);
    }, 0);
  }

  function mettreAJourLabel(nb) {
    const label = document.getElementById("filtres-toggle-label");
    if (label) label.textContent = nb > 0 ? "Filtres · " + nb : "Filtres";
  }

  const nb = compterActifs();
  mettreAJourLabel(nb);

  // Ouvrir automatiquement si des filtres sont actifs
  if (nb > 0) {
    form.classList.add("visible");
    btn.setAttribute("aria-expanded", "true");
  }

  btn.addEventListener("click", function () {
    const ouvert = form.classList.toggle("visible");
    btn.setAttribute("aria-expanded", String(ouvert));
  });
})();

// Select natif — onglets paramètres/admin sur mobile
(function () {
  const select = document.querySelector(".param-onglets-mobile");
  if (!select) return;

  function syncSelect() {
    const hash = location.hash;
    if (hash && select.querySelector('option[value="' + hash + '"]')) {
      select.value = hash;
    } else {
      select.selectedIndex = 0;
    }
  }

  syncSelect();
  window.addEventListener("hashchange", syncSelect);

  select.addEventListener("change", function () {
    location.hash = select.value;
    requestAnimationFrame(function () {
      window.scrollTo({ top: 0 });
    });
  });
})();

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

// Drawer de navigation mobile (dialog natif)
(function () {
  const btnPlus = document.getElementById("btn-nav-plus");
  const dialog = document.getElementById("dialog-nav-plus");
  if (!btnPlus || !dialog) return;

  function ouvrir() {
    dialog.showModal();
    btnPlus.setAttribute("aria-expanded", "true");
  }

  function fermer() {
    dialog.close();
  }

  btnPlus.addEventListener("click", function () {
    dialog.open ? fermer() : ouvrir();
  });

  // Clic sur le backdrop (clic sur le <dialog> lui-même, hors contenu)
  dialog.addEventListener("click", function (e) {
    if (e.target === dialog) fermer();
  });

  // Sync aria-expanded à la fermeture (Echap natif inclus)
  dialog.addEventListener("close", function () {
    btnPlus.setAttribute("aria-expanded", "false");
  });

  // Swipe vers le bas pour fermer
  let touchDepart = 0;
  dialog.addEventListener(
    "touchstart",
    function (e) {
      touchDepart = e.touches[0].clientY;
    },
    { passive: true },
  );
  dialog.addEventListener(
    "touchend",
    function (e) {
      if (e.changedTouches[0].clientY - touchDepart > 80) fermer();
    },
    { passive: true },
  );
})();

// Modale de signalement (page aide)
(function () {
  const dialog = document.getElementById("dialog-signaler");
  if (!dialog) return;

  const btnOuvrir = document.getElementById("btn-ouvrir-signalement");
  const btnFermer = document.getElementById("btn-fermer-signalement");
  const btnAnnuler = document.getElementById("btn-annuler-signalement");
  const typeSelect = document.getElementById("type-signalement");
  const messageArea = document.getElementById("message-signalement");

  const canevas = {
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
  const btn = document.getElementById("btn-relancer");
  if (!btn) return;
  const statut = document.getElementById("relancer-statut");
  let intervalId = null;

  function mettreAJourBadges(jobsEnCours) {
    document.querySelectorAll(".job-en-cours[data-job]").forEach(function (el) {
      el.style.display = "none";
    });
    (jobsEnCours || []).forEach(function (nom) {
      const badge = document.querySelector(
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
          const actifs = data.jobsEnCours || [];
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

// Logs admin — filtre multi-niveaux avec warn+error actifs par défaut
(function () {
  const table = document.getElementById("table-logs");
  const filtreSource = document.getElementById("filtre-source");
  const filtresActifs = new Set(["warn", "error"]);
  const btnNiveaux = document.querySelectorAll(".log-filtre-btn");
  const compte = document.getElementById("log-compte");

  if (!btnNiveaux.length) return;

  if (table) {
    table.querySelectorAll("tbody tr[data-meta]").forEach(function (tr) {
      const metaStr = tr.getAttribute("data-meta");
      if (!metaStr) return;
      try {
        const meta = JSON.parse(metaStr);
        const cles = Object.keys(meta);
        if (!cles.length) return;
        const msgTd = tr.querySelector("td.log-message");
        if (!msgTd) return;
        const metaDiv = document.createElement("div");
        metaDiv.className = "log-meta";
        cles.forEach(function (k) {
          const item = document.createElement("span");
          item.className = "log-meta-item";
          item.textContent = k + ": " + meta[k];
          metaDiv.appendChild(item);
        });
        msgTd.appendChild(metaDiv);
      } catch (_) {}
    });
  }

  function mettreAJourBoutonsLogs() {
    btnNiveaux.forEach(function (b) {
      const actif = filtresActifs.has(b.getAttribute("data-niveau"));
      b.classList.toggle("actif", actif);
      b.setAttribute("aria-pressed", String(actif));
    });
  }

  function filtrerLogs() {
    if (!table) return;
    const lignes = table.querySelectorAll("tbody tr");
    const source = filtreSource ? filtreSource.value : "";
    const tousActifs = filtresActifs.size === 0;
    let visible = 0;
    lignes.forEach(function (tr) {
      const n = tr.getAttribute("data-niveau") || "";
      const s = tr.getAttribute("data-source") || "";
      const okNiveau = tousActifs || filtresActifs.has(n);
      const okSource = !source || s === source;
      const show = okNiveau && okSource;
      tr.style.display = show ? "" : "none";
      if (show) visible++;
    });
    if (compte) compte.textContent = visible + " / " + lignes.length;
  }

  btnNiveaux.forEach(function (btn) {
    btn.addEventListener("click", function () {
      const niv = btn.getAttribute("data-niveau");
      if (filtresActifs.has(niv)) {
        filtresActifs.delete(niv);
      } else {
        filtresActifs.add(niv);
      }
      mettreAJourBoutonsLogs();
      filtrerLogs();
    });
  });

  if (filtreSource) filtreSource.addEventListener("change", filtrerLogs);
  mettreAJourBoutonsLogs();
  filtrerLogs();
})();

// Modification utilisateur (admin)
(function () {
  const formsModif = document.querySelectorAll(".form-modif-utilisateur");
  if (!formsModif.length) return;

  formsModif.forEach(function (form) {
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      const userId = form.getAttribute("data-user-id");
      const erreurSpan = form.querySelector(".erreur-modif");
      erreurSpan.style.display = "none";
      const corps = {
        nom: form.querySelector("[name=nom]").value.trim(),
        email: form.querySelector("[name=email]").value.trim(),
        role: form.querySelector("[name=role]").value,
      };
      fetch("/api/admin/utilisateurs/" + userId, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(corps),
      })
        .then(function (r) {
          return r.json();
        })
        .then(function (d) {
          if (d.succes) {
            location.reload();
          } else {
            erreurSpan.textContent = d.erreur || "Erreur.";
            erreurSpan.style.display = "";
          }
        })
        .catch(function () {
          erreurSpan.textContent = "Erreur réseau.";
          erreurSpan.style.display = "";
        });
    });
  });
})();

// Modale invitation (admin)
(function () {
  const dialog = document.getElementById("dialog-invitation");
  if (dialog) dialog.showModal();
})();

window.copierLienInvitation = function () {
  const input = document.getElementById("lien-invitation");
  if (!input) return;
  if (navigator.clipboard) {
    navigator.clipboard.writeText(input.value).catch(function () {
      input.select();
      document.execCommand("copy");
    });
  } else {
    input.select();
    document.execCommand("copy");
  }
};

window.toggleModif = function (userId) {
  const row = document.getElementById("modif-" + userId);
  if (row) row.style.display = row.style.display === "none" ? "" : "none";
};

// Push notifications (paramètres)
(function () {
  const erreurDiv = document.getElementById("push-erreur");
  if (!erreurDiv) return;

  const nonSupporteDiv = document.getElementById("push-non-supporte");
  const vapidManquantDiv = document.getElementById("push-vapid-manquant");
  const permRefusee = document.getElementById("push-permission-refusee");
  const abonneDiv = document.getElementById("push-abonne");
  const nonAbonneDiv = document.getElementById("push-non-abonne");

  if (location.protocol !== "https:" && location.hostname !== "localhost") {
    nonSupporteDiv.style.display = "";
    return;
  }
  if (!("serviceWorker" in navigator) || !("PushManager" in window)) {
    nonSupporteDiv.style.display = "";
    return;
  }

  const btnActiver = document.getElementById("btn-activer-push");
  const btnDesactiver = document.getElementById("btn-desactiver-push");
  let pushClePublique = null;
  let pushAbonnement = null;
  let pushEnCours = false;

  function afficherErreur(msg) {
    erreurDiv.textContent = msg;
    erreurDiv.style.display = "";
  }

  function masquerErreur() {
    erreurDiv.style.display = "none";
  }

  function majEtat() {
    permRefusee.style.display = "none";
    abonneDiv.style.display = "none";
    nonAbonneDiv.style.display = "none";
    if (Notification.permission === "denied") {
      permRefusee.style.display = "";
    } else if (pushAbonnement) {
      abonneDiv.style.display = "";
    } else {
      nonAbonneDiv.style.display = "";
    }
  }

  async function chargerEtat() {
    try {
      const results = await Promise.all([
        fetch("/api/push").then(function (r) {
          return r.json();
        }),
        navigator.serviceWorker.ready,
      ]);
      const repVapid = results[0];
      const registration = results[1];
      if (!repVapid.disponible) {
        vapidManquantDiv.style.display = "";
        return;
      }
      pushClePublique = repVapid.clePublique;
      pushAbonnement = await registration.pushManager.getSubscription();
      majEtat();
    } catch (_) {
      nonSupporteDiv.style.display = "";
    }
  }

  btnActiver.addEventListener("click", async function () {
    if (!pushClePublique || pushEnCours) return;
    pushEnCours = true;
    masquerErreur();
    btnActiver.disabled = true;
    btnActiver.textContent = "Activation…";
    try {
      const permission = await Notification.requestPermission();
      if (permission !== "granted") {
        if (permission === "denied") majEtat();
        return;
      }
      const registration = await navigator.serviceWorker.ready;
      const abonnement = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64VersUint8Array(pushClePublique),
      });
      const rep = await fetch("/api/push", {
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
      if (!rep.ok) {
        afficherErreur("Échec enregistrement");
        return;
      }
      pushAbonnement = abonnement;
      document.cookie = "push_opt_out=;path=/;max-age=0;SameSite=Lax";
      majEtat();
    } catch (e) {
      afficherErreur(e.message || "Une erreur est survenue.");
    } finally {
      pushEnCours = false;
      btnActiver.disabled = false;
      btnActiver.textContent = "Activer les notifications push";
    }
  });

  btnDesactiver.addEventListener("click", async function () {
    if (!pushAbonnement || pushEnCours) return;
    pushEnCours = true;
    masquerErreur();
    btnDesactiver.disabled = true;
    btnDesactiver.textContent = "Désactivation…";
    try {
      const endpoint = pushAbonnement.endpoint;
      await pushAbonnement.unsubscribe();
      await fetch("/api/push", {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ endpoint: endpoint }),
      });
      pushAbonnement = null;
      document.cookie = "push_opt_out=1;path=/;max-age=31536000;SameSite=Lax";
      majEtat();
    } catch (e) {
      afficherErreur(e.message || "Une erreur est survenue.");
    } finally {
      pushEnCours = false;
      btnDesactiver.disabled = false;
      btnDesactiver.textContent = "Désactiver les notifications push";
    }
  });

  chargerEtat();
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

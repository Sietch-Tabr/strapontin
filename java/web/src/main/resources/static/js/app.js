// Strapontin v2 — JS minimal (thème, push)

// Applique le thème depuis le cookie au chargement pour éviter le FOUC
window.__prefSombre =
  window.__prefSombre || window.matchMedia("(prefers-color-scheme: dark)");

function appliquerTheme(t) {
  const sombre =
    t === "sombre" || (t === "auto" && window.__prefSombre.matches);
  document.documentElement.classList.toggle("dark", sombre);
}

(function () {
  const theme = document.cookie.match(/(?:^|; )theme=([^;]*)/)?.[1] ?? "auto";
  try {
    localStorage.setItem("strapontin-theme", theme);
  } catch (_) {}
  appliquerTheme(theme);
  if (theme === "auto") {
    window.__prefSombre.addEventListener("change", () =>
      appliquerTheme("auto"),
    );
  }
})();

// Soumission automatique des champs marqués js-auto-submit
document.addEventListener("change", function (e) {
  if (e.target.classList.contains("js-auto-submit")) {
    e.target.form.submit();
  }
});

// Aperçu immédiat du thème au clic sur les boutons de thème
document.addEventListener("click", function (e) {
  const btn = e.target.closest(".btn-theme");
  if (!btn) return;
  const valeur = btn.value;
  document.cookie = "theme=" + valeur + "; path=/; max-age=" + 365 * 24 * 3600;
  try {
    localStorage.setItem("strapontin-theme", valeur);
  } catch (_) {}
  appliquerTheme(valeur);
});

// Service worker + abonnement push
if ("serviceWorker" in navigator && "PushManager" in window) {
  navigator.serviceWorker
    .register("/sw.js")
    .then(async (registration) => {
      if (!document.cookie.match(/(?:^|; )push_opt_in=1/)) return;
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
    .catch(function (e) {
      window.signalerErreur &&
        window.signalerErreur({
          message: "sw.register: " + (e && e.message ? e.message : "échec"),
          type: "js-error",
          url: "/sw.js",
        });
    });
}

// Toasts (Popover API — l'attribut est posé par JS pour contourner le filtrage Thymeleaf)
function delaiParType(el) {
  if (el.dataset.delai) return parseInt(el.dataset.delai, 10);
  if (el.classList.contains("alerte-erreur")) return 0;
  if (el.classList.contains("alerte-avertissement")) return 10000;
  if (el.querySelector("a")) return 15000;
  return 5000;
}

function initToasts() {
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

    const delai = delaiParType(el);
    const minuterie =
      delai > 0 ? setTimeout(() => el.hidePopover(), delai) : null;
    fermer.addEventListener("click", () => {
      if (minuterie) clearTimeout(minuterie);
      el.hidePopover();
    });
  });
}

function afficherToast(message, type) {
  if (!("showPopover" in HTMLElement.prototype)) return;
  const el = document.createElement("div");
  el.className = "alerte alerte-" + type;
  el.setAttribute("role", type === "erreur" ? "alert" : "status");
  el.setAttribute("aria-live", type === "erreur" ? "assertive" : "polite");
  el.textContent = message;

  const nbExistants = document.querySelectorAll("[popover].alerte").length;
  el.style.setProperty("--indice-toast", nbExistants);

  const fermer = document.createElement("button");
  fermer.textContent = "×";
  fermer.setAttribute("aria-label", "Fermer");
  fermer.className = "toast-fermer";
  el.appendChild(fermer);

  document.body.appendChild(el);
  el.setAttribute("popover", "manual");
  el.showPopover();

  const delai = delaiParType(el);
  const minuterie =
    delai > 0 ? setTimeout(() => el.hidePopover(), delai) : null;
  fermer.addEventListener("click", () => {
    if (minuterie) clearTimeout(minuterie);
    el.hidePopover();
  });
  el.addEventListener("toggle", (e) => {
    if (e.newState === "closed") el.remove();
  });
}

// Bouton de recherche — activation dynamique selon la longueur du champ
function initBoutonRecherche() {
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
}

function majNavActif(chemin) {
  document
    .querySelectorAll(
      "#bottom-nav a[href], #bottom-nav button[data-nav-path], .sidebar a[href], #dialog-nav-plus a[href]",
    )
    .forEach(function (el) {
      var href =
        el.getAttribute("href") || el.getAttribute("data-nav-path") || "";
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
      }),
    );
  }
}

// Réaffichage de concerts masqués (accueil)
function initReafficher() {
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
          window.signalerErreur &&
            window.signalerErreur({
              message: "reafficher: PATCH échec id=" + id,
              type: "htmx-error",
              url: "/api/spectacles/matches/" + id,
            });
        });
    });
  });
}

// Toggle filtres spectacles (mobile)
function initFiltresToggle() {
  const btn = document.getElementById("btn-filtres");
  const form = document.getElementById("filtres-form");
  if (!btn || !form) return;

  const NOMS_FILTRES = ["q", "mois", "genre", "classification"];

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
}

// Select natif + liens onglets — paramètres/admin, réinitialisé à chaque navigation HTMX.
function initOngletsPage() {
  const sections = document.querySelectorAll(
    ".parametres-section, .admin-section",
  );
  if (!sections.length) return;

  function activerSection(hash) {
    sections.forEach(function (s) {
      s.classList.remove("actif");
    });
    const cible = hash ? document.querySelector(hash) : sections[0];
    if (cible) cible.classList.add("actif");
  }

  const hash = location.hash;
  activerSection(hash || null);

  const select = document.querySelector(".param-onglets-mobile");
  if (select) {
    if (hash && select.querySelector('option[value="' + hash + '"]')) {
      select.value = hash;
    } else {
      select.selectedIndex = 0;
    }
    select.addEventListener("change", function () {
      history.replaceState(null, "", select.value);
      activerSection(select.value);
      window.scrollTo({ top: 0 });
    });
  }

  document
    .querySelectorAll(".param-onglet[href^='#']")
    .forEach(function (lien) {
      lien.addEventListener("click", function (e) {
        e.preventDefault();
        const cibleHash = lien.getAttribute("href");
        history.replaceState(null, "", cibleHash);
        activerSection(cibleHash);
        if (select) select.value = cibleHash;
        window.scrollTo({ top: 0 });
      });
    });
}

// Compteur de longueur MDP + dialog suppression (/parametres)
function initParametres() {
  const champ = document.getElementById("mdp-nouveau");
  const compteur = document.getElementById("compteur-mdp");
  if (champ && compteur) {
    function majCompteurMdp() {
      const n = champ.value.length;
      compteur.textContent =
        n === 0
          ? ""
          : n +
            " caractère" +
            (n > 1 ? "s" : "") +
            (n < 12 ? " (minimum 12)" : "");
    }
    champ.addEventListener("input", majCompteurMdp);
    champ.addEventListener("change", majCompteurMdp);
    majCompteurMdp();
  }

  const btnOuvrir = document.getElementById("btn-ouvrir-suppression");
  const dialog = document.getElementById("dialog-supprimer-compte");
  const btnAnnuler = document.getElementById("btn-annuler-suppression");
  if (btnOuvrir && dialog) {
    btnOuvrir.addEventListener("click", function () {
      const champConfirm = document.getElementById("confirmation-suppression");
      const btnConfirmer = document.getElementById("btn-confirmer-suppression");
      if (champConfirm) champConfirm.value = "";
      if (btnConfirmer) btnConfirmer.disabled = true;
      dialog.showModal();
    });
    if (btnAnnuler) {
      btnAnnuler.addEventListener("click", function () {
        dialog.close();
      });
    }
    const champConfirm = document.getElementById("confirmation-suppression");
    const btnConfirmer = document.getElementById("btn-confirmer-suppression");
    if (champConfirm && btnConfirmer) {
      champConfirm.addEventListener("input", function () {
        btnConfirmer.disabled = champConfirm.value !== "supprimer";
      });
    }
  }

  const selectMode = document.getElementById("import-mode");
  const avertissementEcraser = document.getElementById("avertissement-ecraser");
  if (selectMode && avertissementEcraser) {
    selectMode.addEventListener("change", function () {
      avertissementEcraser.hidden = selectMode.value !== "ecraser";
    });
  }
}

// Drawer de navigation mobile (dialog natif)
function initDrawerNavigation() {
  const btnPlus = document.getElementById("btn-nav-plus");
  const dialog = document.getElementById("dialog-nav-plus");
  if (!btnPlus || !dialog) return;
  if (btnPlus.dataset.drawerInit) return;
  btnPlus.dataset.drawerInit = "1";

  btnPlus.addEventListener("click", function () {
    if (dialog.open) {
      dialog.close();
    } else {
      dialog.showModal();
      btnPlus.setAttribute("aria-expanded", "true");
    }
  });

  dialog.addEventListener("click", function (e) {
    if (e.target === dialog) {
      dialog.close();
      return;
    }
    if (e.target.closest("a[href]")) {
      // Délai d'un tick : laisse HTMX intercepter le click avant la fermeture
      // (Android Chrome annule la propagation si dialog.close() est synchrone)
      setTimeout(function () {
        dialog.close();
      }, 0);
    }
  });

  dialog.addEventListener("close", function () {
    btnPlus.setAttribute("aria-expanded", "false");
  });

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
      if (e.changedTouches[0].clientY - touchDepart > 80) dialog.close();
    },
    { passive: true },
  );
}

// Modale de signalement (page aide)
function initModalSignaler() {
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
      var type = typeSelect ? typeSelect.value : "bug";
      messageArea.value = canevas[type] || canevas["bug"] || "";
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
}

// Confirmation de suppression d'artiste
function initConfirmSupprArtiste() {
  var dialog = document.getElementById("dialog-confirmer-suppr-artiste");
  if (!dialog) return;
  var nomEl = document.getElementById("nom-artiste-a-supprimer");
  var btnConfirmer = document.getElementById("btn-confirmer-suppr-artiste");
  var btnAnnuler = document.getElementById("btn-annuler-suppr-artiste");
  var formEnAttente = null;

  document.querySelectorAll(".js-confirmer-suppr").forEach(function (btn) {
    btn.addEventListener("click", function (e) {
      e.preventDefault();
      formEnAttente = btn.closest("form");
      if (nomEl) nomEl.textContent = btn.getAttribute("data-nom-artiste") || "";
      dialog.showModal();
    });
  });

  if (btnConfirmer)
    btnConfirmer.addEventListener("click", function () {
      dialog.close();
      if (formEnAttente) formEnAttente.submit();
    });
  if (btnAnnuler)
    btnAnnuler.addEventListener("click", function () {
      dialog.close();
    });
  dialog.addEventListener("click", function (e) {
    if (e.target === dialog) dialog.close();
  });
}

// Confirmation de suppression de salle
function initConfirmSupprSalle() {
  var dialog = document.getElementById("dialog-confirmer-suppr-salle");
  if (!dialog) return;
  var nomEl = document.getElementById("nom-salle-a-supprimer");
  var btnConfirmer = document.getElementById("btn-confirmer-suppr-salle");
  var btnAnnuler = document.getElementById("btn-annuler-suppr-salle");
  var formEnAttente = null;

  document
    .querySelectorAll(".js-confirmer-suppr-salle")
    .forEach(function (btn) {
      btn.addEventListener("click", function (e) {
        e.preventDefault();
        formEnAttente = btn.closest("form");
        if (nomEl) nomEl.textContent = btn.getAttribute("data-nom-salle") || "";
        dialog.showModal();
      });
    });

  if (btnConfirmer)
    btnConfirmer.addEventListener("click", function () {
      dialog.close();
      if (formEnAttente) formEnAttente.submit();
    });
  if (btnAnnuler)
    btnAnnuler.addEventListener("click", function () {
      dialog.close();
    });
  dialog.addEventListener("click", function (e) {
    if (e.target === dialog) dialog.close();
  });
}

// Bannière système — auto-dismiss et dismiss manuel via sessionStorage
function initBanniere() {
  const el = document.getElementById("banniere");
  if (!el) return;

  const id = el.dataset.banniereId;
  const type = el.dataset.banniereType;

  if (sessionStorage.getItem("banniere_dismissed_id") === id) {
    el.style.display = "none";
    return;
  }

  const btn = document.getElementById("btn-fermer-banniere");
  if (btn) {
    btn.onclick = function () {
      sessionStorage.setItem("banniere_dismissed_id", id);
      el.style.display = "none";
    };
  }

  const delais = { info: 10000, warning: 20000 };
  if (delais[type]) {
    setTimeout(function () {
      el.style.display = "none";
    }, delais[type]);
  }
}

// Formulaire bannière admin — Publier / Effacer
function initAdminBanniere() {
  const btnPublier = document.getElementById("btn-publier-banniere");
  const btnEffacer = document.getElementById("btn-effacer-banniere");
  if (!btnPublier && !btnEffacer) return;

  if (btnPublier) {
    btnPublier.onclick = function () {
      const message = document.getElementById("banniere-message").value.trim();
      const type = document.getElementById("banniere-type").value;
      if (!message) return;
      fetch("/api/admin/banniere", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: message, type: type }),
      })
        .then(function (r) {
          return r.json();
        })
        .then(function (data) {
          if (data.succes) afficherToast("Bannière publiée.", "succes");
          else
            afficherToast(
              data.erreur || "Erreur lors de la publication.",
              "erreur",
            );
        })
        .catch(function () {
          afficherToast("Erreur réseau.", "erreur");
          window.signalerErreur &&
            window.signalerErreur({
              message: "banniere.publier: erreur réseau",
              type: "js-error",
              url: "/api/admin/banniere",
            });
        });
    };
  }

  if (btnEffacer) {
    btnEffacer.onclick = function () {
      fetch("/api/admin/banniere", { method: "DELETE" })
        .then(function (r) {
          return r.json();
        })
        .then(function (data) {
          if (data.succes) afficherToast("Bannière effacée.", "succes");
          else
            afficherToast(
              data.erreur || "Erreur lors de l'effacement.",
              "erreur",
            );
        })
        .catch(function () {
          afficherToast("Erreur réseau.", "erreur");
          window.signalerErreur &&
            window.signalerErreur({
              message: "banniere.effacer: erreur réseau",
              type: "js-error",
              url: "/api/admin/banniere",
            });
        });
    };
  }
}

// Bouton "Relancer les fetches" (admin) — AJAX + polling indicateur visuel
function initBoutonRelancer() {
  const btn = document.getElementById("btn-relancer");
  if (!btn) return;
  if (btn.dataset.relancerInit) return;
  btn.dataset.relancerInit = "1";
  const statut = document.getElementById("relancer-statut");
  let intervalId = null;

  function mettreAJourBadges(jobsEnCours) {
    document.querySelectorAll(".job-en-cours[data-job]").forEach(function (el) {
      el.classList.add("cache");
    });
    (jobsEnCours || []).forEach(function (nom) {
      const badge = document.querySelector(
        ".job-en-cours[data-job='" + nom + "']",
      );
      if (badge) badge.classList.remove("cache");
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
          }
        })
        .catch(function () {
          arreterPolling();
          window.signalerErreur &&
            window.signalerErreur({
              message: "relancer: polling jobs échoué",
              type: "js-error",
              url: "/api/admin/jobs",
            });
        });
    }, 3000);
  }

  fetch("/api/admin/jobs")
    .then(function (rep) {
      return rep.json();
    })
    .then(function (data) {
      var actifs = data.jobsEnCours || [];
      mettreAJourBadges(actifs);
      if (actifs.length > 0) demarrerPolling();
    })
    .catch(function () {
      window.signalerErreur &&
        window.signalerErreur({
          message: "relancer: vérification état jobs échouée",
          type: "js-error",
          url: "/api/admin/jobs",
        });
    });

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
        window.signalerErreur &&
          window.signalerErreur({
            message: "relancer: worker inaccessible",
            type: "js-error",
            url: "/api/admin/relancer",
          });
      });
  });
}

// Logs admin — filtre multi-niveaux avec warn+error actifs par défaut
function initLogsAdmin() {
  const table = document.getElementById("table-logs");
  const filtreSource = document.getElementById("filtre-source");
  const filtresActifs = new Set(["warn", "error"]);
  const btnNiveaux = document.querySelectorAll(".log-filtre-btn");
  const compte = document.getElementById("log-compte");
  const noteInfo = document.getElementById("logs-note-info");

  if (!btnNiveaux.length) return;

  function formaterTs(epochMs) {
    if (!epochMs) return "";
    const d = new Date(epochMs);
    const fmt = new Intl.DateTimeFormat("fr-FR", {
      weekday: "short",
      day: "numeric",
      month: "short",
      hour: "2-digit",
      minute: "2-digit",
      timeZone: "Europe/Paris",
    });
    return fmt
      .format(d)
      .replace(",", "")
      .replace(/(\d{2}):(\d{2})/, function (_, h, m) {
        return h + "h" + (m === "00" ? "" : m);
      });
  }

  function ajouterMeta(tbody) {
    tbody.querySelectorAll("tr[data-meta]").forEach(function (tr) {
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

  if (table) ajouterMeta(table.querySelector("tbody") || table);

  function mettreAJourBoutonsLogs() {
    btnNiveaux.forEach(function (b) {
      const actif = filtresActifs.has(b.getAttribute("data-niveau"));
      b.classList.toggle("actif", actif);
      b.setAttribute("aria-pressed", String(actif));
    });
  }

  function mettreAJourNoteInfo() {
    if (!noteInfo) return;
    noteInfo.hidden = filtresActifs.has("info");
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
    mettreAJourNoteInfo();
  }

  function creerLignelog(entree) {
    const tr = document.createElement("tr");
    if (entree.niveau === "error") tr.classList.add("log-error");
    else if (entree.niveau === "warn") tr.classList.add("log-warn");
    tr.setAttribute("data-source", entree.source || "");
    tr.setAttribute("data-niveau", entree.niveau || "");
    tr.setAttribute("data-meta", entree.meta || "");

    const tdTs = document.createElement("td");
    tdTs.className = "log-ts";
    tdTs.setAttribute("data-label", "Horodatage");
    tdTs.textContent = formaterTs(entree.ts);

    const tdNiv = document.createElement("td");
    tdNiv.setAttribute("data-label", "Niveau");
    tdNiv.textContent = entree.niveau || "";

    const tdSrc = document.createElement("td");
    tdSrc.setAttribute("data-label", "Source");
    tdSrc.textContent = entree.source || "";

    const tdMsg = document.createElement("td");
    tdMsg.className = "log-message";
    tdMsg.setAttribute("data-label", "Message");
    tdMsg.textContent = entree.message || "";

    if (entree.meta) {
      try {
        const meta = JSON.parse(entree.meta);
        const cles = Object.keys(meta);
        if (cles.length) {
          const metaDiv = document.createElement("div");
          metaDiv.className = "log-meta";
          cles.forEach(function (k) {
            const item = document.createElement("span");
            item.className = "log-meta-item";
            item.textContent = k + ": " + meta[k];
            metaDiv.appendChild(item);
          });
          tdMsg.appendChild(metaDiv);
        }
      } catch (_) {}
    }

    tr.appendChild(tdTs);
    tr.appendChild(tdNiv);
    tr.appendChild(tdSrc);
    tr.appendChild(tdMsg);
    return tr;
  }

  function rafraichirLogs() {
    fetch("/api/admin/logs")
      .then(function (r) {
        return r.json();
      })
      .then(function (donnees) {
        const tbody = table ? table.querySelector("tbody") : null;
        if (!tbody) return;
        tbody.innerHTML = "";
        donnees.forEach(function (entree) {
          tbody.appendChild(creerLignelog(entree));
        });
        filtrerLogs();
      })
      .catch(function () {
        window.signalerErreur &&
          window.signalerErreur({
            message: "logs.rafraichir: erreur réseau",
            type: "js-error",
            url: "/api/admin/logs",
          });
      });
  }

  function reinitialiserFiltres() {
    if (filtreSource) filtreSource.value = "";
    filtresActifs.clear();
    filtresActifs.add("warn");
    filtresActifs.add("error");
    mettreAJourBoutonsLogs();
    filtrerLogs();
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

  const btnRafraichir = document.getElementById("btn-rafraichir-logs");
  const btnReinitialiser = document.getElementById("btn-reinitialiser-logs");
  if (btnRafraichir) btnRafraichir.addEventListener("click", rafraichirLogs);
  if (btnReinitialiser)
    btnReinitialiser.addEventListener("click", reinitialiserFiltres);

  if (filtreSource) filtreSource.addEventListener("change", filtrerLogs);
  mettreAJourBoutonsLogs();
  filtrerLogs();
}

// Dialog confirmation VAPID (admin)
function initDialogVapid() {
  const form = document.getElementById("form-vapid");
  const dialog = document.getElementById("dialog-vapid");
  if (!form || !dialog) return;

  form.addEventListener("submit", function (e) {
    e.preventDefault();
    dialog.showModal();
  });

  document
    .getElementById("dialog-vapid-confirmer")
    ?.addEventListener("click", function () {
      dialog.close();
      form.submit();
    });

  document
    .getElementById("dialog-vapid-annuler")
    ?.addEventListener("click", function () {
      dialog.close();
    });

  dialog.addEventListener("click", function (e) {
    if (e.target === dialog) dialog.close();
  });
}

// Modification / suppression utilisateur (admin)
function initGestionUtilisateurs() {
  const formsModif = document.querySelectorAll(".form-modif-utilisateur");
  const dialogSuppr = document.getElementById("dialog-supprimer-user");
  const dialogNom = document.getElementById("dialog-supprimer-user-nom");
  let formSupprEnAttente = null;

  // Boutons Modifier / Annuler — event listeners remplacent onclick inline
  document.querySelectorAll(".btn-toggle-modif").forEach(function (btn) {
    btn.addEventListener("click", function () {
      const userId = btn.getAttribute("data-id");
      const row = document.getElementById("modif-" + userId);
      if (row) row.classList.toggle("visible");
    });
  });

  // Dialog confirmation suppression
  if (dialogSuppr) {
    document.querySelectorAll(".form-supprimer-user").forEach(function (form) {
      form.addEventListener("submit", function (e) {
        e.preventDefault();
        formSupprEnAttente = form;
        if (dialogNom)
          dialogNom.textContent =
            "Supprimer le compte de " +
            (form.getAttribute("data-nom") || "cet utilisateur") +
            " ?";
        dialogSuppr.showModal();
      });
    });

    document
      .getElementById("dialog-supprimer-user-confirmer")
      ?.addEventListener("click", function () {
        dialogSuppr.close();
        if (formSupprEnAttente) formSupprEnAttente.submit();
      });

    document
      .getElementById("dialog-supprimer-user-annuler")
      ?.addEventListener("click", function () {
        dialogSuppr.close();
        formSupprEnAttente = null;
      });

    dialogSuppr.addEventListener("click", function (e) {
      if (e.target === dialogSuppr) {
        dialogSuppr.close();
        formSupprEnAttente = null;
      }
    });
  }

  // Soumission PATCH modification
  formsModif.forEach(function (form) {
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      const userId = form.getAttribute("data-user-id");
      const erreurSpan = form.querySelector(".erreur-modif");
      if (erreurSpan) erreurSpan.hidden = true;
      const corps = {
        nom: form.querySelector("[name=nom]")?.value.trim() ?? "",
        email: form.querySelector("[name=email]")?.value.trim() ?? "",
        role: form.querySelector("[name=role]")?.value ?? "",
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
            location.replace(
              location.pathname + location.search + "#utilisateurs",
            );
          } else if (erreurSpan) {
            erreurSpan.textContent = d.erreur || "Erreur.";
            erreurSpan.hidden = false;
          }
        })
        .catch(function () {
          if (erreurSpan) {
            erreurSpan.textContent = "Erreur réseau.";
            erreurSpan.hidden = false;
          }
          window.signalerErreur &&
            window.signalerErreur({
              message: "utilisateurs.modifier: erreur réseau userId=" + userId,
              type: "js-error",
              url: "/api/admin/utilisateurs/" + userId,
            });
        });
    });
  });
}

// Modale invitation (admin)
function initModaleInvitation() {
  const dialog = document.getElementById("dialog-invitation");
  if (!dialog) return;
  if (!dialog.open) dialog.showModal();

  const input = document.getElementById("lien-invitation");
  document
    .getElementById("btn-copier-invitation")
    ?.addEventListener("click", () => {
      if (input && navigator.clipboard)
        navigator.clipboard.writeText(input.value);
    });
  document
    .getElementById("btn-fermer-invitation")
    ?.addEventListener("click", () => {
      dialog.close();
    });
}

// Push notifications (paramètres)
function initPush() {
  const erreurDiv = document.getElementById("push-erreur");
  if (!erreurDiv) return;

  const chargementDiv = document.getElementById("push-chargement");
  const nonSupporteDiv = document.getElementById("push-non-supporte");
  const vapidManquantDiv = document.getElementById("push-vapid-manquant");
  const permRefusee = document.getElementById("push-permission-refusee");
  const abonneDiv = document.getElementById("push-abonne");
  const nonAbonneDiv = document.getElementById("push-non-abonne");

  function masquerChargement() {
    if (chargementDiv) chargementDiv.hidden = true;
  }

  if (location.protocol !== "https:" && location.hostname !== "localhost") {
    masquerChargement();
    if (nonSupporteDiv) nonSupporteDiv.hidden = false;
    return;
  }
  if (!("serviceWorker" in navigator) || !("PushManager" in window)) {
    masquerChargement();
    if (nonSupporteDiv) nonSupporteDiv.hidden = false;
    return;
  }
  const infoFrequenceP = document.getElementById("push-info-frequence");
  const selectFrequence = document.getElementById("frequence");
  const btnActiver = document.getElementById("btn-activer-push");
  const btnDesactiver = document.getElementById("btn-desactiver-push");
  let pushClePublique = null;
  let pushAbonnement = null;
  let pushEnCours = false;

  function estFrequenceJamais() {
    return !selectFrequence || selectFrequence.value === "jamais";
  }

  function afficherErreur(msg) {
    erreurDiv.textContent = msg;
    erreurDiv.hidden = false;
  }

  function masquerErreur() {
    erreurDiv.hidden = true;
  }

  function majEtat() {
    masquerChargement();
    permRefusee.hidden = true;
    abonneDiv.hidden = true;
    nonAbonneDiv.hidden = true;
    if (Notification.permission === "denied") {
      permRefusee.hidden = false;
    } else if (pushAbonnement) {
      abonneDiv.hidden = false;
    } else {
      nonAbonneDiv.hidden = false;
      const frequenceJamais = estFrequenceJamais();
      if (infoFrequenceP) infoFrequenceP.hidden = !frequenceJamais;
      if (btnActiver) btnActiver.disabled = frequenceJamais;
    }
  }

  if (selectFrequence) {
    selectFrequence.addEventListener("change", majEtat);
  }

  async function chargerEtat() {
    if (chargementDiv) chargementDiv.hidden = false;
    const timeout = new Promise(function (_, reject) {
      setTimeout(function () {
        reject(new Error("timeout"));
      }, 5000);
    });
    try {
      const results = await Promise.race([
        Promise.all([
          fetch("/api/push").then(function (r) {
            return r.json();
          }),
          navigator.serviceWorker.ready,
        ]),
        timeout,
      ]);
      const repVapid = results[0];
      const registration = results[1];
      if (!repVapid.disponible) {
        masquerChargement();
        vapidManquantDiv.hidden = false;
        return;
      }
      pushClePublique = repVapid.clePublique;
      pushAbonnement = await registration.pushManager.getSubscription();
      majEtat();
    } catch (e) {
      masquerChargement();
      nonSupporteDiv.hidden = false;
      window.signalerErreur &&
        window.signalerErreur({
          message: "push.chargerEtat: " + (e && e.message ? e.message : "timeout ou erreur"),
          type: "js-error",
          url: "/api/push",
        });
    }
  }

  if (btnActiver)
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
        document.cookie =
          "push_opt_in=1;path=/;max-age=31536000;SameSite=Strict";
        majEtat();
        afficherToast("Notifications push activées.", "succes");
      } catch (e) {
        afficherErreur(e.message || "Une erreur est survenue.");
        window.signalerErreur &&
          window.signalerErreur({
            message: "push.activer: " + (e.message || "erreur"),
            type: "js-error",
            url: "/api/push",
          });
      } finally {
        pushEnCours = false;
        btnActiver.disabled = false;
        btnActiver.textContent = "Activer les notifications push";
      }
    });

  if (btnDesactiver)
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
        document.cookie = "push_opt_in=;path=/;max-age=0;SameSite=Strict";
        majEtat();
        afficherToast("Notifications push désactivées.", "info");
      } catch (e) {
        afficherErreur(e.message || "Une erreur est survenue.");
        window.signalerErreur &&
          window.signalerErreur({
            message: "push.desactiver: " + (e.message || "erreur"),
            type: "js-error",
            url: "/api/push",
          });
      } finally {
        pushEnCours = false;
        btnDesactiver.disabled = false;
        btnDesactiver.textContent = "Désactiver les notifications push";
      }
    });

  chargerEtat();
}

function urlBase64VersUint8Array(base64) {
  const rembourrage = "=".repeat((4 - (base64.length % 4)) % 4);
  const b64 = (base64 + rembourrage).replace(/-/g, "+").replace(/_/g, "/");
  const brut = atob(b64);
  return Uint8Array.from(brut, (c) => c.charCodeAt(0));
}

function arrayBufferVersBase64(buffer) {
  return btoa(String.fromCharCode(...new Uint8Array(buffer)));
}

function mettreAJourBadgeApp() {
  if (!("setAppBadge" in navigator)) return;
  var badge = document.querySelector(".nav-badge");
  var count = badge ? parseInt(badge.textContent, 10) || 0 : 0;
  if (count > 0) {
    navigator.setAppBadge(count);
  } else {
    navigator.clearAppBadge();
  }
}

function initBarresProgression() {
  document.querySelectorAll("[data-largeur]").forEach(function (el) {
    el.style.width = el.dataset.largeur + "%";
  });
}

// Observabilité JS — remontée des erreurs client vers logs_admin
(function () {
  var MAX_ERREURS = 10;
  var FENETRE_MS = 60000;

  function peutSignalerErreur() {
    var maintenant = Date.now();
    var compte = parseInt(sessionStorage.getItem("err_compte") || "0", 10);
    var depuis = parseInt(sessionStorage.getItem("err_depuis") || "0", 10);
    if (maintenant - depuis > FENETRE_MS) {
      compte = 0;
      depuis = maintenant;
    }
    if (compte >= MAX_ERREURS) return false;
    sessionStorage.setItem("err_compte", compte + 1);
    sessionStorage.setItem("err_depuis", depuis);
    return true;
  }

  function signalerErreur(charge) {
    if (!peutSignalerErreur()) return;
    fetch("/api/client/erreur", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(charge),
    }).catch(function () {});
  }

  window.addEventListener("error", function (e) {
    signalerErreur({
      message: e.message || "Erreur inconnue",
      type: "js-error",
      url: e.filename || "",
      ligne: e.lineno || 0,
      colonne: e.colno || 0,
    });
  });

  window.addEventListener("unhandledrejection", function (e) {
    if (
      e.reason instanceof DOMException &&
      e.reason.name === "InvalidStateError"
    )
      return;
    signalerErreur({
      message: String(e.reason) || "Rejection non gérée",
      type: "promise-rejection",
    });
  });

  function contexteReseau() {
    var ctx = {
      enLigne: navigator.onLine,
      visibilite: document.visibilityState,
      swActif: !!(
        navigator.serviceWorker && navigator.serviceWorker.controller
      ),
      swEtat:
        (navigator.serviceWorker &&
          navigator.serviceWorker.controller &&
          navigator.serviceWorker.controller.state) ||
        "",
    };
    if (navigator.connection) {
      ctx.typeConnexion =
        navigator.connection.effectiveType || navigator.connection.type || "";
    }
    return ctx;
  }

  window.signalerErreur = signalerErreur;

  document.addEventListener("htmx:timeout", function (e) {
    var chemin = (e.detail.requestConfig && e.detail.requestConfig.path) || "";
    signalerErreur(
      Object.assign(
        { message: "htmx:timeout — " + chemin, type: "htmx-error", url: chemin },
        contexteReseau(),
      ),
    );
  });

  document.addEventListener("htmx:sendError", function (e) {
    var chemin = (e.detail.requestConfig && e.detail.requestConfig.path) || "";
    signalerErreur(
      Object.assign(
        {
          message: "htmx:sendError — " + chemin,
          type: "htmx-error",
          url: chemin,
        },
        contexteReseau(),
      ),
    );
  });

  document.addEventListener("htmx:responseError", function (e) {
    var statut = (e.detail.xhr && e.detail.xhr.status) || "";
    var chemin = (e.detail.requestConfig && e.detail.requestConfig.path) || "";
    var urlReponse =
      (e.detail.xhr && (e.detail.xhr.responseURL || e.detail.xhr.url)) || "";
    var ctx = contexteReseau();
    if (urlReponse && urlReponse !== location.origin + chemin) {
      ctx.urlReponse = urlReponse;
    }
    signalerErreur(
      Object.assign(
        {
          message: "htmx:responseError " + statut + " — " + chemin,
          type: "htmx-error",
          url: chemin,
        },
        ctx,
      ),
    );
  });
})();

function initTout() {
  majNavActif(window.location.pathname);
  mettreAJourBadgeApp();
  initBarresProgression();
  initToasts();
  initBanniere();
  initAdminBanniere();
  initBoutonRecherche();
  initReafficher();
  initFiltresToggle();
  initOngletsPage();
  initParametres();
  initDrawerNavigation();
  initModalSignaler();
  initConfirmSupprArtiste();
  initConfirmSupprSalle();
  initBoutonRelancer();
  initLogsAdmin();
  initDialogVapid();
  initGestionUtilisateurs();
  initModaleInvitation();
  initPush();
}

document.addEventListener("DOMContentLoaded", initTout);
document.addEventListener("htmx:afterSettle", initTout);
document.addEventListener("htmx:beforeRequest", function () {
  htmx.config.globalViewTransitions = document.visibilityState === "visible";
});

if (navigator.serviceWorker) {
  navigator.serviceWorker.addEventListener("message", function (e) {
    if (e.data?.type === "sw-error") {
      window.signalerErreur &&
        window.signalerErreur({
          message: "sw: " + (e.data.message || "erreur service worker"),
          type: "js-error",
          url: "/sw.js",
        });
      return;
    }
    if (e.data?.type === "sw-navigate-app") {
      window.signalerErreur &&
        window.signalerErreur({
          message: "sw.navigate intercepté (route app) : " + e.data.path,
          type: "js-error",
          url: e.data.path,
        });
      return;
    }
    if (e.data?.type !== "nav-ancre") return;
    var cible = new URL(e.data.url, location.origin);
    if (location.pathname === cible.pathname && cible.hash) {
      document
        .getElementById(cible.hash.slice(1))
        ?.scrollIntoView({ behavior: "smooth" });
    } else {
      window.location.href = e.data.url;
    }
  });
}

// Masque la bannière dismissée avant @starting-style (synchrone, avant le premier rendu)
document.addEventListener("htmx:afterSwap", function (e) {
  if (!e.detail.target || e.detail.target.id !== "banniere-container") return;
  var el = document.getElementById("banniere");
  if (
    el &&
    sessionStorage.getItem("banniere_dismissed_id") === el.dataset.banniereId
  ) {
    el.style.display = "none";
  }
});

(function () {
  var toastHorsLigne = null;

  window.addEventListener("offline", function () {
    if (toastHorsLigne || !("showPopover" in HTMLElement.prototype)) return;
    toastHorsLigne = document.createElement("div");
    toastHorsLigne.className = "alerte alerte-avertissement";
    toastHorsLigne.setAttribute("role", "status");
    toastHorsLigne.setAttribute("aria-live", "polite");
    toastHorsLigne.textContent = "Vous êtes hors ligne.";
    var n = document.querySelectorAll("[popover].alerte").length;
    toastHorsLigne.style.setProperty("--indice-toast", n);
    toastHorsLigne.setAttribute("popover", "manual");
    document.body.appendChild(toastHorsLigne);
    toastHorsLigne.showPopover();
  });

  window.addEventListener("online", function () {
    if (!toastHorsLigne) return;
    try {
      toastHorsLigne.hidePopover();
    } catch (_) {}
    toastHorsLigne.remove();
    toastHorsLigne = null;
  });
})();

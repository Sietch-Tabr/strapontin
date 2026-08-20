import { ref } from "vue";

// Affichage différé après quelques visites plutôt qu'immédiatement — compteur purement
// cosmétique côté client (localStorage), pas besoin de persistance serveur.
const SEUIL_VISITES = 3;
const CLE_VISITES = "pwa_install_visites";
const CLE_INSTALLE = "pwa_install_installe";

const visible = ref(false);
let evenementDiffere = null;

function estDejaInstalle() {
  return localStorage.getItem(CLE_INSTALLE) === "1";
}

function incrementerVisites() {
  const n = parseInt(localStorage.getItem(CLE_VISITES) || "0", 10) + 1;
  localStorage.setItem(CLE_VISITES, String(n));
  return n;
}

// Compté une fois par chargement de l'application (état de module, singleton).
const nbVisites = incrementerVisites();

// beforeinstallprompt n'est déclenché que sur les navigateurs supportant l'installation PWA
// (Chrome/Edge desktop notamment) — Safari/Firefox ne le déclenchent jamais, la bannière reste
// simplement toujours masquée sans erreur.
window.addEventListener("beforeinstallprompt", (e) => {
  e.preventDefault();
  evenementDiffere = e;
  if (!estDejaInstalle() && nbVisites >= SEUIL_VISITES) {
    visible.value = true;
  }
});

// après une installation réussie, ne plus jamais réafficher la bannière.
window.addEventListener("appinstalled", () => {
  localStorage.setItem(CLE_INSTALLE, "1");
  visible.value = false;
  evenementDiffere = null;
});

export function usePwaInstall() {
  return {
    visible,
    async installer() {
      if (!evenementDiffere) return;
      evenementDiffere.prompt();
      await evenementDiffere.userChoice;
      evenementDiffere = null;
      visible.value = false;
    },
    plusTard() {
      visible.value = false;
      // retente seulement au prochain seuil de visites, pas dès la prochaine ouverture.
      localStorage.setItem(CLE_VISITES, "0");
    },
  };
}

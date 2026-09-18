import { ref, watch, onMounted, onUnmounted } from "vue";
import { useRoute } from "vue-router";
import { parseJsonOuNull } from "../utils/reponseJson.js";

const banniere = ref(null);
const visible = ref(false);
let minuterie = null;
// id de la bannière dont la minuterie (10 s info / 20 s warning) a déjà expiré.
// Drapeau en mémoire (pas sessionStorage) : un rechargement complet ré-affiche une fois.
// seul le poll périodique (chargerBanniere) respecte ce drapeau — une navigation SPA
// (afficherSiPertinente) doit réafficher la bannière malgré l'auto-masquage, conformément à
// Design-et-navigation.md ("réapparaît à chaque changement de page, sauf fermeture manuelle").
let idAutoMasque = null;
// un seul interval de polling au niveau module, partagé entre toutes les instances.
let intervalPoll = null;
let nbInstances = 0;

const DELAIS = { info: 10000, warning: 20000 };

function estDismissed(id) {
  return !!id && sessionStorage.getItem("banniere_dismissed_id") === id;
}

function estAutoMasque(id) {
  return !!id && idAutoMasque === id;
}

function lancerMinuterie(val) {
  if (minuterie) clearTimeout(minuterie);
  const delai = DELAIS[val.type];
  if (!delai) return;
  minuterie = setTimeout(() => {
    visible.value = false;
    idAutoMasque = val.id;
  }, delai);
}

// Affiche la bannière courante si elle existe et n'a pas été rejetée manuellement.
//
// contrairement à chargerBanniere (poll périodique — ne doit pas ressusciter une
// bannière auto-masquée sans navigation réelle), une navigation SPA doit réafficher une bannière
// auto-masquée (types info/warning, Design-et-navigation.md) — seule une fermeture manuelle doit
// bloquer la réapparition, via estDismissed (sessionStorage, persistant).
function afficherSiPertinente() {
  const data = banniere.value;
  if (!data?.message) return;
  if (estDismissed(data.id)) return;
  visible.value = true;
  lancerMinuterie(data);
}

export async function chargerBanniere() {
  try {
    const rep = await fetch("/api/banniere/json");
    const texte = rep.ok ? await rep.text() : "";
    const data = parseJsonOuNull(texte);
    banniere.value = data;
    if (!data?.message || estDismissed(data.id) || estAutoMasque(data.id)) {
      visible.value = false;
      return;
    }
    visible.value = true;
    lancerMinuterie(data);
  } catch (_) {
    // échec ignoré : best-effort, la bannière ne s'affiche simplement pas
  }
}

export function useBanniere() {
  const route = useRoute();

  watch(
    () => route.path,
    () => afficherSiPertinente(),
  );

  onMounted(() => {
    nbInstances += 1;
    if (!intervalPoll) {
      intervalPoll = setInterval(chargerBanniere, 60000);
    }
  });

  onUnmounted(() => {
    nbInstances -= 1;
    if (nbInstances <= 0 && intervalPoll) {
      clearInterval(intervalPoll);
      intervalPoll = null;
    }
  });

  return {
    banniere,
    visible,
    fermer() {
      if (banniere.value?.id) {
        sessionStorage.setItem("banniere_dismissed_id", banniere.value.id);
      }
      visible.value = false;
      if (minuterie) clearTimeout(minuterie);
    },
  };
}

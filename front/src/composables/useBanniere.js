import { appelApi } from "../utils/api.js";
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

// Récupère l'état courant de la bannière côté serveur et met à jour le cache partagé.
// Échec réseau : ignoré (best-effort), on garde l'état affiché tel quel.
async function fetcherBanniere() {
  try {
    const rep = await appelApi("/api/banniere/json", { arrierePlan: true });
    const texte = rep.ok ? await rep.text() : "";
    const data = parseJsonOuNull(texte);
    banniere.value = data;
    return { ok: true, data };
  } catch (_) {
    return { ok: false, data: null };
  }
}

function appliquerVisibilite(data, respecterAutoMasque) {
  if (
    !data?.message ||
    estDismissed(data.id) ||
    (respecterAutoMasque && estAutoMasque(data.id))
  ) {
    visible.value = false;
    return;
  }
  visible.value = true;
  lancerMinuterie(data);
}

// Affiche la bannière si elle existe et n'a pas été rejetée manuellement.
//
// Revalide toujours contre le serveur (pas seulement le cache module-scope) : une bannière
// effacée côté admin doit cesser de réapparaître immédiatement, même chez un utilisateur qui ne
// l'a jamais fermée manuellement (pas de flag sessionStorage posé dans ce cas).
//
// contrairement à chargerBanniere (poll périodique — ne doit pas ressusciter une
// bannière auto-masquée sans navigation réelle), une navigation SPA doit réafficher une bannière
// auto-masquée (types info/warning, Design-et-navigation.md) — seule une fermeture manuelle doit
// bloquer la réapparition, via estDismissed (sessionStorage, persistant).
export async function afficherSiPertinente() {
  const { ok, data } = await fetcherBanniere();
  if (!ok) return;
  appliquerVisibilite(data, false);
}

export async function chargerBanniere() {
  const { ok, data } = await fetcherBanniere();
  if (!ok) return;
  appliquerVisibilite(data, true);
}

// Exposé pour les tests uniquement (état module-scope non accessible sans montage Vue Router).
export function _etatBanniereTest() {
  return { banniere, visible };
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

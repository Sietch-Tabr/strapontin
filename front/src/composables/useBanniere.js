import { ref, watch, onMounted, onUnmounted } from "vue";
import { useRoute } from "vue-router";

const banniere = ref(null);
const visible = ref(false);
let minuterie = null;
// #591 : id de la bannière dont la minuterie de 10 s a déjà expiré.
// Drapeau en mémoire (pas sessionStorage) : un rechargement complet ré-affiche une fois,
// mais navigation et poll ne ré-affichent plus une bannière déjà auto-masquée.
let idAutoMasque = null;
// #571 : un seul interval de polling au niveau module, partagé entre toutes les instances.
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

// Affiche la bannière courante si elle existe et n'a été ni rejetée ni auto-masquée.
function afficherSiPertinente() {
  const data = banniere.value;
  if (!data?.message) return;
  if (estDismissed(data.id) || estAutoMasque(data.id)) return;
  visible.value = true;
  lancerMinuterie(data);
}

export async function chargerBanniere() {
  try {
    const rep = await fetch("/api/banniere/json");
    const data = rep.ok ? await rep.json() : null;
    banniere.value = data;
    if (!data?.message || estDismissed(data.id) || estAutoMasque(data.id)) {
      visible.value = false;
      return;
    }
    visible.value = true;
    lancerMinuterie(data);
  } catch (_) {}
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

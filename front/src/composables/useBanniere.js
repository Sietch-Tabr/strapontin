import { ref, watch, onMounted, onUnmounted } from "vue";
import { useRoute } from "vue-router";

const banniere = ref(null);
const visible = ref(false);
let minuterie = null;

const DELAIS = { info: 10000, warning: 20000 };

function estDismissed(id) {
  return !!id && sessionStorage.getItem("banniere_dismissed_id") === id;
}

function lancerMinuterie(val) {
  if (minuterie) clearTimeout(minuterie);
  const delai = DELAIS[val.type];
  if (!delai) return;
  minuterie = setTimeout(() => {
    visible.value = false;
  }, delai);
}

export async function chargerBanniere() {
  try {
    const rep = await fetch("/api/banniere/json");
    const data = rep.ok ? await rep.json() : null;
    banniere.value = data;
    if (!data?.message) {
      visible.value = false;
      return;
    }
    if (estDismissed(data.id)) {
      visible.value = false;
      return;
    }
    visible.value = true;
    lancerMinuterie(data);
  } catch (_) {}
}

export function useBanniere() {
  const route = useRoute();
  let intervalPoll = null;

  watch(
    () => route.path,
    () => {
      if (!banniere.value?.message) return;
      if (estDismissed(banniere.value.id)) return;
      visible.value = true;
      lancerMinuterie(banniere.value);
    },
  );

  onMounted(() => {
    intervalPoll = setInterval(chargerBanniere, 60000);
  });

  onUnmounted(() => {
    clearInterval(intervalPoll);
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

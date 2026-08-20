import { ref } from "vue";
import { corpsJsonOuVide } from "../utils/reponseJson.js";

// état de module, singleton — même pattern que useBanniere.js.
const ouvert = ref(false);

/// Vérifie si l'utilisateur courant n'a pas encore vu la modale d'onboarding et l'ouvre le cas
/// échéant. Appelée au montage de App.vue.
export async function verifierOnboarding() {
  try {
    const rep = await fetch("/api/user/config/onboarding");
    if (!rep.ok) return;
    const data = await corpsJsonOuVide(rep);
    ouvert.value = !!data.nonVu;
  } catch (_) {}
}

async function marquerVu() {
  try {
    await fetch("/api/user/config/onboarding-vu", { method: "POST" });
  } catch (_) {}
}

export function useOnboarding() {
  return {
    ouvert,
    ouvrir() {
      ouvert.value = true;
    },
    async fermer() {
      ouvert.value = false;
      await marquerVu();
    },
  };
}

import { appelApi, lireJson } from "../utils/api.js";
import { ref } from "vue";

// état de module, singleton — même pattern que useBanniere.js.
const ouvert = ref(false);

/// Vérifie si l'utilisateur courant n'a pas encore vu la modale d'onboarding et l'ouvre le cas
/// échéant. Appelée au montage de App.vue.
export async function verifierOnboarding() {
  try {
    const rep = await appelApi("/api/user/config/onboarding");
    if (!rep.ok) return;
    const data = await lireJson(rep);
    ouvert.value = !!data.nonVu;
  } catch (_) {
    // échec ignoré : la modale reste simplement fermée
  }
}

async function marquerVu() {
  try {
    await appelApi("/api/user/config/onboarding-vu", { method: "POST" });
  } catch (_) {
    // échec ignoré : best-effort, la modale réapparaîtra simplement au prochain montage
  }
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

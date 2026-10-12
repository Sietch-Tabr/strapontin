import { ref } from "vue";
import { appelApi, lireJson } from "../utils/api.js";

// Départements choisis où aucune source ne propose de concert : affichés pour que l'absence de
// couverture ne passe pas pour une panne.
export function useDepartementsSansConcert() {
  const departementsSansConcert = ref([]);

  async function chargerDepartementsSansConcert() {
    try {
      const rep = await appelApi("/api/user/config/departements-sans-concert");
      departementsSansConcert.value = rep.ok
        ? ((await lireJson(rep)).departements ?? [])
        : [];
    } catch {
      departementsSansConcert.value = [];
    }
  }

  return { departementsSansConcert, chargerDepartementsSansConcert };
}

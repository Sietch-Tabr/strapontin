import { reactive } from "vue";
import { brancherSuiviConnexion } from "../utils/api.js";

// État de la connexion tel que vécu par l'application : lente (un appel patiente depuis
// plusieurs secondes) ou réduite aux dernières données connues. Alimenté par `appelApi`.
export const etatConnexion = reactive({
  lente: false,
  // Date (ms) de la plus ancienne donnée gardée affichée, null tant que tout est à jour.
  donneesEnCacheLe: null,
});

let branche = false;

export function initialiserSuiviConnexion() {
  if (branche) return;
  branche = true;
  brancherSuiviConnexion({
    lenteur: (lente) => {
      etatConnexion.lente = lente;
    },
    donneesEnCache: (instantMs) => {
      etatConnexion.donneesEnCacheLe =
        etatConnexion.donneesEnCacheLe === null
          ? instantMs
          : Math.min(etatConnexion.donneesEnCacheLe, instantMs);
    },
  });
}

import { reactive } from "vue";
import { brancherSuiviConnexion } from "../utils/api.js";

// État de la connexion tel que vécu par l'application : lente (un appel patiente depuis
// plusieurs secondes) ou réduite aux dernières données connues. Alimenté par `appelApi`.
export const etatConnexion = reactive({
  lente: false,
  // Date (ms) de la plus ancienne donnée gardée affichée, null tant que tout est à jour.
  donneesEnCacheLe: null,
  // Une liste de l'utilisateur n'a pu être lue et aucune copie n'existe : l'application n'a rien
  // à afficher et montre l'écran hors ligne.
  sansDonnees: false,
  // Un appel n'a obtenu aucune réponse de l'application (serveur arrêté, réseau coupé, délai
  // dépassé) : effacé dès qu'une réponse arrive. Distinct de `lente`, qui dit seulement qu'un
  // appel patiente.
  injoignable: false,
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
    sansDonnees: (valeur) => {
      etatConnexion.sansDonnees = valeur;
    },
    injoignable: (valeur) => {
      etatConnexion.injoignable = valeur;
    },
  });
}

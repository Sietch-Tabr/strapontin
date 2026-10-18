// Règles des canaux de notification, partagées entre la checklist « Premiers pas », l'onboarding
// et le rappel affiché au premier suivi. Miroir du digest côté worker : une fréquence « jamais »
// coupe tout, et le push n'atteint personne sans appareil abonné.

/** Fréquences de notification, dans l'ordre d'affichage : un seul libellé pour Paramètres,
 * l'onboarding et l'Aide, qui changent donc ensemble. */
export const FREQUENCES = [
  { valeur: "jamais", libelle: "Jamais", resume: "Aucune notification." },
  {
    valeur: "quotidien",
    libelle: "Récapitulatif quotidien (12h)",
    resume: "Un récapitulatif chaque jour à 12h.",
  },
  {
    valeur: "hebdomadaire",
    libelle: "Récapitulatif hebdomadaire (dimanche 12h)",
    resume: "Un récapitulatif chaque dimanche à 12h.",
  },
  {
    valeur: "temps_reel",
    libelle: "À chaque nouveau concert",
    resume: "Un message à chaque nouveau concert.",
  },
];

/** Plage pendant laquelle le serveur retient les notifications push. */
export const PLAGE_SILENCIEUSE =
  "Aucune notification push n'est envoyée entre 22h et 7h : celles de la nuit partent à 7h.";

/**
 * @param {object} config préférences (`frequenceNotifications`, `notifArtistes`, ...)
 * @param {number|null} nbAppareils appareils abonnés au push, null si inconnu
 * @returns {boolean} vrai si au moins un canal peut faire parvenir une notification
 */
export function canalJoignable(config, nbAppareils) {
  if (!config || config.frequenceNotifications === "jamais") return false;
  const email = !!(config.notifArtistes || config.notifSalles);
  const push =
    !!(config.notifArtistesPush || config.notifSallesPush) &&
    (nbAppareils ?? 0) > 0;
  return email || push;
}

// Une fréquence « jamais » est remplacée par l'hebdomadaire dès qu'un canal est activé, sans
// toucher au rythme déjà choisi par l'utilisateur.
function frequenceActive(config) {
  return config.frequenceNotifications === "jamais"
    ? "hebdomadaire"
    : config.frequenceNotifications;
}

function aucunCanal(config) {
  return !(
    config.notifArtistes ||
    config.notifSalles ||
    config.notifArtistesPush ||
    config.notifSallesPush
  );
}

/**
 * @param {object} config préférences actuelles
 * @param {boolean} actif email des concerts des artistes suivis
 * @returns {object} préférences à enregistrer
 */
export function avecEmailArtistes(config, actif) {
  const suivante = { ...config, notifArtistes: actif };
  if (actif)
    return { ...suivante, frequenceNotifications: frequenceActive(config) };
  return aucunCanal(suivante)
    ? {
        ...suivante,
        frequenceNotifications: "jamais",
        notifNouvelleVersion: false,
      }
    : suivante;
}

/**
 * @param {object} config préférences actuelles
 * @returns {object} préférences avec le push des artistes suivis activé
 */
export function avecPushArtistes(config) {
  return {
    ...config,
    notifArtistesPush: true,
    frequenceNotifications: frequenceActive(config),
  };
}

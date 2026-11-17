// Mise à jour de l'application depuis l'administration : fonctions pures de l'onglet « Mise à jour »
// (étapes, statuts, conditions d'installation). Les clés d'étape et de résultat sont celles que
// le module de mise à jour écrit dans son état (Etape.java, Resultat.java).

/** Étapes de la mise à jour, dans l'ordre où le module les exécute. */
export const ETAPES = [
  { cle: "VERIFICATION", libelle: "Vérification de la version" },
  { cle: "TELECHARGEMENT", libelle: "Téléchargement des fichiers" },
  { cle: "PROVENANCE", libelle: "Contrôle de la provenance" },
  { cle: "SAUVEGARDE", libelle: "Sauvegarde de la base" },
  { cle: "EXTRACTION", libelle: "Extraction des fichiers d'exploitation" },
  { cle: "INSTALLATION", libelle: "Installation et redémarrage" },
  { cle: "CONTROLE", libelle: "Contrôle de la version installée" },
];

const STATUTS = {
  faite: { libelle: "Fait", classe: "badge badge-succes" },
  "en-cours": { libelle: "En cours", classe: "badge badge-info" },
  echec: { libelle: "Échec", classe: "badge badge-danger" },
  "a-venir": { libelle: "À venir", classe: "texte-discret" },
};

/**
 * Position d'une étape dans la mise à jour.
 *
 * @param {string} cle clé de l'étape à situer
 * @param {{ resultat: string, etape: string | null } | null} etat dernier état du module
 * @returns {"faite" | "en-cours" | "echec" | "a-venir"} le statut de l'étape
 */
export function statutEtape(cle, etat) {
  if (!etat) return "a-venir";
  if (etat.resultat === "REUSSI") return "faite";
  const rang = (c) => ETAPES.findIndex((e) => e.cle === c);
  const courante = rang(etat.etape);
  if (courante < 0 || rang(cle) > courante) return "a-venir";
  if (rang(cle) < courante) return "faite";
  return etat.resultat === "ECHEC" ? "echec" : "en-cours";
}

/**
 * Libellé et classes d'affichage d'un statut d'étape.
 *
 * @param {"faite" | "en-cours" | "echec" | "a-venir"} statut statut de l'étape
 * @returns {{ libelle: string, classe: string }} ce que l'onglet affiche
 */
export function presentationStatut(statut) {
  return STATUTS[statut];
}

/**
 * Raison pour laquelle l'installation n'est pas possible.
 *
 * @param {{ versionProposee: string | null, enCours: boolean, totpActif: boolean }} donnees
 *   réponse de `GET /api/admin/mise-a-jour`
 * @returns {string | null} le motif en français, `null` si l'installation est possible
 */
export function motifImpossible(donnees) {
  if (!donnees.totpActif) {
    return "Activez d'abord l'authentification à deux facteurs de votre compte : elle est exigée pour installer une mise à jour.";
  }
  if (donnees.enCours) return "Une mise à jour est déjà demandée ou en cours.";
  if (!donnees.versionProposee) return "L'application est à jour.";
  return null;
}

/**
 * Corps de la demande d'installation.
 *
 * @param {string} version version proposée à installer
 * @param {string} motDePasse mot de passe actuel de l'administrateur
 * @param {string} codeTotp code TOTP en cours
 * @returns {string} le JSON envoyé à `POST /api/admin/mise-a-jour`
 */
export function corpsDemande(version, motDePasse, codeTotp) {
  return JSON.stringify({ version, motDePasse, codeTotp: codeTotp.trim() });
}

/**
 * Empreinte de l'état, pour détecter qu'il a changé depuis la demande : l'état précédent peut
 * rester affiché un instant, tant que le module n'a pas écrit le sien.
 *
 * @param {object | null} etat dernier état du module
 * @returns {string} une valeur qui change dès qu'un nouvel état est écrit
 */
export function empreinteEtat(etat) {
  return etat ? JSON.stringify(etat) : "";
}

/**
 * Indique que la mise à jour demandée est terminée, réussie ou non.
 *
 * @param {{ enCours: boolean, etat: object | null }} donnees réponse de l'API
 * @param {string} empreinteAvant empreinte de l'état au moment de la demande
 * @returns {boolean} vrai quand un nouvel état final est écrit
 */
export function demandeTerminee(donnees, empreinteAvant) {
  return !donnees.enCours && empreinteEtat(donnees.etat) !== empreinteAvant;
}

/**
 * Indique la version cible installée et vérifiée.
 *
 * @param {{ versionInstallee: string, etat: { resultat: string, versionCible: string } | null }} donnees
 *   réponse de l'API
 * @returns {boolean} vrai si le dernier état est une réussite et que la version en service est la cible
 */
export function estInstallee(donnees) {
  return (
    donnees.etat?.resultat === "REUSSI" &&
    donnees.etat.versionCible === donnees.versionInstallee
  );
}

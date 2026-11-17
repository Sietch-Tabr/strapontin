// Écrit sur la sortie standard les notes de la release d'une version, tirées du changelog
// utilisateur. Usage : node scripts/notes-release.mjs 3.2.0 (échoue si une version finale n'a pas d'entrée ;
// une pré-version d'essai, sans entrée, reçoit une note fixe).
import { versionsChangelog } from "../front/src/pages/changelogVersions.js";
import {
  estPreVersion,
  formaterNotes,
  NOTES_PRE_VERSION,
  trouverEntree,
} from "../front/src/utils/notesRelease.js";

const version = process.argv[2];
if (!version) {
  console.error("Usage : node scripts/notes-release.mjs <version>");
  process.exit(2);
}
const entree = trouverEntree(versionsChangelog, version);
if (!entree && estPreVersion(version)) {
  process.stdout.write(NOTES_PRE_VERSION);
  process.exit(0);
}
if (!entree) {
  console.error(`Aucune entrée du changelog pour la version ${version}`);
  process.exit(1);
}
process.stdout.write(formaterNotes(entree));

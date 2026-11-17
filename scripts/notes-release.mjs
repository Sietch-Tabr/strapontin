// Écrit sur la sortie standard les notes de la release d'une version, tirées du changelog
// utilisateur. Usage : node scripts/notes-release.mjs 3.2.0 (échoue si la version n'a pas d'entrée).
import { versionsChangelog } from "../front/src/pages/changelogVersions.js";
import {
  formaterNotes,
  trouverEntree,
} from "../front/src/utils/notesRelease.js";

const version = process.argv[2];
if (!version) {
  console.error("Usage : node scripts/notes-release.mjs <version>");
  process.exit(2);
}
const entree = trouverEntree(versionsChangelog, version);
if (!entree) {
  console.error(`Aucune entrée du changelog pour la version ${version}`);
  process.exit(1);
}
process.stdout.write(formaterNotes(entree));

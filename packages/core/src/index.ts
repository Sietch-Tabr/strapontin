export * from "./schema.js";
export { creerDb } from "./db.js";
export type { DB } from "./db.js";
export { dedoublonner } from "./deduplication.js";
export {
  normaliserTexte,
  normaliserVille,
  similarite,
  levenshtein,
} from "./normalisation.js";

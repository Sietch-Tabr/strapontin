/**
 * Versionne le service worker.
 *
 * Calcule une empreinte du contenu des assets précachés (le tableau
 * RESSOURCES_STATIQUES de sw.js) et réécrit la constante CACHE_NOM avec cette
 * empreinte. Ainsi sw.js change dès qu'un asset à URL stable (sprite, fontes,
 * images) change : le navigateur détecte la mise à jour, réinstalle le service
 * worker et reconstruit le cache automatiquement, sans bump manuel.
 *
 * Usage :
 *   node scripts/versionner-sw.mjs
 *
 * Appelé par `npm run build`, après la génération du sprite (le sprite doit
 * exister pour entrer dans l'empreinte).
 */

import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const RACINE = resolve(__dirname, '..');
const STATIC = resolve(RACINE, '../java/web/src/main/resources/static');
const SW = resolve(STATIC, 'sw.js');

const contenuSw = readFileSync(SW, 'utf8');

// Extrait les chemins déclarés dans le tableau RESSOURCES_STATIQUES de sw.js,
// pour ne hasher que les assets réellement précachés (source unique de vérité).
const blocRessources = contenuSw.match(/RESSOURCES_STATIQUES\s*=\s*\[([\s\S]*?)\]/);
if (!blocRessources) {
  console.error('RESSOURCES_STATIQUES introuvable dans sw.js');
  process.exit(1);
}
const chemins = [...blocRessources[1].matchAll(/["']([^"']+)["']/g)].map((m) => m[1]);

// Empreinte stable du contenu concaténé des assets (hash de contenu, pas de date :
// le cache n'est invalidé que si un asset change réellement).
const empreinte = createHash('sha256');
for (const chemin of chemins) {
  const fichier = resolve(STATIC, '.' + chemin); // '/icons/x.svg' -> STATIC/icons/x.svg
  try {
    empreinte.update(readFileSync(fichier));
  } catch {
    console.error(`Asset précaché introuvable : ${chemin} (${fichier})`);
    process.exit(1);
  }
}
const cacheNom = `strapontin-${empreinte.digest('hex').slice(0, 8)}`;

// Réécrit la constante CACHE_NOM (le contenu peut rester identique si aucun asset n'a changé).
const motif = /const CACHE_NOM = "[^"]*";/;
if (!motif.test(contenuSw)) {
  console.error('Ligne `const CACHE_NOM = "...";` introuvable dans sw.js');
  process.exit(1);
}
writeFileSync(SW, contenuSw.replace(motif, `const CACHE_NOM = "${cacheNom}";`), 'utf8');
console.log(`sw.js versionné : CACHE_NOM = ${cacheNom} (${chemins.length} assets précachés)`);

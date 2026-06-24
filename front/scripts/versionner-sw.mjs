/**
 * Versionne le service worker.
 *
 * Calcule une empreinte couvrant :
 *   1. Le contenu des assets précachés (tableau RESSOURCES_STATIQUES de sw.js) :
 *      fontes, images, sprite SVG — assets à URL stable.
 *   2. Le contenu de index.html généré par Vite, qui contient les URLs hashées
 *      des bundles JS/CSS. Ainsi le CACHE_NOM change à chaque déploiement qui
 *      modifie le code Vue, même si les assets statiques n'ont pas bougé.
 *
 * Le navigateur détecte la mise à jour via sw.js modifié, réinstalle le service
 * worker et purge le vieux cache automatiquement.
 *
 * Usage :
 *   node scripts/versionner-sw.mjs
 *
 * Appelé par `npm run build` APRÈS vite build (index.html doit exister).
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

// Extrait les chemins déclarés dans RESSOURCES_STATIQUES (source unique de vérité).
const blocRessources = contenuSw.match(/RESSOURCES_STATIQUES\s*=\s*\[([\s\S]*?)\]/);
if (!blocRessources) {
  console.error('RESSOURCES_STATIQUES introuvable dans sw.js');
  process.exit(1);
}
const chemins = [...blocRessources[1].matchAll(/["']([^"']+)["']/g)].map((m) => m[1]);

const empreinte = createHash('sha256');

// 1. Assets précachés à URL stable (fontes, images, sprite).
for (const chemin of chemins) {
  const fichier = resolve(STATIC, '.' + chemin);
  try {
    empreinte.update(readFileSync(fichier));
  } catch {
    console.error(`Asset précaché introuvable : ${chemin} (${fichier})`);
    process.exit(1);
  }
}

// 2. index.html généré par Vite (contient les URLs hashées des bundles JS).
//    Couvre les déploiements qui ne modifient que le code Vue.
try {
  empreinte.update(readFileSync(resolve(STATIC, 'index.html')));
} catch {
  console.error('index.html introuvable — vite build doit être exécuté avant versionner-sw.mjs');
  process.exit(1);
}

const cacheNom = `strapontin-${empreinte.digest('hex').slice(0, 8)}`;

// Réécrit CACHE_NOM dans sw.js (peut rester identique si rien n'a changé).
const motif = /const CACHE_NOM = "[^"]*";/;
if (!motif.test(contenuSw)) {
  console.error('Ligne `const CACHE_NOM = "...";` introuvable dans sw.js');
  process.exit(1);
}
writeFileSync(SW, contenuSw.replace(motif, `const CACHE_NOM = "${cacheNom}";`), 'utf8');
console.log(`sw.js versionné : CACHE_NOM = ${cacheNom} (${chemins.length} assets précachés + index.html)`);

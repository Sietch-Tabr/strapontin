/**
 * Génère le sprite SVG Lucide à partir des icônes listées dans ICONES.
 *
 * Usage :
 *   node scripts/generer-sprite.mjs
 *   npm run generer-sprite
 *
 * Pré-requis : lucide-static installé en devDependency (npm install).
 *
 * Pour ajouter une icône : ajouter son nom kebab-case dans le tableau ICONES
 * puis relancer le script (ou npm run build).
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const RACINE = resolve(__dirname, '..');
const DEST = resolve(RACINE, '../java/web/src/main/resources/static/icons/lucide-sprite.svg');

// Noms kebab-case des icônes à inclure dans le sprite.
// Chaque entrée correspond au fichier node_modules/lucide-static/icons/<nom>.svg.
const ICONES = [
  'home',
  'calendar-heart',
  'ticket',
  'compass',
  'music',
  'building-2',
  'shield-check',
  'settings',
  'help-circle',
  'sliders-horizontal',
  'bell',
  'book-open',
  'smartphone',
  'scale',
  'code',
  'message-square',
  'x',
  'menu',
  'sparkles',
  'refresh-cw',
  'info',
  'triangle-alert',
  'circle-x',
  'check',
  'download',
];

const pkgLucide = resolve(RACINE, 'node_modules/lucide-static/package.json');
const { version } = JSON.parse(readFileSync(pkgLucide, 'utf8'));

function construireSymbole(nom) {
  const chemin = resolve(RACINE, `node_modules/lucide-static/icons/${nom}.svg`);
  let svgBrut;
  try {
    svgBrut = readFileSync(chemin, 'utf8');
  } catch {
    console.error(`Icône introuvable : "${nom}" (${chemin})`);
    process.exit(1);
  }

  const match = svgBrut.match(/<svg[\s\S]*?>([\s\S]*)<\/svg>/);
  const lignes = match[1]
    .split('\n')
    .filter((l) => l.trim().length > 0);

  const indentMin = Math.min(...lignes.map((l) => l.match(/^ */)[0].length));

  const contenu = lignes
    .map((l) => '    ' + l.slice(indentMin))
    .join('\n');

  return (
    `  <symbol id="icon-${nom}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">\n` +
    contenu + '\n' +
    `  </symbol>`
  );
}

const symboles = ICONES.map(construireSymbole);

const sprite = [
  `<!-- lucide-static v${version} - ISC License -->`,
  `<svg xmlns="http://www.w3.org/2000/svg" style="display:none">`,
  '',
  symboles.join('\n\n'),
  '',
  `</svg>`,
  '',
].join('\n');

writeFileSync(DEST, sprite, 'utf8');
console.log(`Sprite généré : ${ICONES.length} icônes (lucide-static v${version}) → ${DEST}`);

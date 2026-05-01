import { writeFileSync } from "fs";

const DEPTS = [
  { code: "01", nom: "Ain" }, { code: "02", nom: "Aisne" },
  { code: "03", nom: "Allier" }, { code: "04", nom: "Alpes-de-Haute-Provence" },
  { code: "05", nom: "Hautes-Alpes" }, { code: "06", nom: "Alpes-Maritimes" },
  { code: "07", nom: "Ardèche" }, { code: "08", nom: "Ardennes" },
  { code: "09", nom: "Ariège" }, { code: "10", nom: "Aube" },
  { code: "11", nom: "Aude" }, { code: "12", nom: "Aveyron" },
  { code: "13", nom: "Bouches-du-Rhône" }, { code: "14", nom: "Calvados" },
  { code: "15", nom: "Cantal" }, { code: "16", nom: "Charente" },
  { code: "17", nom: "Charente-Maritime" }, { code: "18", nom: "Cher" },
  { code: "19", nom: "Corrèze" }, { code: "20", nom: "Corse" },
  { code: "21", nom: "Côte-d'Or" }, { code: "22", nom: "Côtes-d'Armor" },
  { code: "23", nom: "Creuse" }, { code: "24", nom: "Dordogne" },
  { code: "25", nom: "Doubs" }, { code: "26", nom: "Drôme" },
  { code: "27", nom: "Eure" }, { code: "28", nom: "Eure-et-Loir" },
  { code: "29", nom: "Finistère" }, { code: "30", nom: "Gard" },
  { code: "31", nom: "Haute-Garonne" }, { code: "32", nom: "Gers" },
  { code: "33", nom: "Gironde" }, { code: "34", nom: "Hérault" },
  { code: "35", nom: "Ille-et-Vilaine" }, { code: "36", nom: "Indre" },
  { code: "37", nom: "Indre-et-Loire" }, { code: "38", nom: "Isère" },
  { code: "39", nom: "Jura" }, { code: "40", nom: "Landes" },
  { code: "41", nom: "Loir-et-Cher" }, { code: "42", nom: "Loire" },
  { code: "43", nom: "Haute-Loire" }, { code: "44", nom: "Loire-Atlantique" },
  { code: "45", nom: "Loiret" }, { code: "46", nom: "Lot" },
  { code: "47", nom: "Lot-et-Garonne" }, { code: "48", nom: "Lozère" },
  { code: "49", nom: "Maine-et-Loire" }, { code: "50", nom: "Manche" },
  { code: "51", nom: "Marne" }, { code: "52", nom: "Haute-Marne" },
  { code: "53", nom: "Mayenne" }, { code: "54", nom: "Meurthe-et-Moselle" },
  { code: "55", nom: "Meuse" }, { code: "56", nom: "Morbihan" },
  { code: "57", nom: "Moselle" }, { code: "58", nom: "Nièvre" },
  { code: "59", nom: "Nord" }, { code: "60", nom: "Oise" },
  { code: "61", nom: "Orne" }, { code: "62", nom: "Pas-de-Calais" },
  { code: "63", nom: "Puy-de-Dôme" }, { code: "64", nom: "Pyrénées-Atlantiques" },
  { code: "65", nom: "Hautes-Pyrénées" }, { code: "66", nom: "Pyrénées-Orientales" },
  { code: "67", nom: "Bas-Rhin" }, { code: "68", nom: "Haut-Rhin" },
  { code: "69", nom: "Rhône" }, { code: "70", nom: "Haute-Saône" },
  { code: "71", nom: "Saône-et-Loire" }, { code: "72", nom: "Sarthe" },
  { code: "73", nom: "Savoie" }, { code: "74", nom: "Haute-Savoie" },
  { code: "75", nom: "Paris" }, { code: "76", nom: "Seine-Maritime" },
  { code: "77", nom: "Seine-et-Marne" }, { code: "78", nom: "Yvelines" },
  { code: "79", nom: "Deux-Sèvres" }, { code: "80", nom: "Somme" },
  { code: "81", nom: "Tarn" }, { code: "82", nom: "Tarn-et-Garonne" },
  { code: "83", nom: "Var" }, { code: "84", nom: "Vaucluse" },
  { code: "85", nom: "Vendée" }, { code: "86", nom: "Vienne" },
  { code: "87", nom: "Haute-Vienne" }, { code: "88", nom: "Vosges" },
  { code: "89", nom: "Yonne" }, { code: "90", nom: "Territoire de Belfort" },
  { code: "91", nom: "Essonne" }, { code: "92", nom: "Hauts-de-Seine" },
  { code: "93", nom: "Seine-Saint-Denis" }, { code: "94", nom: "Val-de-Marne" },
  { code: "95", nom: "Val-d'Oise" },
];

async function fetchBbox({ code, nom }) {
  const url = `https://nominatim.openstreetmap.org/search?q=${encodeURIComponent(nom + ", France")}&countrycodes=fr&format=json&limit=5`;
  await new Promise(r => setTimeout(r, 1100));
  const res = await fetch(url, { headers: { "User-Agent": "strapontin-bbox/1.0" } });
  const data = await res.json();
  const hit = data.find(d => d.class === "boundary" && d.type === "administrative") ?? data[0];
  if (!hit?.boundingbox) return null;
  const [sud, nord, ouest, est] = hit.boundingbox.map(Number);
  const lat = +((sud + nord) / 2).toFixed(4);
  const lng = +((ouest + est) / 2).toFixed(4);
  const dLat = (nord - sud) * 111;
  const dLng = (est - ouest) * 111 * Math.cos((lat * Math.PI) / 180);
  const rayon = Math.ceil(Math.sqrt(dLat ** 2 + dLng ** 2) / 2);
  return { code, nom, lat, lng, rayon };
}

const resultats = [];
for (const dept of DEPTS) {
  process.stdout.write(`${dept.code} ${dept.nom}... `);
  const r = await fetchBbox(dept);
  if (r) { resultats.push(r); console.log(`lat=${r.lat} lng=${r.lng} rayon=${r.rayon}km`); }
  else console.log("ÉCHEC");
}
writeFileSync("/tmp/bboxes-depts.json", JSON.stringify(resultats, null, 2));
console.log(`\n${resultats.length}/95 départements`);

// Script one-shot : génère le bloc CENTRES_DEPT depuis l'API officielle geo.api.gouv.fr
// Calcule le centroïde de chaque département comme moyenne des centres de ses communes.
// Usage : tsx scripts/generer-centres-dept.ts
// Copier la sortie dans packages/sources/src/ticketmaster.ts (remplacement de CENTRES_DEPT)

interface Commune {
  centre?: { type: string; coordinates: [number, number] };
}

async function fetchCentroideDept(code: string): Promise<{ lat: number; lng: number }> {
  const reponse = await fetch(
    `https://geo.api.gouv.fr/communes?codeDepartement=${code}&fields=centre`
  );
  if (!reponse.ok) {
    throw new Error(`Erreur API communes dept ${code}: ${reponse.status}`);
  }
  const communes: Commune[] = await reponse.json();
  const avecCentre = communes.filter((c) => c.centre);
  if (avecCentre.length === 0) {
    throw new Error(`Aucune commune avec centre pour le département ${code}`);
  }
  const lat =
    avecCentre.reduce((acc, c) => acc + c.centre!.coordinates[1], 0) /
    avecCentre.length;
  const lng =
    avecCentre.reduce((acc, c) => acc + c.centre!.coordinates[0], 0) /
    avecCentre.length;
  return {
    lat: Math.round(lat * 100) / 100,
    lng: Math.round(lng * 100) / 100,
  };
}

async function main() {
  const reponse = await fetch(
    "https://geo.api.gouv.fr/departements?fields=code&zone=metro"
  );
  if (!reponse.ok) {
    throw new Error(`Erreur API départements : ${reponse.status}`);
  }
  const departements: { code: string }[] = await reponse.json();

  // Exclure 2A et 2B (on les agrège en "20", code utilisé à la saisie pour la Corse)
  const codes = departements
    .map((d) => d.code)
    .filter((c) => c !== "2A" && c !== "2B");

  process.stderr.write(`Calcul des centroïdes pour ${codes.length + 1} départements...\n`);

  const resultats: { code: string; lat: number; lng: number }[] = [];

  for (const code of codes) {
    resultats.push({ code, ...(await fetchCentroideDept(code)) });
    process.stderr.write(`  ${code} ✓\n`);
  }

  // Corse : agréger 2A et 2B en un centroïde unique pour le code "20"
  const [r2A, r2B] = await Promise.all([
    fetchCentroideDept("2A"),
    fetchCentroideDept("2B"),
  ]);
  resultats.push({
    code: "20",
    lat: Math.round(((r2A.lat + r2B.lat) / 2) * 100) / 100,
    lng: Math.round(((r2A.lng + r2B.lng) / 2) * 100) / 100,
  });
  process.stderr.write(`  20 (2A+2B) ✓\n`);

  resultats.sort((a, b) => a.code.localeCompare(b.code));

  const commentaireCorse =
    `  // 2A et 2B sont normalisés en "20" à la saisie (codes postaux corses commencent par 20)`;

  const lignes = resultats
    .map(({ code, lat, lng }) => {
      const ligne = `  "${code}": { lat: ${lat}, lng: ${lng} },`;
      return code === "20" ? commentaireCorse + "\n" + ligne : ligne;
    })
    .join("\n");

  console.log(
    `// Correspondance code département → latitude/longitude centre\n` +
      `const CENTRES_DEPT: Record<string, { lat: number; lng: number }> = {\n` +
      lignes +
      `\n};`
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});

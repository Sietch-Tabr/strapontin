// Normalisation des chaînes pour le dédoublonnage inter-sources.

export function normaliserTexte(texte: string): string {
  return texte
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "") // supprimer accents
    .replace(/[''`]/g, " ")
    .replace(/[-–—]/g, " ")
    .replace(/[^a-z0-9\s]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

export function normaliserVille(ville: string): string {
  return normaliserTexte(ville)
    .replace(/\bsaint\b/g, "st")
    .replace(/\bsainte\b/g, "ste");
}

// Similarité simple — portage de SequenceMatcher Python
// Retourne un score entre 0 et 1
export function similarite(a: string, b: string): number {
  if (a === b) return 1;
  if (a.length === 0 || b.length === 0) return 0;

  const longueur = Math.max(a.length, b.length);
  const distance = levenshtein(a, b);
  return 1 - distance / longueur;
}

function levenshtein(a: string, b: string): number {
  const m = a.length;
  const n = b.length;
  const dp: number[][] = Array.from({ length: m + 1 }, (_, i) =>
    Array.from({ length: n + 1 }, (_, j) => (i === 0 ? j : j === 0 ? i : 0)),
  );

  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      if (a[i - 1] === b[j - 1]) {
        dp[i]![j] = dp[i - 1]![j - 1]!;
      } else {
        dp[i]![j] =
          1 + Math.min(dp[i - 1]![j]!, dp[i]![j - 1]!, dp[i - 1]![j - 1]!);
      }
    }
  }
  return dp[m]![n]!;
}

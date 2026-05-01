// Résolution des noms canoniques via MusicBrainz (prioritaire) et Deezer (fallback artistes).
// Utilisé pour corriger les noms tout-majuscule retournés par TM quand aucune autre source
// ne couvre l'artiste ou la salle.

const MUSICBRAINZ_BASE = "https://musicbrainz.org/ws/2";
const DEEZER_BASE = "https://api.deezer.com";
const CONTACT_EMAIL = process.env["CONTACT_EMAIL"] ?? "noreply@example.com";
const USER_AGENT = `Strapontin/1.0 (${CONTACT_EMAIL})`;
const DELAI_MB_MS = 1100;
const TIMEOUT_FETCH_MS = 8000;
const SEUIL_CONFIANCE_MB = 0.6;
const SEUIL_CONFIANCE_DEEZER = 0.7;

// Normalisation minimale pour comparaison — évite d'importer @strapontin/core
function normaliserLocal(s: string): string {
  return s
    .toLowerCase()
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9\s]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

// Similarité Levenshtein 0–1 — évite d'importer @strapontin/core
function similariteLocale(a: string, b: string): number {
  if (a === b) return 1;
  if (a.length === 0 || b.length === 0) return 0;
  const longueur = Math.max(a.length, b.length);
  const dp = Array.from({ length: a.length + 1 }, (_, i) =>
    Array.from({ length: b.length + 1 }, (_, j) =>
      i === 0 ? j : j === 0 ? i : 0,
    ),
  );
  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      dp[i]![j] =
        a[i - 1] === b[j - 1]
          ? dp[i - 1]![j - 1]!
          : 1 + Math.min(dp[i - 1]![j]!, dp[i]![j - 1]!, dp[i - 1]![j - 1]!);
    }
  }
  return 1 - dp[a.length]![b.length]! / longueur;
}

function attendreMB(): Promise<void> {
  return new Promise((r) => setTimeout(r, DELAI_MB_MS));
}

function fetchAvecTimeout(url: string, init?: RequestInit): Promise<Response> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), TIMEOUT_FETCH_MS);
  return fetch(url, { ...init, signal: ctrl.signal }).finally(() =>
    clearTimeout(timer),
  );
}

async function rechercherMBArtiste(nom: string): Promise<string | null> {
  const url = `${MUSICBRAINZ_BASE}/artist/?query=${encodeURIComponent(nom)}&fmt=json&limit=1`;
  try {
    const res = await fetchAvecTimeout(url, {
      headers: { "User-Agent": USER_AGENT },
    });
    await attendreMB();
    if (!res.ok) return null;
    const data = (await res.json()) as {
      artists?: Array<{ score: number; name: string }>;
    };
    const artiste = data.artists?.[0];
    if (!artiste || artiste.score < 90) return null;
    if (
      similariteLocale(normaliserLocal(nom), normaliserLocal(artiste.name)) <
      SEUIL_CONFIANCE_MB
    )
      return null;
    return artiste.name;
  } catch {
    await attendreMB();
    return null;
  }
}

async function rechercherMBSalle(nom: string): Promise<string | null> {
  const url = `${MUSICBRAINZ_BASE}/place/?query=${encodeURIComponent(nom)}&fmt=json&limit=1`;
  try {
    const res = await fetchAvecTimeout(url, {
      headers: { "User-Agent": USER_AGENT },
    });
    await attendreMB();
    if (!res.ok) return null;
    const data = (await res.json()) as {
      places?: Array<{ score: number; name: string }>;
    };
    const lieu = data.places?.[0];
    if (!lieu || lieu.score < 90) return null;
    return lieu.name;
  } catch {
    await attendreMB();
    return null;
  }
}

async function rechercherDeezerArtiste(nom: string): Promise<string | null> {
  const url = `${DEEZER_BASE}/search/artist?q=${encodeURIComponent(nom)}&limit=1`;
  try {
    const res = await fetchAvecTimeout(url);
    if (!res.ok) return null;
    const data = (await res.json()) as {
      data?: Array<{ name: string }>;
    };
    const resultat = data.data?.[0]?.name ?? null;
    if (!resultat) return null;
    // Valider la pertinence du résultat Deezer (contrairement à MB, pas de score natif)
    if (
      similariteLocale(normaliserLocal(nom), normaliserLocal(resultat)) <
      SEUIL_CONFIANCE_DEEZER
    ) {
      return null;
    }
    return resultat;
  } catch {
    return null;
  }
}

export type SourceResolution =
  | "musicbrainz"
  | "deezer"
  | "inchange";

export async function resoudreNomCanonique(
  type: "artiste" | "salle",
  nom: string,
): Promise<{ nom: string; source: SourceResolution }> {
  if (type === "artiste") {
    const nomMB = await rechercherMBArtiste(nom);
    if (nomMB) return { nom: nomMB, source: "musicbrainz" };
    const nomDeezer = await rechercherDeezerArtiste(nom);
    return nomDeezer
      ? { nom: nomDeezer, source: "deezer" }
      : { nom, source: "inchange" };
  }
  const nomMB = await rechercherMBSalle(nom);
  return nomMB
    ? { nom: nomMB, source: "musicbrainz" }
    : { nom, source: "inchange" };
}

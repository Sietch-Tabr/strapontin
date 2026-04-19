// Résolution des noms canoniques via MusicBrainz (prioritaire) et Deezer (fallback artistes).
// Utilisé pour corriger les noms tout-majuscule retournés par TM quand aucune autre source
// ne couvre l'artiste ou la salle.

const MUSICBRAINZ_BASE = "https://musicbrainz.org/ws/2";
const DEEZER_BASE = "https://api.deezer.com";
const CONTACT_EMAIL =
  process.env["CONTACT_EMAIL"] ?? "kwaite@hotmail.fr";
const USER_AGENT = `Strapontin/1.0 (${CONTACT_EMAIL})`;
const DELAI_MB_MS = 1100;
// Timeout réseau pour MB et Deezer — évite le blocage si la source ne répond pas
const TIMEOUT_FETCH_MS = 8000;
// Seuil de confiance Deezer : similarité entre le nom recherché et le résultat retourné
const SEUIL_CONFIANCE_DEEZER = 0.7;

// Ratio de caractères communs (SequenceMatcher simplifié) — évite d'importer @strapontin/core
function similitudeBrute(a: string, b: string): number {
  if (a === b) return 1;
  if (a.length === 0 || b.length === 0) return 0;
  const communes = [...a].filter((c) => b.includes(c)).length;
  return (2 * communes) / (a.length + b.length);
}

function attendreMB(): Promise<void> {
  return new Promise((r) => setTimeout(r, DELAI_MB_MS));
}

function fetchAvecTimeout(
  url: string,
  init?: RequestInit,
): Promise<Response> {
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
    if (similitudeBrute(nom.toLowerCase(), resultat.toLowerCase()) < SEUIL_CONFIANCE_DEEZER) {
      return null;
    }
    return resultat;
  } catch {
    return null;
  }
}

export async function resoudreNomCanonique(
  type: "artiste" | "salle",
  nom: string,
): Promise<string> {
  if (type === "artiste") {
    const nomMB = await rechercherMBArtiste(nom);
    if (nomMB) return nomMB;
    const nomDeezer = await rechercherDeezerArtiste(nom);
    return nomDeezer ?? nom;
  }
  const nomMB = await rechercherMBSalle(nom);
  return nomMB ?? nom;
}

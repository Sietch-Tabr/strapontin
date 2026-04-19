// Résolution des noms canoniques via MusicBrainz (prioritaire) et Deezer (fallback artistes).
// Utilisé pour corriger les noms tout-majuscule retournés par TM quand aucune autre source
// ne couvre l'artiste ou la salle.

const MUSICBRAINZ_BASE = "https://musicbrainz.org/ws/2";
const DEEZER_BASE = "https://api.deezer.com";
const USER_AGENT = "Strapontin/1.0 (kwaite@hotmail.fr)";
const DELAI_MB_MS = 1100;

function attendreMB(): Promise<void> {
  return new Promise((r) => setTimeout(r, DELAI_MB_MS));
}

async function rechercherMBArtiste(nom: string): Promise<string | null> {
  const url = `${MUSICBRAINZ_BASE}/artist/?query=${encodeURIComponent(nom)}&fmt=json&limit=1`;
  try {
    const res = await fetch(url, { headers: { "User-Agent": USER_AGENT } });
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
    const res = await fetch(url, { headers: { "User-Agent": USER_AGENT } });
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
    const res = await fetch(url);
    if (!res.ok) return null;
    const data = (await res.json()) as {
      data?: Array<{ name: string }>;
    };
    return data.data?.[0]?.name ?? null;
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

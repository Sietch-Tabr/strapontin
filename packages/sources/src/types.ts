// Type commun retourné par toutes les sources avant normalisation/dédoublonnage.

export interface ConcertBrut {
  artiste: string;
  date: string; // YYYY-MM-DD
  heure: string | null; // HH:MM ou null
  salle: string;
  ville: string | null;
  dept: string | null; // code département 2-3 chiffres
  prixMin: number | null;
  prixMax: number | null;
  url: string | null;
  source: string; // "ticketmaster" | "bandsintown" | "eventim"
}

export interface SourceConcerts {
  fetchArtiste(nom: string): Promise<ConcertBrut[]>;
  fetchRegion(depts: string[], classifs: string[]): Promise<ConcertBrut[]>;
}

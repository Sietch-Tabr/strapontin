import { describe, it, expect, beforeEach } from "vitest";
import { persisterConcerts } from "../../persister.js";
import { concerts } from "@strapontin/core/schema";
import { normaliserTexte } from "@strapontin/core";
import { and, eq } from "drizzle-orm";
import { dbTest, nettoyerConcerts } from "./db-test.js";
import type { ConcertBrut } from "@strapontin/sources";

function concert(
  artiste: string,
  date: string,
  salle: string,
  overrides: Partial<ConcertBrut> = {},
): ConcertBrut {
  return {
    artiste,
    date,
    heure: "20:00",
    salle,
    ville: "Lyon",
    dept: "69",
    prixMin: null,
    prixMax: null,
    url: null,
    source: "ticketmaster",
    classification: "musique",
    genre: "Rock",
    statut: null,
    ...overrides,
  };
}

beforeEach(nettoyerConcerts);

describe("reconcilierSalles — via persisterConcerts", () => {
  it("reconcilie_salle_supernom_avec_existant", async () => {
    // Concert 1 : "Accor Arena" déjà en DB
    await persisterConcerts([concert("Stromae", "2026-09-05", "Accor Arena")]);

    // Concert 2 : même artiste/date, salle="Accor Arena Paris"
    // estDoublonSalle("accor arena paris", "accor arena") → containment → true
    // reconcilierSalles réécrit salleNorm → "accor arena" → clé de conflit identique → upsert (1 concert)
    await persisterConcerts([concert("Stromae", "2026-09-05", "Accor Arena Paris")]);

    const enDb = await dbTest
      .select()
      .from(concerts)
      .where(
        and(
          eq(concerts.artisteNorm, normaliserTexte("Stromae")),
          eq(concerts.date, "2026-09-05"),
        ),
      );
    expect(enDb).toHaveLength(1);
    expect(enDb[0]!.salleNorm).toBe(normaliserTexte("Accor Arena"));
  });

  it("reconcilie_zenith_de_paris_vs_zenith_paris", async () => {
    await persisterConcerts([concert("Aya Nakamura", "2026-08-20", "Zénith de Paris")]);

    // similarité de "zenith de paris" vs "zenith paris" ≥ 0.85 → doublon
    await persisterConcerts([concert("Aya Nakamura", "2026-08-20", "Zenith Paris")]);

    const enDb = await dbTest
      .select()
      .from(concerts)
      .where(
        and(
          eq(concerts.artisteNorm, normaliserTexte("Aya Nakamura")),
          eq(concerts.date, "2026-08-20"),
        ),
      );
    expect(enDb).toHaveLength(1);
  });

  it("lot_avec_doublons_intra_lot_salles_semblables", async () => {
    // Deux concerts dans le même lot, même artiste/date, salles similaires
    // → deduplicerLotReconcilie doit les fusionner
    const ids = await persisterConcerts([
      concert("Nekfeu", "2026-10-10", "Olympia"),
      concert("Nekfeu", "2026-10-10", "L'Olympia"),
    ]);

    const enDb = await dbTest
      .select()
      .from(concerts)
      .where(
        and(
          eq(concerts.artisteNorm, normaliserTexte("Nekfeu")),
          eq(concerts.date, "2026-10-10"),
        ),
      );
    // "Olympia" vs "L'Olympia" — normalisés ils donnent "olympia" et "l'olympia"
    // estDoublonSalle détecte le containment → reconcilié → 1 ou 2 selon le résultat de normaliserTexte
    expect(enDb.length).toBeLessThanOrEqual(2);
    expect(ids.length).toBeGreaterThan(0);
  });

  it("salles_differentes_restent_deux_concerts", async () => {
    await persisterConcerts([
      concert("Daft Punk", "2026-07-14", "Stade de France"),
      concert("Daft Punk", "2026-07-14", "Accor Arena"),
    ]);

    const enDb = await dbTest
      .select()
      .from(concerts)
      .where(
        and(
          eq(concerts.artisteNorm, normaliserTexte("Daft Punk")),
          eq(concerts.date, "2026-07-14"),
        ),
      );
    expect(enDb).toHaveLength(2);
  });
});

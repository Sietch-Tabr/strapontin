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
    ville: "Paris",
    dept: "75",
    prixMin: null,
    prixMax: null,
    url: null,
    source: "ticketmaster",
    classification: "musique",
    genre: "Pop",
    statut: null,
    ...overrides,
  };
}

beforeEach(nettoyerConcerts);

describe("persisterConcerts", () => {
  it("liste_vide_retourne_tableau_vide", async () => {
    const ids = await persisterConcerts([]);
    expect(ids).toHaveLength(0);
  });

  it("insert_concert_unique_en_db", async () => {
    const ids = await persisterConcerts([concert("Mika", "2026-10-15", "Accor Arena")]);

    expect(ids).toHaveLength(1);

    const enDb = await dbTest
      .select()
      .from(concerts)
      .where(eq(concerts.id, ids[0]!));
    expect(enDb).toHaveLength(1);
    expect(enDb[0]!.artiste).toBe("Mika");
    expect(enDb[0]!.salle).toBe("Accor Arena");
  });

  it("doublon_exacte_meme_source_un_seul_en_db", async () => {
    await persisterConcerts([
      concert("Coldplay", "2026-11-01", "Stade de France"),
      concert("Coldplay", "2026-11-01", "Stade de France"),
    ]);

    const enDb = await dbTest
      .select()
      .from(concerts)
      .where(
        and(
          eq(concerts.artisteNorm, normaliserTexte("Coldplay")),
          eq(concerts.date, "2026-11-01"),
        ),
      );
    expect(enDb).toHaveLength(1);
  });

  it("conflict_garde_nom_avec_diacritiques", async () => {
    await persisterConcerts([
      concert("JULIEN DORE", "2026-09-20", "La Cigale", { source: "ticketmaster" }),
      concert("Julien Doré", "2026-09-20", "La Cigale", { source: "eventim" }),
    ]);

    const enDb = await dbTest
      .select({ artiste: concerts.artiste })
      .from(concerts)
      .where(eq(concerts.artisteNorm, normaliserTexte("Julien Doré")));
    expect(enDb).toHaveLength(1);
    expect(enDb[0]!.artiste).toBe("Julien Doré");
  });

  it("conflict_garde_salle_en_casse_mixte", async () => {
    await persisterConcerts([
      concert("Vianney", "2026-08-10", "ACCOR ARENA", { source: "ticketmaster" }),
      concert("Vianney", "2026-08-10", "Accor Arena", { source: "eventim" }),
    ]);

    const enDb = await dbTest
      .select({ salle: concerts.salle })
      .from(concerts)
      .where(eq(concerts.artisteNorm, normaliserTexte("Vianney")));
    expect(enDb).toHaveLength(1);
    expect(enDb[0]!.salle).toBe("Accor Arena");
  });

  it("statut_cancelled_disponible_false", async () => {
    await persisterConcerts([
      concert("Angèle", "2026-07-04", "Olympia", { statut: "cancelled" }),
    ]);

    const enDb = await dbTest
      .select({ disponible: concerts.disponible, statut: concerts.statut })
      .from(concerts)
      .where(
        and(
          eq(concerts.artisteNorm, normaliserTexte("Angèle")),
          eq(concerts.date, "2026-07-04"),
        ),
      );
    expect(enDb).toHaveLength(1);
    expect(enDb[0]!.disponible).toBe(false);
    expect(enDb[0]!.statut).toBe("cancelled");
  });

  it("statut_offsale_disponible_true", async () => {
    // offsale = complet (billets épuisés), pas annulé — disponible reste true
    await persisterConcerts([
      concert("Maes", "2026-07-05", "Zénith", { statut: "offsale" }),
    ]);

    const enDb = await dbTest
      .select({ disponible: concerts.disponible, statut: concerts.statut })
      .from(concerts)
      .where(
        and(
          eq(concerts.artisteNorm, normaliserTexte("Maes")),
          eq(concerts.date, "2026-07-05"),
        ),
      );
    expect(enDb).toHaveLength(1);
    expect(enDb[0]!.disponible).toBe(true);
    expect(enDb[0]!.statut).toBe("offsale");
  });

  it("insere_grand_lot_sans_erreur", async () => {
    const liste = Array.from({ length: 75 }, (_, i) =>
      concert(`Artiste${i}`, "2026-12-01", "Zénith", { dept: "75" }),
    );
    const ids = await persisterConcerts(liste);
    expect(ids).toHaveLength(75);
  });
});

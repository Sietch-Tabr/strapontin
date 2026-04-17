import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  parseDateBIT,
  mapEvenement,
  BandsintownSource,
} from "../bandsintown.js";

// --- Fixtures ---

function evenementBITRaw(
  surcharge: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    datetime: "2025-09-15T20:30:00",
    venue: {
      name: "Zénith Paris",
      city: "Paris",
      country: "France",
    },
    url: "https://www.bandsintown.com/e/12345",
    ...surcharge,
  };
}

// --- parseDateBIT ---

describe("parseDateBIT", () => {
  it("parse_date_et_heure_valides", () => {
    expect(parseDateBIT("2025-09-15T20:30:00")).toEqual({
      date: "2025-09-15",
      heure: "20:30",
    });
  });

  it("retourne_heure_null_si_heure_00_00_00", () => {
    expect(parseDateBIT("2025-09-15T00:00:00")).toEqual({
      date: "2025-09-15",
      heure: null,
    });
  });

  it("gere_chaine_sans_separateur_T", () => {
    const resultat = parseDateBIT("2025-09-15");
    expect(resultat.date).toBe("2025-09-15");
    expect(resultat.heure).toBeNull();
  });
});

// --- mapEvenement (Bandsintown) ---

describe("mapEvenement — Bandsintown", () => {
  it("parse_un_evenement_complet_en_France", () => {
    const resultat = mapEvenement(evenementBITRaw(), "Julien Clerc");
    expect(resultat).toEqual({
      artiste: "Julien Clerc",
      date: "2025-09-15",
      heure: "20:30",
      salle: "Zénith Paris",
      ville: "Paris",
      dept: null,
      prixMin: null,
      prixMax: null,
      url: "https://www.bandsintown.com/e/12345",
      source: "bandsintown",
      classification: null,
      genre: null,
      statut: null,
    });
  });

  it("filtre_les_evenements_hors_France", () => {
    const evt = evenementBITRaw({
      venue: { name: "O2 Arena", city: "London", country: "UK" },
    });
    expect(mapEvenement(evt, "Radiohead")).toBeNull();
  });

  it("accepte_le_pays_abrege_FR", () => {
    const evt = evenementBITRaw({
      venue: { name: "Salle", city: "Paris", country: "FR" },
    });
    expect(mapEvenement(evt, "Artiste")).not.toBeNull();
  });

  it("retourne_null_si_datetime_absente", () => {
    const { datetime: _d, ...sansDate } = evenementBITRaw();
    expect(mapEvenement(sansDate, "Artiste")).toBeNull();
  });

  it("dept_toujours_null_bandsintown_na_pas_de_cp", () => {
    const resultat = mapEvenement(evenementBITRaw(), "Artiste");
    expect(resultat?.dept).toBeNull();
  });

  it("salle_inconnue_si_venue_absent", () => {
    const evt = evenementBITRaw({ venue: { country: "France" } });
    const resultat = mapEvenement(evt, "Artiste");
    expect(resultat?.salle).toBe("Salle inconnue");
  });
});

// --- BandsintownSource (mocks HTTP) ---

describe("BandsintownSource.fetchArtiste", () => {
  beforeEach(() => vi.restoreAllMocks());

  it("retourne_concerts_france_depuis_reponse_valide", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify([evenementBITRaw()]), { status: 200 }),
    );

    const source = new BandsintownSource("fake-app-id");
    const resultats = await source.fetchArtiste("Julien Clerc");

    expect(resultats).toHaveLength(1);
    expect(resultats[0]?.artiste).toBe("Julien Clerc");
  });

  it("retourne_liste_vide_sur_404_artiste_inconnu", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(null, { status: 404 }),
    );

    const source = new BandsintownSource("fake-app-id");
    const resultats = await source.fetchArtiste("Artiste Inconnu");

    expect(resultats).toHaveLength(0);
  });

  it("retourne_liste_vide_sur_erreur_http", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(null, { status: 500 }),
    );

    const source = new BandsintownSource("fake-app-id");
    const resultats = await source.fetchArtiste("Radiohead");

    expect(resultats).toHaveLength(0);
  });

  it("filtre_evenements_hors_france", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify([
          evenementBITRaw(),
          evenementBITRaw({
            venue: { name: "O2", city: "London", country: "UK" },
          }),
        ]),
        { status: 200 },
      ),
    );

    const source = new BandsintownSource("fake-app-id");
    const resultats = await source.fetchArtiste("Artiste");

    expect(resultats).toHaveLength(1);
  });

  it("fetchRegion_retourne_toujours_liste_vide", async () => {
    const source = new BandsintownSource("fake-app-id");
    const resultats = await source.fetchRegion(["75"], ["music"]);
    expect(resultats).toHaveLength(0);
  });
});

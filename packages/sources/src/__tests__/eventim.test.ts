import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  extraireDept,
  parseDateEventim,
  mapProduit,
  EventimSource,
} from "../eventim.js";

// --- Fixtures ---

function produitEventimRaw(
  surcharge: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    name: "Julien Clerc",
    typeAttributes: {
      liveEntertainment: {
        startDate: "2025-09-15T20:30:00",
        location: {
          name: "Accor Arena",
          city: "Paris",
          postalCode: "75012",
        },
        priceRange: { min: 39.9, max: 99.9 },
      },
    },
    link: "https://www.eventim.fr/event/julien-clerc-1234",
    ...surcharge,
  };
}

function reponseEventimOk(
  produits: Record<string, unknown>[],
  total = produits.length,
): Response {
  return new Response(
    JSON.stringify({ products: produits, totalResults: total }),
    { status: 200 },
  );
}

// --- extraireDept (Eventim) ---

describe("extraireDept — Eventim", () => {
  it("retourne_null_si_code_postal_absent", () => {
    expect(extraireDept(null)).toBeNull();
    expect(extraireDept(undefined)).toBeNull();
    expect(extraireDept("")).toBeNull();
  });

  it("extrait_les_deux_premiers_chiffres_pour_metropole", () => {
    expect(extraireDept("75012")).toBe("75");
    expect(extraireDept("69001")).toBe("69");
  });

  it("extrait_trois_chiffres_pour_DOM_commencant_par_97", () => {
    expect(extraireDept("97200")).toBe("972");
  });

  it("accepte_les_codes_postaux_comme_nombres_en_chaine", () => {
    expect(extraireDept("13001")).toBe("13");
  });
});

// --- parseDateEventim ---

describe("parseDateEventim", () => {
  it("parse_date_et_heure_valides", () => {
    expect(parseDateEventim("2025-09-15T20:30:00")).toEqual({
      date: "2025-09-15",
      heure: "20:30",
    });
  });

  it("retourne_heure_null_si_heure_00_00", () => {
    expect(parseDateEventim("2025-09-15T00:00:00")).toEqual({
      date: "2025-09-15",
      heure: null,
    });
  });

  it("retourne_heure_null_si_pas_de_T", () => {
    expect(parseDateEventim("2025-09-15")).toEqual({
      date: "2025-09-15",
      heure: null,
    });
  });

  it("extrait_heure_en_HH_MM_seulement", () => {
    expect(parseDateEventim("2025-09-15T21:45:30")).toEqual({
      date: "2025-09-15",
      heure: "21:45",
    });
  });
});

// --- mapProduit ---

describe("mapProduit — Eventim", () => {
  it("parse_un_produit_complet", () => {
    const resultat = mapProduit(produitEventimRaw());
    expect(resultat).toEqual({
      artiste: "Julien Clerc",
      date: "2025-09-15",
      heure: "20:30",
      salle: "Accor Arena",
      ville: "Paris",
      dept: "75",
      prixMin: 39.9,
      prixMax: 99.9,
      url: "https://www.eventim.fr/event/julien-clerc-1234",
      source: "eventim",
      classification: null,
      genre: null,
      statut: null,
    });
  });

  it("retourne_null_si_date_absente", () => {
    const produit = produitEventimRaw({
      typeAttributes: { liveEntertainment: {} },
    });
    expect(mapProduit(produit)).toBeNull();
  });

  it("retourne_null_si_artiste_absent", () => {
    const { name: _n, ...sansNom } = produitEventimRaw();
    expect(mapProduit(sansNom)).toBeNull();
  });

  it("reconstruit_url_depuis_domain_et_path_si_pas_de_lien_direct", () => {
    const produit = produitEventimRaw({
      link: undefined,
      url: {
        domain: "https://www.eventim.fr",
        path: "/event/julien-clerc-1234",
      },
    });
    const resultat = mapProduit(produit);
    expect(resultat?.url).toBe(
      "https://www.eventim.fr/event/julien-clerc-1234",
    );
  });

  it("url_null_si_ni_lien_ni_url_objet", () => {
    const { link: _l, url: _u, ...sanUrl } = produitEventimRaw();
    const resultat = mapProduit(sanUrl);
    expect(resultat?.url).toBeNull();
  });

  it("salle_inconnue_si_location_absente", () => {
    const produit = produitEventimRaw({
      typeAttributes: {
        liveEntertainment: {
          startDate: "2025-09-15T20:30:00",
        },
      },
    });
    const resultat = mapProduit(produit);
    expect(resultat?.salle).toBe("Salle inconnue");
    expect(resultat?.dept).toBeNull();
  });
});

// --- EventimSource (mocks HTTP) ---

describe("EventimSource.fetchArtiste", () => {
  beforeEach(() => vi.restoreAllMocks());

  it("retourne_liste_vide_si_artiste_non_trouve_sur_eventim", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(
      new Response(JSON.stringify({ attractions: [] }), { status: 200 }),
    );

    const source = new EventimSource();
    const resultats = await source.fetchArtiste("Artiste Inconnu");

    expect(resultats).toHaveLength(0);
  });

  it("retourne_concerts_apres_recherche_attraction_puis_produits", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            attractions: [{ attractionId: "evt-123", name: "Julien Clerc" }],
          }),
          { status: 200 },
        ),
      )
      .mockResolvedValueOnce(reponseEventimOk([produitEventimRaw()]));

    const source = new EventimSource();
    const resultats = await source.fetchArtiste("Julien Clerc");

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(resultats).toHaveLength(1);
    expect(resultats[0]?.artiste).toBe("Julien Clerc");
  });

  it("prefere_correspondance_exacte_sur_eventim", async () => {
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            attractions: [
              { attractionId: "evt-999", name: "Julien Clerc Tribute" },
              { attractionId: "evt-123", name: "Julien Clerc" },
            ],
          }),
          { status: 200 },
        ),
      )
      .mockResolvedValueOnce(reponseEventimOk([]));

    const source = new EventimSource();
    await source.fetchArtiste("Julien Clerc");

    const deuxiemeAppel = vi.mocked(globalThis.fetch).mock.calls[1]!;
    expect(String(deuxiemeAppel[0])).toContain("evt-123");
  });
});

import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  extraireDept,
  parseDateTM,
  mapEvenement,
  TicketmasterSource,
} from "../ticketmaster.js";

// --- Fixtures ---

function evenementTMRaw(
  surcharge: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    name: "Julien Clerc — Tournée 2025",
    dates: {
      start: { localDate: "2025-09-15", localTime: "20:30:00" },
      status: { code: "onsale" },
    },
    _embedded: {
      attractions: [{ name: "Julien Clerc" }],
      venues: [
        {
          name: "ACCOR ARENA",
          city: { name: "Paris" },
          postalCode: "75012",
        },
      ],
    },
    classifications: [{ segment: { name: "Music" }, genre: { name: "Pop" } }],
    priceRanges: [{ min: 39.9, max: 99.9 }],
    url: "https://example.com",
    ...surcharge,
  };
}

function reponseTMOk(
  evenements: Record<string, unknown>[],
  total = evenements.length,
): Response {
  return new Response(
    JSON.stringify({
      _embedded: { events: evenements },
      page: { totalElements: total, size: 20, number: 0 },
    }),
    {
      status: 200,
      headers: new Headers({
        "Rate-Limit-Available": "9800",
        "Rate-Limit": "10000",
        "Rate-Limit-Reset": "1726420000000",
      }),
    },
  );
}

// --- extraireDept ---

describe("extraireDept — Ticketmaster", () => {
  it("retourne_null_si_code_postal_absent", () => {
    expect(extraireDept(undefined)).toBeNull();
    expect(extraireDept("")).toBeNull();
  });

  it("extrait_les_deux_premiers_chiffres_pour_metropole", () => {
    expect(extraireDept("75012")).toBe("75");
    expect(extraireDept("13001")).toBe("13");
    expect(extraireDept("01000")).toBe("01");
  });

  it("extrait_trois_chiffres_pour_DOM_commencant_par_97", () => {
    expect(extraireDept("97100")).toBe("971");
    expect(extraireDept("97200")).toBe("972");
    expect(extraireDept("97600")).toBe("976");
  });

  it("code_postal_court_retourne_la_chaine_complete", () => {
    expect(extraireDept("75")).toBe("75");
  });
});

// --- parseDateTM ---

describe("parseDateTM", () => {
  it("parse_date_et_heure_valides", () => {
    expect(parseDateTM("2025-09-15", "20:30:00")).toEqual({
      date: "2025-09-15",
      heure: "20:30",
    });
  });

  it("retourne_heure_null_si_absente", () => {
    expect(parseDateTM("2025-09-15", undefined)).toEqual({
      date: "2025-09-15",
      heure: null,
    });
  });

  it("conserve_heure_00_00", () => {
    expect(parseDateTM("2025-09-15", "00:00:00")).toEqual({
      date: "2025-09-15",
      heure: "00:00",
    });
  });

  it("tronque_la_date_aux_10_premiers_caracteres", () => {
    expect(parseDateTM("2025-09-15T20:30:00Z", "20:30:00")).toEqual({
      date: "2025-09-15",
      heure: "20:30",
    });
  });
});

// --- mapEvenement ---

describe("mapEvenement — Ticketmaster", () => {
  it("parse_un_evenement_complet", () => {
    const resultat = mapEvenement(evenementTMRaw());
    expect(resultat).toEqual({
      artiste: "Julien Clerc",
      date: "2025-09-15",
      heure: "20:30",
      salle: "Accor Arena",
      ville: "Paris",
      dept: "75",
      prixMin: 39.9,
      prixMax: 99.9,
      url: "https://example.com",
      source: "ticketmaster",
      classification: "music",
      genre: "Pop",
      statut: "onsale",
    });
  });

  it("utilise_artisteFallback_si_attractions_absentes", () => {
    const evt = evenementTMRaw({
      _embedded: {
        attractions: [],
        venues: [
          {
            name: "Zénith Paris",
            city: { name: "Paris" },
            postalCode: "75019",
          },
        ],
      },
    });
    const resultat = mapEvenement(evt, "Julien Clerc");
    expect(resultat?.artiste).toBe("Julien Clerc");
  });

  it("extrait_artiste_depuis_nom_evenement_si_pas_de_fallback", () => {
    const evt = evenementTMRaw({
      name: "Radiohead - OK Computer Tour",
      _embedded: {
        attractions: [],
        venues: [
          {
            name: "Zénith Paris",
            city: { name: "Paris" },
            postalCode: "75019",
          },
        ],
      },
    });
    const resultat = mapEvenement(evt);
    expect(resultat?.artiste).toBe("Radiohead");
  });

  it("normalise_nom_salle_tout_majuscule_en_title_case", () => {
    const resultat = mapEvenement(evenementTMRaw());
    expect(resultat?.salle).toBe("Accor Arena");
  });

  it("retourne_null_si_date_absente", () => {
    const evt = evenementTMRaw({ dates: { start: {} } });
    expect(mapEvenement(evt)).toBeNull();
  });

  it("parse_classification_arts_theatre", () => {
    const evt = evenementTMRaw({
      classifications: [
        { segment: { name: "Arts & Theatre" }, genre: { name: "Theatre" } },
      ],
    });
    const resultat = mapEvenement(evt);
    expect(resultat?.classification).toBe("theatre");
  });

  it("gere_prix_et_url_absents", () => {
    const { priceRanges: _p, url: _u, ...sansExtras } = evenementTMRaw();
    const resultat = mapEvenement(sansExtras);
    expect(resultat?.prixMin).toBeNull();
    expect(resultat?.prixMax).toBeNull();
    expect(resultat?.url).toBeNull();
  });

  it("retourne_dept_971_pour_DOM_Guadeloupe", () => {
    const evt = evenementTMRaw({
      _embedded: {
        attractions: [{ name: "Artiste" }],
        venues: [
          {
            name: "Salle",
            city: { name: "Pointe-à-Pitre" },
            postalCode: "97100",
          },
        ],
      },
    });
    const resultat = mapEvenement(evt);
    expect(resultat?.dept).toBe("971");
  });
});

// --- TicketmasterSource (mocks HTTP) ---

describe("TicketmasterSource.fetchArtiste", () => {
  beforeEach(() => vi.restoreAllMocks());

  it("retourne_concerts_depuis_reponse_valide", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      reponseTMOk([evenementTMRaw()]),
    );

    const source = new TicketmasterSource("fake-key");
    const resultats = await source.fetchArtiste("Julien Clerc");

    expect(resultats).toHaveLength(1);
    expect(resultats[0]?.artiste).toBe("Julien Clerc");
  });

  it("retourne_liste_vide_si_embedded_absent", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ page: { totalElements: 0 } }), {
        status: 200,
        headers: new Headers({
          "Rate-Limit-Available": "9800",
          "Rate-Limit": "10000",
        }),
      }),
    );

    const source = new TicketmasterSource("fake-key");
    const resultats = await source.fetchArtiste("Artiste Inconnu");

    expect(resultats).toHaveLength(0);
  });

  it("retourne_liste_vide_sur_erreur_HTTP", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(null, {
        status: 429,
        headers: new Headers({
          "Rate-Limit-Available": "0",
          "Rate-Limit": "10000",
        }),
      }),
    );

    const source = new TicketmasterSource("fake-key");
    const resultats = await source.fetchArtiste("Radiohead");

    expect(resultats).toHaveLength(0);
  });

  it("met_a_jour_le_quota_depuis_les_headers", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(reponseTMOk([]));

    const source = new TicketmasterSource("fake-key");
    await source.fetchArtiste("Radiohead");
    const quota = source.obtenirQuota();

    expect(quota?.dispo).toBe(9800);
    expect(quota?.total).toBe(10000);
  });
});

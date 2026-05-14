import { describe, it, expect, vi, beforeEach } from "vitest";

const mockDbSelect = vi.hoisted(() => vi.fn());
vi.mock("../db.js", () => ({
  db: {
    select: mockDbSelect,
    delete: vi.fn().mockReturnValue({
      where: vi.fn().mockReturnValue({
        returning: vi.fn().mockResolvedValue([]),
      }),
    }),
  },
}));

vi.mock("../sources.js", () => ({
  obtenirTicketmaster: vi.fn(),
  eventim: { fetchArtiste: vi.fn(), fetchRegion: vi.fn() },
}));

vi.mock("../persister.js", () => ({
  persisterConcerts: vi.fn(),
  recalculerMatches: vi.fn(),
  notifierUsersQuotidien: vi.fn(),
}));

vi.mock("../redis.js", () => ({
  redis: {
    get: vi.fn().mockResolvedValue(null),
    setex: vi.fn().mockResolvedValue("OK"),
    set: vi.fn().mockResolvedValue("OK"),
    lpush: vi.fn().mockResolvedValue(1),
    ltrim: vi.fn().mockResolvedValue("OK"),
  },
  CLE_QUOTA_TM: "strapontin:tm:quota",
  CLE_HISTORIQUE_QUOTA_TM: "strapontin:tm:quota:historique",
  NB_HISTORIQUE_QUOTA: 100,
  cleAttractionId: (norm: string) => `strapontin:tm:attraction:${norm}`,
  TTL_ATTRACTION_ID_S: 604800,
  cleNomCanonique: (type: string, norm: string) =>
    `strapontin:nom:canonique:${type}:${norm}`,
  TTL_NOM_CANONIQUE_S: 604800,
  cleRegionFetchee: (dept: string) => `strapontin:region:fetched:${dept}`,
  TTL_REGION_FETCHEE_S: 691200,
  cleStatsJob: (type: string) => `strapontin:job:stats:${type}`,
  TTL_STATS_JOB_S: 172800,
}));

import {
  traiterFetchArtiste,
  traiterRecalculerMatches,
  traiterNotifierDigest,
  traiterPurgerConcerts,
} from "../jobs.js";
import { obtenirTicketmaster, eventim } from "../sources.js";
import {
  persisterConcerts,
  recalculerMatches,
  notifierUsersQuotidien,
} from "../persister.js";
import type { ConcertBrut } from "@strapontin/sources";

function concertBrut(artiste = "Coldplay"): ConcertBrut {
  return {
    artiste,
    date: "2026-09-01",
    heure: "20:00",
    salle: "Stade de France",
    ville: "Saint-Denis",
    dept: "93",
    prixMin: 49,
    prixMax: 149,
    url: "https://example.com",
    source: "ticketmaster",
    classification: "music",
    genre: "Rock",
    statut: "onsale",
  };
}

function tmMock(concertsRetournes: ConcertBrut[] = [concertBrut()]) {
  return {
    fetchArtiste: vi.fn().mockResolvedValue(concertsRetournes),
    fetchArtistesBatch: vi.fn().mockResolvedValue(concertsRetournes),
    fetchRegion: vi.fn().mockResolvedValue(concertsRetournes),
    fetchSalle: vi.fn().mockResolvedValue(concertsRetournes),
    rechercherAttractionId: vi.fn().mockResolvedValue("K702-123"),
    obtenirQuota: vi.fn().mockReturnValue(null),
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(obtenirTicketmaster).mockResolvedValue(tmMock() as never);
  vi.mocked(eventim.fetchArtiste).mockResolvedValue([]);
  // Par défaut : aucun user ne suit l'artiste
  mockDbSelect.mockReturnValue({
    from: vi.fn().mockReturnValue({
      where: vi.fn().mockResolvedValue([]),
    }),
  });
});

// --- traiterFetchArtiste ---

describe("traiterFetchArtiste", () => {
  it("appelle_fetchArtiste_TM_et_Eventim_puis_persiste", async () => {
    await traiterFetchArtiste({
      type: "fetch:artiste",
      artiste: "Coldplay",
    });

    expect(obtenirTicketmaster).toHaveBeenCalledOnce();
    const tm = await vi.mocked(obtenirTicketmaster).mock.results[0]!.value;
    expect(tm.fetchArtiste).toHaveBeenCalledWith("Coldplay");
    expect(eventim.fetchArtiste).toHaveBeenCalledWith("Coldplay");
    expect(persisterConcerts).toHaveBeenCalledOnce();
  });

  it("recalcule_matches_pour_tous_les_users_suivant_artiste", async () => {
    mockDbSelect.mockReturnValue({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockResolvedValue([{ userId: "u1" }, { userId: "u2" }]),
      }),
    });

    await traiterFetchArtiste({
      type: "fetch:artiste",
      artiste: "Coldplay",
    });

    expect(recalculerMatches).toHaveBeenCalledWith(["u1", "u2"]);
  });

  it("ne_pas_appeler_recalculerMatches_si_aucun_user_ne_suit", async () => {
    await traiterFetchArtiste({
      type: "fetch:artiste",
      artiste: "Coldplay",
    });

    expect(recalculerMatches).not.toHaveBeenCalled();
  });

  it("persiste_meme_si_TM_echoue", async () => {
    const tm = tmMock();
    tm.fetchArtiste.mockRejectedValue(new Error("TM timeout"));
    vi.mocked(obtenirTicketmaster).mockResolvedValue(tm as never);
    vi.mocked(eventim.fetchArtiste).mockResolvedValue([concertBrut("Muse")]);

    await traiterFetchArtiste({
      type: "fetch:artiste",
      artiste: "Coldplay",
    });

    expect(persisterConcerts).toHaveBeenCalledOnce();
  });

  it("persiste_meme_si_Eventim_echoue", async () => {
    vi.mocked(eventim.fetchArtiste).mockRejectedValue(
      new Error("Eventim timeout"),
    );

    await traiterFetchArtiste({
      type: "fetch:artiste",
      artiste: "Coldplay",
    });

    expect(persisterConcerts).toHaveBeenCalledOnce();
  });
});

// --- traiterRecalculerMatches ---

describe("traiterRecalculerMatches", () => {
  it("delegue_a_recalculerMatches_avec_userIds", async () => {
    await traiterRecalculerMatches({
      type: "recalculer:matches",
      userIds: ["u1", "u2"],
    });

    expect(recalculerMatches).toHaveBeenCalledWith(["u1", "u2"]);
  });

  it("delegue_a_recalculerMatches_sans_userIds", async () => {
    await traiterRecalculerMatches({
      type: "recalculer:matches",
    });

    expect(recalculerMatches).toHaveBeenCalledOnce();
    expect(vi.mocked(recalculerMatches).mock.calls[0]![0]).toBeUndefined();
  });
});

// --- traiterNotifierDigest ---

describe("traiterNotifierDigest", () => {
  it("appelle_notifierUsersQuotidien", async () => {
    await traiterNotifierDigest({ type: "notifier:digest" });

    expect(notifierUsersQuotidien).toHaveBeenCalledOnce();
  });
});

// --- traiterPurgerConcerts ---

describe("traiterPurgerConcerts", () => {
  it("se_termine_sans_exception", async () => {
    await expect(
      traiterPurgerConcerts({ type: "purger:concerts" }),
    ).resolves.toBeUndefined();
  });
});

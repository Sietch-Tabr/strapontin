import { beforeEach, describe, expect, test } from "vitest";
import { creerCacheHorsLigne, estCachable } from "./cacheHorsLigne.js";

// Stockage en mémoire, à l'interface de `Storage`.
function stockageMemoire({ quota = Infinity } = {}) {
  const donnees = new Map();
  return {
    get length() {
      return donnees.size;
    },
    key: (i) => [...donnees.keys()][i] ?? null,
    getItem: (c) => donnees.get(c) ?? null,
    setItem: (c, v) => {
      const taille = [...donnees.values()].reduce((s, x) => s + x.length, 0);
      if (taille + v.length > quota) throw new Error("QuotaExceededError");
      donnees.set(c, v);
    },
    removeItem: (c) => donnees.delete(c),
  };
}

describe("estCachable", () => {
  test("estCachable_listesDeLUtilisateur_retourneVrai", () => {
    for (const url of [
      "/api/accueil",
      "/api/accueil?masques=true",
      "/api/spectacles?page=2",
      "/api/decouverte?dept=75",
      "/api/artistes",
      "/api/salles",
      "/api/session",
    ]) {
      expect(estCachable(url)).toBe(true);
    }
  });

  test("estCachable_rechercheExterne_retourneFaux", () => {
    expect(estCachable("/api/artistes?q=daft")).toBe(false);
    expect(estCachable("/api/salles?q=olympia")).toBe(false);
  });

  test("estCachable_sousRessourcesEtAutresRoutes_retourneFaux", () => {
    for (const url of [
      "/api/spectacles/matches/abc",
      "/api/admin/utilisateurs",
      "/api/user/password",
      "/api/auth/login",
      "/api/push/abonnements",
    ]) {
      expect(estCachable(url)).toBe(false);
    }
  });
});

describe("creerCacheHorsLigne", () => {
  let maintenant;
  let stockage;
  let cache;

  beforeEach(() => {
    maintenant = 1_700_000_000_000;
    stockage = stockageMemoire();
    cache = creerCacheHorsLigne({ stockage, maintenant: () => maintenant });
  });

  test("lire_apresEcriture_rendLeCorpsEtLaDate", () => {
    cache.ecrire("/api/accueil", '{"concerts":[]}');
    expect(cache.lire("/api/accueil")).toEqual({
      corps: '{"concerts":[]}',
      enregistreLe: maintenant,
    });
  });

  test("lire_urlInconnue_retourneNull", () => {
    expect(cache.lire("/api/accueil")).toBeNull();
  });

  test("lire_entreeTropAncienne_retourneNull", () => {
    cache.ecrire("/api/accueil", "{}");
    maintenant += 31 * 24 * 3_600_000;
    expect(cache.lire("/api/accueil")).toBeNull();
  });

  test("lire_entreeCorrompue_retourneNullSansException", () => {
    stockage.setItem("strapontin:cache:/api/accueil", "pas du json");
    expect(cache.lire("/api/accueil")).toBeNull();
    stockage.setItem("strapontin:cache:/api/salles", '{"corps":3}');
    expect(cache.lire("/api/salles")).toBeNull();
  });

  test("purger_effaceLesDonneesDeLApplicationSeulement", () => {
    cache.ecrire("/api/accueil", "{}");
    cache.ecrire("/api/salles", "{}");
    stockage.setItem("theme", "sombre");

    cache.purger();

    expect(cache.lire("/api/accueil")).toBeNull();
    expect(cache.lire("/api/salles")).toBeNull();
    expect(stockage.getItem("theme")).toBe("sombre");
  });

  test("ecrire_corpsTropVolumineux_neGardeRien", () => {
    cache.ecrire("/api/accueil", "x".repeat(1_500_001));
    expect(cache.lire("/api/accueil")).toBeNull();
  });

  test("ecrire_plusDeQuaranteEntrees_garde_lesPlusRecentes", () => {
    for (let i = 0; i < 45; i++) {
      maintenant += 1000;
      cache.ecrire(`/api/spectacles?page=${i}`, "{}");
    }
    expect(cache.lire("/api/spectacles?page=0")).toBeNull();
    expect(cache.lire("/api/spectacles?page=44")).not.toBeNull();
  });

  test("ecrire_quotaAtteint_libereDeLaPlaceSansLeverDException", () => {
    const petit = stockageMemoire({ quota: 400 });
    const c = creerCacheHorsLigne({
      stockage: petit,
      maintenant: () => maintenant,
    });
    for (let i = 0; i < 10; i++) {
      maintenant += 1000;
      expect(() =>
        c.ecrire(`/api/spectacles?page=${i}`, "y".repeat(40)),
      ).not.toThrow();
    }
  });

  test("sansStockage_toutResteInoffensif", () => {
    const vide = creerCacheHorsLigne({ stockage: undefined });
    expect(() => vide.ecrire("/api/accueil", "{}")).not.toThrow();
    expect(vide.lire("/api/accueil")).toBeNull();
    expect(() => vide.purger()).not.toThrow();
  });
});

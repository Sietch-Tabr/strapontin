import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import {
  appelApi,
  brancherSuiviConnexion,
  ENTETE_DONNEES_EN_CACHE,
  reinitialiserEtatSansDonnees,
  remplacerCacheHorsLigne,
  serveurJoignable,
} from "./api.js";
import { creerCacheHorsLigne } from "./cacheHorsLigne.js";

function stockageMemoire() {
  const donnees = new Map();
  return {
    get length() {
      return donnees.size;
    },
    key: (i) => [...donnees.keys()][i] ?? null,
    getItem: (c) => donnees.get(c) ?? null,
    setItem: (c, v) => donnees.set(c, v),
    removeItem: (c) => donnees.delete(c),
  };
}

const T0 = 1_700_000_000_000;

// Un fetch qui ne répond jamais, mais s'interrompt comme le vrai quand on l'annule.
const fetchSansReponse = () =>
  vi.fn(
    (_url, { signal }) =>
      new Promise((_, rejeter) =>
        signal.addEventListener("abort", () =>
          rejeter(new DOMException("annulé", "AbortError")),
        ),
      ),
  );

const reponseJson = (corps, status = 200) =>
  new Response(JSON.stringify(corps), {
    status,
    headers: { "Content-Type": "application/json" },
  });

describe("appelApi : données gardées pour le hors ligne", () => {
  let cache;
  let suivi;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(T0);
    cache = creerCacheHorsLigne({ stockage: stockageMemoire() });
    remplacerCacheHorsLigne(cache);
    suivi = { lenteur: vi.fn(), donneesEnCache: vi.fn(), sansDonnees: vi.fn() };
    brancherSuiviConnexion(suivi);
    reinitialiserEtatSansDonnees();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  test("appelApi_getReussiSurUneListe_garde_laReponse", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(reponseJson({ n: 1 }))),
    );

    const reponse = await appelApi("/api/accueil");
    await vi.advanceTimersByTimeAsync(0);

    expect(await reponse.json()).toEqual({ n: 1 });
    expect(cache.lire("/api/accueil").corps).toBe('{"n":1}');
  });

  test("appelApi_reseauCoupeAvecCopie_rendLaCopieEtLeSignale", async () => {
    cache.ecrire("/api/accueil", '{"n":7}');
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.reject(new TypeError("réseau"))),
    );

    const reponse = await appelApi("/api/accueil");

    expect(reponse.ok).toBe(true);
    expect(await reponse.json()).toEqual({ n: 7 });
    expect(reponse.headers.get(ENTETE_DONNEES_EN_CACHE)).toBe(String(T0));
    expect(suivi.donneesEnCache).toHaveBeenCalledWith(T0);
  });

  test("appelApi_reseauCoupeSansCopie_rejetteCommeAvant", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.reject(new TypeError("réseau"))),
    );

    await expect(appelApi("/api/accueil")).rejects.toThrow("réseau");
    expect(suivi.donneesEnCache).not.toHaveBeenCalled();
  });

  test("appelApi_connexionMorteAvecCopie_bascule_surLaCopieApresCinqSecondes", async () => {
    cache.ecrire("/api/spectacles", '{"n":2}');
    vi.stubGlobal("fetch", fetchSansReponse());

    const attente = appelApi("/api/spectacles");
    await vi.advanceTimersByTimeAsync(4_999);
    expect(suivi.donneesEnCache).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);

    expect(await (await attente).json()).toEqual({ n: 2 });
  });

  test("appelApi_connexionMorteSansCopie_rejetteEnTypeErrorApresQuinzeSecondes", async () => {
    vi.stubGlobal("fetch", fetchSansReponse());

    const attente = appelApi("/api/accueil");
    const verdict = expect(attente).rejects.toThrow(TypeError);
    await vi.advanceTimersByTimeAsync(15_000);

    await verdict;
    await expect(attente).rejects.not.toMatchObject({ name: "AbortError" });
  });

  test("appelApi_annulationParLAppelant_resteUnAbortErrorMemeAvecCopie", async () => {
    cache.ecrire("/api/accueil", "{}");
    vi.stubGlobal("fetch", fetchSansReponse());
    const controleur = new AbortController();

    const attente = appelApi("/api/accueil", { signal: controleur.signal });
    const verdict = expect(attente).rejects.toMatchObject({
      name: "AbortError",
    });
    controleur.abort();

    await verdict;
    expect(suivi.donneesEnCache).not.toHaveBeenCalled();
  });

  test("appelApi_serveurIndisponibleAvecCopie_rendLaCopie", async () => {
    cache.ecrire("/api/accueil", '{"n":3}');
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(new Response("", { status: 503 }))),
    );

    const reponse = await appelApi("/api/accueil");

    expect(await reponse.json()).toEqual({ n: 3 });
  });

  test("appelApi_serveurIndisponibleSansCopie_rendLaReponseTelleQuelle", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(new Response("", { status: 503 }))),
    );

    expect((await appelApi("/api/accueil")).status).toBe(503);
  });

  test("appelApi_sessionExpiree401_effaceToutesLesDonneesGardees", async () => {
    cache.ecrire("/api/accueil", "{}");
    cache.ecrire("/api/salles", "{}");
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(new Response("", { status: 401 }))),
    );

    await appelApi("/api/session");

    expect(cache.lire("/api/accueil")).toBeNull();
    expect(cache.lire("/api/salles")).toBeNull();
  });

  test("appelApi_getNonGarde_neRendJamaisDeCopieNiNEnEcrit", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.reject(new TypeError("réseau"))),
    );

    await expect(appelApi("/api/artistes?q=daft")).rejects.toThrow();
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(reponseJson({ s: 1 }))),
    );
    await appelApi("/api/artistes?q=daft");
    await vi.advanceTimersByTimeAsync(0);

    expect(cache.lire("/api/artistes?q=daft")).toBeNull();
  });

  test("appelApi_requeteModifiante_neEstNiGardeeNiLimiteeDansLeTemps", async () => {
    const simule = fetchSansReponse();
    vi.stubGlobal("fetch", simule);

    appelApi("/api/accueil/marquer-vus", { method: "POST" }).catch(() => {});
    await vi.advanceTimersByTimeAsync(60_000);

    expect(simule.mock.calls[0][1].signal).toBeUndefined();
    expect(suivi.lenteur).not.toHaveBeenCalled();
  });

  test("appelApi_reponseLenteMaisValide_signaleLaLenteurPuisLaLeve", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        () =>
          new Promise((resoudre) =>
            setTimeout(() => resoudre(reponseJson({ n: 1 })), 4_000),
          ),
      ),
    );

    const attente = appelApi("/api/accueil");
    await vi.advanceTimersByTimeAsync(3_000);
    expect(suivi.lenteur).toHaveBeenLastCalledWith(true);
    await vi.advanceTimersByTimeAsync(1_000);
    await attente;

    expect(suivi.lenteur).toHaveBeenLastCalledWith(false);
  });

  test("appelApi_reponseRapide_nesignaleAucuneLenteur", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(reponseJson({}))),
    );

    await appelApi("/api/accueil");
    await vi.advanceTimersByTimeAsync(10_000);

    expect(suivi.lenteur).not.toHaveBeenCalled();
  });
});

describe("appelApi : aucune donnée gardée", () => {
  let suivi;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(T0);
    remplacerCacheHorsLigne(
      creerCacheHorsLigne({ stockage: stockageMemoire() }),
    );
    suivi = { lenteur: vi.fn(), donneesEnCache: vi.fn(), sansDonnees: vi.fn() };
    brancherSuiviConnexion(suivi);
    reinitialiserEtatSansDonnees();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  test("appelApi_reseauCoupeSansCopie_signaleQueRienNePeutEtreAffiche", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.reject(new TypeError("Failed to fetch"))),
    );

    await expect(appelApi("/api/spectacles")).rejects.toBeInstanceOf(TypeError);

    expect(suivi.sansDonnees).toHaveBeenCalledWith(true);
    expect(suivi.donneesEnCache).not.toHaveBeenCalled();
  });

  test("appelApi_navigateurHorsLigneSansCopie_echoueAussitotSansAttendreLeServeur", async () => {
    const fetchEspion = vi.fn();
    vi.stubGlobal("fetch", fetchEspion);
    vi.stubGlobal("navigator", { onLine: false });

    await expect(appelApi("/api/accueil")).rejects.toBeInstanceOf(TypeError);

    expect(fetchEspion).not.toHaveBeenCalled();
    expect(suivi.sansDonnees).toHaveBeenCalledWith(true);
  });

  test("appelApi_aprèsUnEchecSansCopie_lesListesSuivantesNAttendentPasLeDelai", async () => {
    const fetchEspion = vi.fn(() =>
      Promise.reject(new TypeError("Failed to fetch")),
    );
    vi.stubGlobal("fetch", fetchEspion);
    await expect(appelApi("/api/spectacles")).rejects.toBeInstanceOf(TypeError);

    await expect(appelApi("/api/artistes")).rejects.toBeInstanceOf(TypeError);

    expect(fetchEspion).toHaveBeenCalledTimes(1);
  });

  test("appelApi_serveurIndisponibleSansCopie_signaleQueRienNePeutEtreAffiche", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(new Response("", { status: 503 }))),
    );

    const reponse = await appelApi("/api/salles");

    expect(reponse.status).toBe(503);
    expect(suivi.sansDonnees).toHaveBeenCalledWith(true);
  });

  test("appelApi_appelNonCachable_neSignaleJamaisAbsenceDeDonnees", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.reject(new TypeError("Failed to fetch"))),
    );

    await expect(appelApi("/api/artistes?q=abba")).rejects.toBeInstanceOf(
      TypeError,
    );

    expect(suivi.sansDonnees).not.toHaveBeenCalled();
  });

  test("appelApi_copieDisponibleEtReseauCoupe_rendLaCopieSansEcranHorsLigne", async () => {
    const cache = creerCacheHorsLigne({ stockage: stockageMemoire() });
    cache.ecrire("/api/accueil", '{"n":1}');
    remplacerCacheHorsLigne(cache);
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.reject(new TypeError("Failed to fetch"))),
    );

    const reponse = await appelApi("/api/accueil");

    expect(await reponse.json()).toEqual({ n: 1 });
    expect(suivi.sansDonnees).not.toHaveBeenCalled();
  });
});

describe("serveurJoignable", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    const cache = creerCacheHorsLigne({ stockage: stockageMemoire() });
    cache.ecrire("/api/session", '{"role":"user"}');
    remplacerCacheHorsLigne(cache);
    brancherSuiviConnexion({ lenteur: vi.fn(), donneesEnCache: vi.fn() });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  test("serveurJoignable_reponse401_estJoignable", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(new Response("", { status: 401 }))),
    );

    expect(await serveurJoignable()).toBe(true);
  });

  test("serveurJoignable_reseauCoupe_neSeContentePasDeLaCopieGardee", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.reject(new TypeError("réseau"))),
    );

    expect(await serveurJoignable()).toBe(false);
  });

  test("serveurJoignable_erreurServeur_nEstPasJoignable", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(new Response("", { status: 500 }))),
    );

    expect(await serveurJoignable()).toBe(false);
  });

  test("serveurJoignable_aucuneReponse_abandonneApresCinqSecondes", async () => {
    vi.stubGlobal("fetch", fetchSansReponse());

    const verdict = serveurJoignable();
    await vi.advanceTimersByTimeAsync(5_000);

    expect(await verdict).toBe(false);
  });
});

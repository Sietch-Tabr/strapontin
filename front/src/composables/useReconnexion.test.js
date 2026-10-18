import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { creerSurveillanceReconnexion } from "./useReconnexion.js";

function memoire() {
  const d = new Map();
  return { getItem: (c) => d.get(c) ?? null, setItem: (c, v) => d.set(c, v) };
}

describe("creerSurveillanceReconnexion", () => {
  let heure;
  beforeEach(() => {
    vi.useFakeTimers();
    heure = 1_700_000_000_000;
  });
  afterEach(() => vi.useRealTimers());

  function creer(joignable) {
    const recharger = vi.fn();
    const surveillance = creerSurveillanceReconnexion({
      joignable,
      recharger,
      maintenant: () => heure,
      memoire: memoire(),
    });
    return { surveillance, recharger };
  }

  test("sonderMaintenant_serveurJoignable_recharge", async () => {
    const { surveillance, recharger } = creer(async () => true);

    expect(await surveillance.sonderMaintenant()).toBe(true);
    expect(recharger).toHaveBeenCalledTimes(1);
  });

  test("sonderMaintenant_serveurAbsent_neRechargePas", async () => {
    const { surveillance, recharger } = creer(async () => false);

    expect(await surveillance.sonderMaintenant()).toBe(false);
    expect(recharger).not.toHaveBeenCalled();
  });

  test("sonderMaintenant_deuxRetoursRapproches_neRechargeQuUneFoisParMinute", async () => {
    const { surveillance, recharger } = creer(async () => true);

    await surveillance.sonderMaintenant();
    heure += 30_000;
    await surveillance.sonderMaintenant();
    expect(recharger).toHaveBeenCalledTimes(1);

    heure += 31_000;
    await surveillance.sonderMaintenant();
    expect(recharger).toHaveBeenCalledTimes(2);
  });

  test("sonderMaintenant_sondageDejaEnCours_nEnLanceAucunAutre", async () => {
    let finir;
    const joignable = vi.fn(() => new Promise((r) => (finir = r)));
    const { surveillance } = creer(joignable);

    const premier = surveillance.sonderMaintenant();
    const second = await surveillance.sonderMaintenant();
    finir(false);
    await premier;

    expect(second).toBe(false);
    expect(joignable).toHaveBeenCalledTimes(1);
  });

  test("demarrer_sondeToutesLesQuinzeSecondesJusquAuRetourDuServeur", async () => {
    const reponses = [false, false, true];
    const joignable = vi.fn(async () => reponses.shift());
    const { surveillance, recharger } = creer(joignable);

    surveillance.demarrer();
    await vi.advanceTimersByTimeAsync(15_000 * 2);
    expect(recharger).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(15_000);

    expect(recharger).toHaveBeenCalledTimes(1);
    surveillance.arreter();
  });

  test("arreter_neSondePlus", async () => {
    const joignable = vi.fn(async () => false);
    const { surveillance } = creer(joignable);

    surveillance.demarrer();
    surveillance.arreter();
    await vi.advanceTimersByTimeAsync(60_000);

    expect(joignable).not.toHaveBeenCalled();
  });
});

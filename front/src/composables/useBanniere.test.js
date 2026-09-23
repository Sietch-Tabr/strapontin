import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  chargerBanniere,
  afficherSiPertinente,
  _etatBanniereTest,
} from "./useBanniere.js";

function creerFausseSessionStorage() {
  const valeurs = new Map();
  return {
    getItem: (cle) => (valeurs.has(cle) ? valeurs.get(cle) : null),
    setItem: (cle, val) => valeurs.set(cle, val),
  };
}

function reponse(donnees) {
  return {
    ok: true,
    text: () =>
      Promise.resolve(donnees === null ? "" : JSON.stringify(donnees)),
  };
}

describe("useBanniere", () => {
  let fetchOriginal;
  let sessionStorageOriginal;
  const { banniere, visible } = _etatBanniereTest();

  beforeEach(() => {
    fetchOriginal = globalThis.fetch;
    sessionStorageOriginal = globalThis.sessionStorage;
    globalThis.sessionStorage = creerFausseSessionStorage();
    vi.useFakeTimers();
  });

  afterEach(() => {
    globalThis.fetch = fetchOriginal;
    globalThis.sessionStorage = sessionStorageOriginal;
    vi.useRealTimers();
  });

  it("chargerBanniere_bannièreActiveNonRejetée_afficheEtMetAJourLeCache", async () => {
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(
        reponse({ id: "b1", message: "Maintenance ce soir", type: "info" }),
      );

    await chargerBanniere();

    expect(visible.value).toBe(true);
    expect(banniere.value.message).toBe("Maintenance ce soir");
  });

  it("chargerBanniere_bannièreAutoMasquéeParLaMinuterie_neRéapparaîtPasAuPollSuivant", async () => {
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(
        reponse({ id: "b2", message: "Info courte", type: "info" }),
      );

    await chargerBanniere();
    vi.advanceTimersByTime(10000);
    expect(visible.value).toBe(false);

    await chargerBanniere();

    expect(visible.value).toBe(false);
  });

  it("afficherSiPertinente_bannièreEffacéeCôtéServeur_masqueMêmeSansFermetureManuelle", async () => {
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(
        reponse({ id: "b3", message: "Maintenance", type: "warning" }),
      );
    await chargerBanniere();
    expect(visible.value).toBe(true);

    globalThis.fetch = vi.fn().mockResolvedValue(reponse(null));
    await afficherSiPertinente();

    expect(visible.value).toBe(false);
  });

  it("afficherSiPertinente_bannièreAutoMasquéeParLaMinuterie_réapparaîtÀLaNavigation", async () => {
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(
        reponse({ id: "b4", message: "Info courte", type: "info" }),
      );
    await chargerBanniere();
    vi.advanceTimersByTime(10000);
    expect(visible.value).toBe(false);

    await afficherSiPertinente();

    expect(visible.value).toBe(true);
  });

  it("estDismissed_fermetureManuelle_bloqueLAffichageAuPollEtALaNavigation", async () => {
    globalThis.sessionStorage.setItem("banniere_dismissed_id", "b5");
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(
        reponse({ id: "b5", message: "Toujours active", type: "error" }),
      );

    await chargerBanniere();
    expect(visible.value).toBe(false);

    await afficherSiPertinente();
    expect(visible.value).toBe(false);
  });
});

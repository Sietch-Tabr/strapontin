import { describe, expect, it } from "vitest";
import { lireModeAffichage } from "./modeAffichage.js";

const fenetre = ({ standalone = false, ios = false } = {}) => ({
  matchMedia: (requete) => ({
    matches: requete === "(display-mode: standalone)" && standalone,
  }),
  navigator: { standalone: ios },
});

describe("lireModeAffichage", () => {
  it("annonce pwa pour une application installée", () => {
    expect(lireModeAffichage(fenetre({ standalone: true }))).toBe("pwa");
  });

  it("annonce pwa pour une application installée sur iOS", () => {
    expect(lireModeAffichage(fenetre({ ios: true }))).toBe("pwa");
  });

  it("annonce navigateur dans un onglet", () => {
    expect(lireModeAffichage(fenetre())).toBe("navigateur");
  });

  it("annonce navigateur sans matchMedia ni navigator", () => {
    expect(lireModeAffichage({})).toBe("navigateur");
  });

  it("annonce navigateur si l'interrogation échoue", () => {
    const casse = {
      matchMedia() {
        throw new Error("indisponible");
      },
    };
    expect(lireModeAffichage(casse)).toBe("navigateur");
  });
});

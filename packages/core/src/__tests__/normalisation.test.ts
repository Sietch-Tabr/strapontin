import { describe, it, expect } from "vitest";
import { normaliserTexte, normaliserVille, similarite } from "../normalisation.js";

describe("normaliserTexte", () => {
  it("met en minuscules", () => {
    expect(normaliserTexte("RADIOHEAD")).toBe("radiohead");
  });

  it("supprime les accents", () => {
    expect(normaliserTexte("Julien Doré")).toBe("julien dore");
    expect(normaliserTexte("Héléna")).toBe("helena");
  });

  it("remplace les apostrophes par un espace", () => {
    expect(normaliserTexte("N'Golo")).toBe("n golo");
    expect(normaliserTexte("l'artiste")).toBe("l artiste");
  });

  it("remplace les tirets et tirets cadratins par un espace", () => {
    expect(normaliserTexte("Helene-Martin")).toBe("helene martin");
    expect(normaliserTexte("test—valeur")).toBe("test valeur");
    expect(normaliserTexte("test–valeur")).toBe("test valeur");
  });

  it("supprime les caractères spéciaux non alphanumériques", () => {
    expect(normaliserTexte("AC/DC")).toBe("ac dc");
    expect(normaliserTexte("foo.bar")).toBe("foo bar");
  });

  it("normalise les espaces multiples", () => {
    expect(normaliserTexte("  foo   bar  ")).toBe("foo bar");
  });

  it("retourne une chaîne vide inchangée", () => {
    expect(normaliserTexte("")).toBe("");
  });

  it("conserve les chiffres", () => {
    expect(normaliserTexte("Blink-182")).toBe("blink 182");
  });
});

describe("normaliserVille", () => {
  it("abrège saint en st", () => {
    expect(normaliserVille("Saint-Denis")).toBe("st denis");
    expect(normaliserVille("Saint Denis")).toBe("st denis");
  });

  it("abrège sainte en ste", () => {
    expect(normaliserVille("Sainte-Foy")).toBe("ste foy");
  });

  it("ne modifie pas les villes sans saint/sainte", () => {
    expect(normaliserVille("Paris")).toBe("paris");
    expect(normaliserVille("Lyon")).toBe("lyon");
  });

  it("applique aussi la normalisation de base", () => {
    expect(normaliserVille("Île-de-France")).toBe("ile de france");
  });
});

describe("similarite", () => {
  it("retourne 1 pour deux chaînes identiques", () => {
    expect(similarite("radiohead", "radiohead")).toBe(1);
  });

  it("retourne 0 si l'une des chaînes est vide", () => {
    expect(similarite("", "radiohead")).toBe(0);
    expect(similarite("radiohead", "")).toBe(0);
  });

  it("retourne un score élevé pour des variantes proches (>= 0.85)", () => {
    // "radiohead" vs "radioheads" : distance 1, max 10 → sim 0.9
    expect(similarite("radiohead", "radioheads")).toBeGreaterThanOrEqual(0.85);
    // "julien clerc" vs "julien clere" : distance 1, max 12 → sim ~0.917
    expect(similarite("julien clerc", "julien clere")).toBeGreaterThanOrEqual(0.85);
  });

  it("retourne un score faible pour des artistes distincts (< 0.85)", () => {
    expect(similarite("radiohead", "coldplay")).toBeLessThan(0.85);
    expect(similarite("beyonce", "adele")).toBeLessThan(0.85);
  });
});

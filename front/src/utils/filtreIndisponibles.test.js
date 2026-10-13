import { describe, expect, it } from "vitest";
import {
  choixExplicite,
  indisponiblesAffiches,
  messageSansAjout,
  valeurApresBascule,
} from "./filtreIndisponibles.js";

describe("filtre Indisponibles", () => {
  it("ne transmet que les choix explicites de l'URL", () => {
    expect(choixExplicite("1")).toBe("1");
    expect(choixExplicite("0")).toBe("0");
    expect(choixExplicite(undefined)).toBeNull();
    expect(choixExplicite("oui")).toBeNull();
  });

  it("suit l'état renvoyé par le serveur, préférence comprise", () => {
    expect(
      indisponiblesAffiches(undefined, { inclureIndisponibles: true }),
    ).toBe(true);
    expect(indisponiblesAffiches("1", { inclureIndisponibles: false })).toBe(
      false,
    );
    expect(indisponiblesAffiches("1", null)).toBe(true);
  });

  it("inverse l'affichage courant par une valeur explicite", () => {
    expect(valeurApresBascule(true)).toBe("0");
    expect(valeurApresBascule(false)).toBe("1");
  });

  it("signale une inclusion qui n'ajoute aucun concert", () => {
    expect(messageSansAjout(false, { nbIndisponibles: 0 })).toMatch(
      /Aucun concert indisponible/,
    );
    expect(messageSansAjout(false, { nbIndisponibles: 3 })).toBeNull();
    expect(messageSansAjout(true, { nbIndisponibles: 0 })).toBeNull();
  });
});

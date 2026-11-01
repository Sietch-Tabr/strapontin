import { describe, expect, it } from "vitest";
import { classeSituation, libelleSituation } from "./situationConcert.js";

describe("situationConcert", () => {
  it("nomme les trois situations", () => {
    expect(libelleSituation("jumeau")).toBe("Même produit à une autre date");
    expect(libelleSituation("annoncee_ailleurs")).toBe(
      "Annoncé par une autre billetterie",
    );
    expect(libelleSituation("introuvable")).toBe("Introuvable partout");
  });

  it("associe une teinte sémantique à chaque situation", () => {
    expect(classeSituation("jumeau")).toBe("badge badge-info");
    expect(classeSituation("annoncee_ailleurs")).toBe(
      "badge badge-avertissement",
    );
    expect(classeSituation("introuvable")).toBe("badge badge-danger");
  });

  it("retombe sur une situation inconnue sans planter", () => {
    expect(libelleSituation(undefined)).toBe("Inconnue");
    expect(classeSituation("autre")).toBe("badge");
  });
});

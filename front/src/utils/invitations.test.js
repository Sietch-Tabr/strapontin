import { describe, expect, it } from "vitest";
import {
  libelleRestantes,
  libelleStatut,
  peutAgir,
  peutInviter,
} from "./invitations.js";

describe("libelleStatut", () => {
  it("traduit chaque statut", () => {
    expect(libelleStatut("en_attente")).toBe("En attente");
    expect(libelleStatut("activee")).toBe("Activée");
    expect(libelleStatut("expiree")).toBe("Expirée");
    expect(libelleStatut("annulee")).toBe("Annulée");
  });

  it("rend un statut inconnu tel quel", () => {
    expect(libelleStatut("autre")).toBe("autre");
  });
});

describe("peutAgir", () => {
  it("n'autorise le renvoi et l'annulation que pour une invitation en attente", () => {
    expect(peutAgir("en_attente")).toBe(true);
    expect(peutAgir("activee")).toBe(false);
    expect(peutAgir("expiree")).toBe(false);
    expect(peutAgir("annulee")).toBe(false);
  });
});

describe("libelleRestantes", () => {
  it("annonce le plafond atteint avant la saisie", () => {
    expect(libelleRestantes(0, 2)).toMatch(/nombre maximal/);
  });

  it("annonce l'envoi désactivé quand le plafond est nul", () => {
    expect(libelleRestantes(0, 0)).toMatch(/désactivé/);
  });

  it("accorde le pluriel", () => {
    expect(libelleRestantes(1, 2)).toBe(
      "Il vous reste 1 invitation à envoyer.",
    );
    expect(libelleRestantes(2, 2)).toBe(
      "Il vous reste 2 invitations à envoyer.",
    );
  });
});

describe("peutInviter", () => {
  it("exige un plafond non nul et une invitation restante", () => {
    expect(peutInviter(1, 2)).toBe(true);
    expect(peutInviter(0, 2)).toBe(false);
    expect(peutInviter(0, 0)).toBe(false);
    expect(peutInviter(1, 0)).toBe(false);
  });
});

import { describe, it, expect, vi } from "vitest";
import { creerMockEvent } from "./utils";

vi.mock("$lib/server/db", () => ({
  db: {
    select: vi.fn().mockReturnValue({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockResolvedValue([]),
      }),
    }),
  },
}));

const { load } = await import("../../routes/(app)/+layout.server");

describe("layout garde auth", () => {
  it("redirect_login_si_user_null", async () => {
    const event = creerMockEvent({ locals: { user: null } });
    let erreur: unknown;
    try {
      await load(event as any);
      expect.fail("devrait lancer une redirection");
    } catch (e) {
      erreur = e;
    }
    expect(erreur).toHaveProperty("status", 302);
    expect(erreur).toHaveProperty("location", "/login");
  });

  it("retourne_utilisateur_si_authentifie", async () => {
    const utilisateur = { id: "1", role: "user", name: "Test" };
    const event = creerMockEvent({ locals: { user: utilisateur } });
    const resultat = (await load(event as any)) as { utilisateur: unknown };
    expect(resultat.utilisateur).toEqual(utilisateur);
  });
});

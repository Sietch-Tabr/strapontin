import { describe, it, expect, vi, beforeEach } from "vitest";
import { isRedirect } from "@sveltejs/kit";
import { creerMockEvent, creerMockDb } from "./utils";

const mockChangePassword = vi.fn().mockResolvedValue({});
const mockDb = creerMockDb();

vi.mock("$lib/server/db", () => ({ db: mockDb }));
vi.mock("$lib/auth", () => ({
  auth: { api: { changePassword: mockChangePassword } },
}));
vi.mock("$lib/server/queue", () => ({
  queue: { add: vi.fn().mockResolvedValue({ id: "job-1" }) },
}));
vi.mock("$lib/server/email", () => ({
  resendDisponible: vi.fn().mockResolvedValue(false),
  envoyerEmail: vi.fn().mockResolvedValue(undefined),
}));

const { actions } = await import(
  "../../routes/(app)/parametres/+page.server"
);

const userLocals = { user: { id: "user-1", role: "user" } };

beforeEach(() => {
  vi.clearAllMocks();
  mockChangePassword.mockResolvedValue({});
});

describe("parametres supprimerCompte", () => {
  it("supprime_user_et_redirige_vers_login", async () => {
    const event = creerMockEvent({ locals: userLocals });
    await expect(
      actions.supprimerCompte(event as never),
    ).rejects.toSatisfy(isRedirect);
    expect(mockDb.delete).toHaveBeenCalledOnce();
  });
});

describe("parametres motDePasse validations", () => {
  it("champs_vides_fail_400", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: {
        motDePasseActuel: "",
        motDePasseNouveau: "",
        motDePasseConfirmation: "",
      },
    });
    const resultat = await actions.motDePasse(event as never);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("mdp_trop_court_fail_400", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: {
        motDePasseActuel: "ancienmdp",
        motDePasseNouveau: "court",
        motDePasseConfirmation: "court",
      },
    });
    const resultat = await actions.motDePasse(event as never);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("confirmation_differente_fail_400", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: {
        motDePasseActuel: "ancienmdp",
        motDePasseNouveau: "nouveaumdpsecurise",
        motDePasseConfirmation: "differente",
      },
    });
    const resultat = await actions.motDePasse(event as never);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("changePassword_echoue_fail_400", async () => {
    mockChangePassword.mockRejectedValueOnce(new Error("mot de passe incorrect"));
    const event = creerMockEvent({
      locals: userLocals,
      formData: {
        motDePasseActuel: "ancienmdp",
        motDePasseNouveau: "nouveaumdpsecurise",
        motDePasseConfirmation: "nouveaumdpsecurise",
      },
    });
    const resultat = await actions.motDePasse(event as never);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("succes_retourne_motDePasse", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: {
        motDePasseActuel: "ancienmdp",
        motDePasseNouveau: "nouveaumdpsecurise",
        motDePasseConfirmation: "nouveaumdpsecurise",
      },
    });
    const resultat = await actions.motDePasse(event as never);
    expect(resultat).toMatchObject({ succes: "motDePasse" });
  });
});

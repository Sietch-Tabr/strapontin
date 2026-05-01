import { describe, it, expect, vi, beforeEach } from "vitest";
import { isRedirect } from "@sveltejs/kit";
import { creerMockEvent, creerMockDb } from "./utils";

const mockDb = creerMockDb();
vi.mock("$lib/server/db", () => ({ db: mockDb }));
vi.mock("$lib/server/queue", () => ({ queue: { add: vi.fn() } }));
vi.mock("$lib/server/redis", () => ({
  redis: { get: vi.fn().mockResolvedValue(null) },
  CLE_QUOTA_TM: "strapontin:tm:quota",
  cleStatsJob: vi.fn().mockReturnValue("cle-stats"),
}));
vi.mock("$lib/auth", () => ({
  auth: {
    api: {
      signUpEmail: vi.fn().mockResolvedValue({}),
      requestPasswordReset: vi.fn().mockResolvedValue({}),
    },
  },
}));
vi.mock("$lib/server/email", () => ({
  envoyerInvitation: vi.fn().mockResolvedValue(undefined),
}));
vi.mock("$lib/server/config", () => ({
  lireConfig: vi.fn().mockResolvedValue(null),
  sauvegarderConfig: vi.fn().mockResolvedValue(undefined),
  lireConfigsPresentes: vi.fn().mockResolvedValue(new Set()),
}));

const { load, actions } = await import(
  "../../routes/(app)/admin/+page.server"
);

const adminLocals = { user: { id: "admin-1", role: "admin" } };
const userLocals = { user: { id: "user-1", role: "user" } };

beforeEach(() => {
  vi.clearAllMocks();
  mockDb.select.mockReturnValue({
    from: vi.fn().mockReturnValue({
      where: vi.fn().mockResolvedValue([]),
      orderBy: vi.fn().mockResolvedValue([]),
    }),
  });
});

describe("admin load garde", () => {
  it("redirige_si_role_user", async () => {
    const event = creerMockEvent({ locals: userLocals });
    await expect(load(event as any)).rejects.toSatisfy(isRedirect);
  });
});

describe("admin creerCompte validations", () => {
  it("refuse_si_pas_admin", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { email: "test@exemple.fr" },
    });
    const resultat = await actions.creerCompte(event as any);
    expect(resultat).toMatchObject({ status: 403 });
  });

  it("email_vide_fail_400", async () => {
    const event = creerMockEvent({
      locals: adminLocals,
      formData: { email: "" },
    });
    const resultat = await actions.creerCompte(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("email_format_invalide_fail_400", async () => {
    const event = creerMockEvent({
      locals: adminLocals,
      formData: { email: "pasunemail" },
    });
    const resultat = await actions.creerCompte(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("email_sans_domaine_fail_400", async () => {
    const event = creerMockEvent({
      locals: adminLocals,
      formData: { email: "test@" },
    });
    const resultat = await actions.creerCompte(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });
});

describe("admin supprimerCompte", () => {
  it("refuse_si_pas_admin", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { userId: "autre-1" },
    });
    const resultat = await actions.supprimerCompte(event as any);
    expect(resultat).toMatchObject({ status: 403 });
  });

  it("refuse_suppression_soi_meme", async () => {
    const event = creerMockEvent({
      locals: adminLocals,
      formData: { userId: "admin-1" },
    });
    const resultat = await actions.supprimerCompte(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("refuse_userId_vide", async () => {
    const event = creerMockEvent({
      locals: adminLocals,
      formData: { userId: "" },
    });
    const resultat = await actions.supprimerCompte(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });
});

describe("admin modifierCompte validations", () => {
  it("refuse_si_pas_admin", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { userId: "autre-1", nom: "Nouveau" },
    });
    const resultat = await actions.modifierCompte(event as any);
    expect(resultat).toMatchObject({ status: 403 });
  });

  it("userId_vide_fail_400", async () => {
    const event = creerMockEvent({
      locals: adminLocals,
      formData: { userId: "" },
    });
    const resultat = await actions.modifierCompte(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("nom_trop_long_fail_400", async () => {
    const event = creerMockEvent({
      locals: adminLocals,
      formData: { userId: "autre-1", nom: "a".repeat(101) },
    });
    const resultat = await actions.modifierCompte(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("email_format_invalide_fail_400", async () => {
    const event = creerMockEvent({
      locals: adminLocals,
      formData: { userId: "autre-1", email: "pasunemail" },
    });
    const resultat = await actions.modifierCompte(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });
});

import { describe, it, expect, vi, beforeEach } from "vitest";
import { creerMockEvent } from "./utils";

const mockSelect = vi.fn();
const mockFrom = vi.fn();
const mockWhere = vi.fn();

vi.mock("$lib/server/db", () => ({
  db: {
    select: mockSelect,
    insert: vi.fn().mockReturnValue({
      values: vi.fn().mockResolvedValue(undefined),
    }),
    delete: vi.fn().mockReturnValue({
      where: vi.fn().mockResolvedValue(undefined),
    }),
    $count: vi.fn().mockResolvedValue(0),
  },
}));
vi.mock("$lib/server/queue", () => ({
  queue: { add: vi.fn().mockResolvedValue({ id: "job-1" }) },
}));
vi.mock("$lib/server/config", () => ({
  lireConfig: vi.fn().mockResolvedValue(null),
}));

const { actions } = await import("../../routes/(app)/salles/+page.server");

const userLocals = { user: { id: "user-1", role: "user" } };

beforeEach(() => {
  vi.clearAllMocks();
  let appelWhere = 0;
  mockWhere.mockImplementation(async () => {
    appelWhere++;
    return appelWhere === 1 ? [] : [{ total: 0 }];
  });
  mockFrom.mockReturnValue({ where: mockWhere, limit: vi.fn().mockReturnValue({ where: mockWhere }) });
  mockSelect.mockReturnValue({ from: mockFrom });
});

describe("salles ajouter validations", () => {
  it("salleId_vide_fail_400", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { salleId: "", tmVenueId: "" },
    });
    const resultat = await actions.ajouter(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("salleId_trop_long_200_chars_fail_400", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { salleId: "a".repeat(201), tmVenueId: "" },
    });
    const resultat = await actions.ajouter(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("salleId_200_chars_exactement_accepte", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { salleId: "a".repeat(200), tmVenueId: "" },
    });
    const resultat = await actions.ajouter(event as any);
    expect(resultat).toMatchObject({ succes: true });
  });

  it("salle_doublon_fail_409", async () => {
    mockWhere.mockResolvedValueOnce([{ salleId: "salle-existante" }]);
    const event = creerMockEvent({
      locals: userLocals,
      formData: { salleId: "salle-existante", tmVenueId: "" },
    });
    const resultat = await actions.ajouter(event as any);
    expect(resultat).toMatchObject({ status: 409 });
  });

  it("cap_200_atteint_fail_400", async () => {
    mockWhere
      .mockResolvedValueOnce([]) // pas de doublon
      .mockResolvedValueOnce([{ total: 200 }]); // cap atteint
    const event = creerMockEvent({
      locals: userLocals,
      formData: { salleId: "nouvelle-salle", tmVenueId: "" },
    });
    const resultat = await actions.ajouter(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });
});

describe("salles supprimer validations", () => {
  it("salleId_vide_fail_400", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { salleId: "" },
    });
    const resultat = await actions.supprimer(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("suppression_reussie", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { salleId: "la-cigale-paris" },
    });
    const resultat = await actions.supprimer(event as any);
    expect(resultat).toMatchObject({ succes: true });
  });
});

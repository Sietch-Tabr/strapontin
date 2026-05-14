import { describe, it, expect, vi, beforeEach } from "vitest";
import { creerMockEvent } from "./utils";

const mockSelect = vi.fn();
const mockFrom = vi.fn();
const mockWhere = vi.fn();
const mockOrderBy = vi.fn();

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

const { actions } = await import("../../routes/(app)/artistes/+page.server");

const userLocals = { user: { id: "user-1", role: "user" } };

beforeEach(() => {
  vi.clearAllMocks();
  // Par défaut : 1er where = pas de doublon, 2e = count à 0 ; tous retournent aussi .limit()
  let appelWhere = 0;
  mockWhere.mockImplementation(() => {
    appelWhere++;
    const valeur = appelWhere === 1 ? [] : [{ total: 0 }];
    return Object.assign(Promise.resolve(valeur), { limit: vi.fn().mockResolvedValue([]) });
  });
  mockOrderBy.mockResolvedValue([]);
  mockFrom.mockReturnValue({ where: mockWhere, orderBy: mockOrderBy, limit: vi.fn().mockReturnValue({ where: mockWhere }) });
  mockSelect.mockReturnValue({ from: mockFrom });
});

describe("artistes ajouter validations", () => {
  it("nom_vide_fail_400", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { nom: "" },
    });
    const resultat = await actions.ajouter(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("nom_trop_long_200_chars_fail_400", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { nom: "a".repeat(201) },
    });
    const resultat = await actions.ajouter(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("nom_200_chars_exactement_accepte", async () => {
    // Valeur limite : 200 chars ← accepté
    const event = creerMockEvent({
      locals: userLocals,
      formData: { nom: "a".repeat(200) },
    });
    // Le mock DB retourne [] (pas de doublon) puis 0 pour $count
    const resultat = await actions.ajouter(event as any);
    expect(resultat).toMatchObject({ succes: true });
  });

  it("artiste_doublon_fail_409", async () => {
    // Simuler qu'un artiste existe déjà
    mockWhere.mockResolvedValueOnce([{ artisteNormalise: "mika" }]);
    const event = creerMockEvent({
      locals: userLocals,
      formData: { nom: "Mika" },
    });
    const resultat = await actions.ajouter(event as any);
    expect(resultat).toMatchObject({ status: 409 });
  });

  it("cap_500_atteint_fail_400", async () => {
    // Premier select (vérif doublon) → [], deuxième select (count) → [{ total: 500 }]
    mockWhere
      .mockResolvedValueOnce([]) // pas de doublon
      .mockResolvedValueOnce([{ total: 500 }]); // cap atteint
    const event = creerMockEvent({
      locals: userLocals,
      formData: { nom: "NouvelArtiste" },
    });
    const resultat = await actions.ajouter(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });
});

describe("artistes supprimer validations", () => {
  it("artisteNormalise_vide_fail_400", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { artisteNormalise: "" },
    });
    const resultat = await actions.supprimer(event as any);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("suppression_reussie", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { artisteNormalise: "mika" },
    });
    const resultat = await actions.supprimer(event as any);
    expect(resultat).toMatchObject({ succes: true });
  });
});

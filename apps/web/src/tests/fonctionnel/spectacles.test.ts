import { describe, it, expect, vi, beforeEach } from "vitest";
import { isHttpError } from "@sveltejs/kit";
import { creerMockEvent, creerMockDb } from "./utils";

const mockDb = creerMockDb();
vi.mock("$lib/server/db", () => ({ db: mockDb }));
vi.mock("$lib/server/filtres", () => ({
  lireFiltresStatut: vi.fn().mockResolvedValue({ inclureIndisponibles: false }),
  construireConditionStatut: vi.fn().mockReturnValue(null),
}));

const { actions } = await import("../../routes/(app)/spectacles/+page.server");

const userLocals = { user: { id: "user-1", role: "user" } };

beforeEach(() => {
  vi.clearAllMocks();
});

describe("spectacles demasquer validations", () => {
  it("concertId_manquant_error_400", async () => {
    const event = creerMockEvent({ locals: userLocals, formData: {} });
    await expect(actions.demasquer(event as never)).rejects.toSatisfy((e) =>
      isHttpError(e, 400),
    );
  });

  it("concertId_vide_error_400", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { concertId: "" },
    });
    await expect(actions.demasquer(event as never)).rejects.toSatisfy((e) =>
      isHttpError(e, 400),
    );
  });

  it("demasquer_valide_met_a_jour_db", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { concertId: "concert-123" },
    });
    await expect(actions.demasquer(event as never)).resolves.toBeUndefined();
    expect(mockDb.update).toHaveBeenCalledOnce();
  });
});

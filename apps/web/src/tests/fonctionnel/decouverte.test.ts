import { describe, it, expect, vi, beforeEach } from "vitest";
import { creerMockEvent, creerMockDb } from "./utils";

const mockQueueAdd = vi.fn().mockResolvedValue({ id: "job-1" });
const mockRedisSet = vi.fn().mockResolvedValue("OK");
const mockDb = creerMockDb();

vi.mock("$lib/server/db", () => ({ db: mockDb }));
vi.mock("$lib/server/queue", () => ({ queue: { add: mockQueueAdd } }));
vi.mock("$lib/server/redis", () => ({
  redis: { get: vi.fn().mockResolvedValue(null), set: mockRedisSet },
  cleRegionFetchee: (dept: string) => `strapontin:region:fetched:${dept}`,
  TTL_REGION_FETCHEE_S: 691200,
  SEUIL_STALENESS_MS: 7_200_000,
}));

const { actions } = await import("../../routes/(app)/decouverte/+page.server");

const userLocals = { user: { id: "user-1", role: "user" } };

beforeEach(() => {
  vi.clearAllMocks();
  mockQueueAdd.mockResolvedValue({ id: "job-1" });
  mockRedisSet.mockResolvedValue("OK");
  mockDb.select.mockReturnValue({
    from: vi.fn().mockReturnValue({
      where: vi.fn().mockResolvedValue([]),
    }),
  });
});

describe("decouverte fetcherRegion validations", () => {
  it("dept_vide_fail_400", async () => {
    const event = creerMockEvent({ locals: userLocals, formData: {} });
    const resultat = await actions.fetcherRegion(event as never);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("dept_format_invalide_filtre_fail_400", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { dept: ["INVALIDE", "XY9"] },
    });
    const resultat = await actions.fetcherRegion(event as never);
    expect(resultat).toMatchObject({ status: 400 });
  });

  it("dept_valide_lance_job_et_retourne_succes", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { dept: ["75"] },
    });
    const resultat = await actions.fetcherRegion(event as never);
    expect(resultat).toMatchObject({ success: true });
    expect(mockQueueAdd).toHaveBeenCalledOnce();
    expect(mockRedisSet).toHaveBeenCalledOnce();
  });

  it("plusieurs_depts_valides_retourne_succes", async () => {
    const event = creerMockEvent({
      locals: userLocals,
      formData: { dept: ["75", "69", "33"] },
    });
    const resultat = await actions.fetcherRegion(event as never);
    expect(resultat).toMatchObject({ success: true });
    expect(mockRedisSet).toHaveBeenCalledTimes(3);
  });
});

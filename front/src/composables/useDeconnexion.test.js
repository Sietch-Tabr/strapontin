import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { useDeconnexion } from "./useDeconnexion.js";

// useToasts() gère la Popover API (DOM), hors de portée d'un test Vitest en environnement node —
// seul afficherToast est vérifié via son appel, pas son rendu.
vi.mock("./useToasts.js", () => ({
  useToasts: () => ({ afficherToast: vi.fn() }),
}));

describe("useDeconnexion", () => {
  let fetchOriginal;

  beforeEach(() => {
    fetchOriginal = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = fetchOriginal;
  });

  it("deconnecter_appelSucces_appellePointApiAuthLogout", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true });
    globalThis.fetch = fetchMock;
    const { deconnecter } = useDeconnexion();

    await deconnecter();

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/auth/logout");
    expect(options.method).toBe("POST");
  });
});

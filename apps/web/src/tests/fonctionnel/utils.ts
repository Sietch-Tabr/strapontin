import { vi } from "vitest";

export function creerMockEvent(options: {
  locals?: Record<string, unknown>;
  formData?: Record<string, string | string[]>;
  searchParams?: Record<string, string>;
} = {}) {
  const { locals = {}, formData = {}, searchParams = {} } = options;

  const form = new FormData();
  for (const [cle, valeur] of Object.entries(formData)) {
    if (Array.isArray(valeur)) {
      valeur.forEach((v) => form.append(cle, v));
    } else {
      form.set(cle, valeur);
    }
  }

  const url = new URL("http://localhost/test");
  for (const [cle, valeur] of Object.entries(searchParams)) {
    url.searchParams.set(cle, valeur);
  }

  return {
    locals: { user: null, ...locals },
    request: {
      formData: vi.fn().mockResolvedValue(form),
      headers: new Headers(),
    } as unknown as Request,
    url,
    params: {},
    cookies: { get: vi.fn(), set: vi.fn(), delete: vi.fn() },
    fetch: vi.fn(),
    getClientAddress: () => "127.0.0.1",
    platform: undefined,
    route: { id: "/test" },
    setHeaders: vi.fn(),
    depends: vi.fn(),
    parent: vi.fn(),
    untrack: vi.fn(),
  };
}

// Mock db Drizzle — les selects répondent séquentiellement selon le tableau fourni.
// Chaque appel à db.select().from(...).where(...) consomme le prochain résultat.
export function creerMockDb(resultsSequentiels: unknown[][] = []) {
  let appel = 0;

  const where = vi.fn().mockImplementation(() =>
    Promise.resolve(resultsSequentiels[appel++] ?? []),
  );

  const from = vi.fn().mockReturnValue({ where });

  return {
    select: vi.fn().mockReturnValue({ from }),
    insert: vi.fn().mockReturnValue({
      values: vi.fn().mockReturnValue({
        onConflictDoUpdate: vi.fn().mockResolvedValue(undefined),
      }),
    }),
    update: vi.fn().mockReturnValue({
      set: vi.fn().mockReturnValue({
        where: vi.fn().mockResolvedValue(undefined),
      }),
    }),
    delete: vi.fn().mockReturnValue({
      where: vi.fn().mockResolvedValue(undefined),
    }),
    $count: vi.fn().mockResolvedValue(0),
  };
}

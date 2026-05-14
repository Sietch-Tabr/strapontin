import { describe, it, expect, vi, beforeEach } from "vitest";

vi.mock("$app/environment", () => ({ building: false, dev: false }));

vi.mock("@sveltejs/kit/hooks", () => ({
  // sequence retourne le dernier handler pour tester gererAuth en isolation
  sequence: (...handlers: ((...args: unknown[]) => unknown)[]) =>
    handlers[handlers.length - 1],
}));

vi.mock("@sveltejs/kit", () => ({
  redirect: (status: number, location: string) => {
    throw Object.assign(new Error(), { status, location });
  },
  error: (status: number, message: string) => {
    throw Object.assign(new Error(), { status, body: { message } });
  },
}));

const mockGetSession = vi.fn();
vi.mock("$lib/auth", () => ({
  auth: { api: { getSession: mockGetSession } },
}));

vi.mock("better-auth/svelte-kit", () => ({
  svelteKitHandler: vi.fn().mockResolvedValue(new Response("ok")),
}));

vi.mock("$lib/server/db", () => ({
  db: {
    select: vi.fn().mockReturnValue({
      from: vi.fn().mockReturnValue({
        where: vi.fn().mockResolvedValue([]),
      }),
    }),
  },
  fermerDb: vi.fn(),
}));

vi.mock("$lib/server/reseau", () => ({
  estAdresseIP: vi.fn().mockReturnValue(false),
}));

vi.mock("@strapontin/core/schema", () => ({
  userConfig: {},
}));

vi.mock("drizzle-orm", () => ({
  eq: vi.fn().mockReturnValue({}),
}));

const { handle } = await import("../../hooks.server");
import { svelteKitHandler } from "better-auth/svelte-kit";

function creerEventHook(pathname: string, sessionUtilisateur?: unknown) {
  return {
    url: new URL(`http://localhost${pathname}`),
    request: { headers: new Headers() } as unknown as Request,
    cookies: { get: vi.fn().mockReturnValue(null), set: vi.fn(), delete: vi.fn() },
    locals: {} as App.Locals,
    params: {},
    route: { id: pathname },
    setHeaders: vi.fn(),
    depends: vi.fn(),
    fetch: vi.fn(),
    getClientAddress: () => "127.0.0.1",
    platform: undefined,
    _sessionMock: sessionUtilisateur,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(svelteKitHandler).mockResolvedValue(new Response("ok"));
});

const sessionAdmin = {
  session: { id: "s1" },
  user: { id: "u1", role: "admin", twoFactorEnabled: true, mustChangeCredentials: false },
};

const sessionAdminSans2FA = {
  session: { id: "s1" },
  user: { id: "u1", role: "admin", twoFactorEnabled: false, mustChangeCredentials: false },
};

describe("gererAuth — guard non-authentifié", () => {
  it("redirect_login_si_pas_de_session", async () => {
    mockGetSession.mockResolvedValue(null);
    const event = creerEventHook("/spectacles");

    let erreur: Record<string, unknown> | undefined;
    try {
      await handle({ event: event as never, resolve: vi.fn() });
    } catch (e) {
      erreur = e as Record<string, unknown>;
    }
    expect(erreur?.status).toBe(302);
    expect(erreur?.location).toBe("/login");
  });

  it("ne_pas_redirect_pour_route_publique_login", async () => {
    const event = creerEventHook("/login");

    await expect(
      handle({ event: event as never, resolve: vi.fn() }),
    ).resolves.toBeDefined();

    expect(mockGetSession).not.toHaveBeenCalled();
  });

  it("ne_pas_redirect_pour_api_push_route_publique", async () => {
    const event = creerEventHook("/api/push");

    await expect(
      handle({ event: event as never, resolve: vi.fn() }),
    ).resolves.toBeDefined();

    expect(mockGetSession).not.toHaveBeenCalled();
  });
});

describe("gererAuth — guard 2FA admin", () => {
  it("admin_avec_2fa_accede_aux_pages_protegees", async () => {
    mockGetSession.mockResolvedValue(sessionAdmin);
    const event = creerEventHook("/spectacles");

    await expect(
      handle({ event: event as never, resolve: vi.fn() }),
    ).resolves.toBeDefined();
  });

  it("admin_sans_2fa_redirect_vers_parametres", async () => {
    mockGetSession.mockResolvedValue(sessionAdminSans2FA);
    const event = creerEventHook("/spectacles");

    let erreur: Record<string, unknown> | undefined;
    try {
      await handle({ event: event as never, resolve: vi.fn() });
    } catch (e) {
      erreur = e as Record<string, unknown>;
    }
    expect(erreur?.status).toBe(302);
    expect(erreur?.location).toBe("/parametres#deux-facteurs");
  });

  it("admin_sans_2fa_redirect_meme_sur_routes_api_F1_fix", async () => {
    // F1 — les routes /api/* ne bypassent plus le guard 2FA
    mockGetSession.mockResolvedValue(sessionAdminSans2FA);
    const event = creerEventHook("/api/concerts");

    let erreur: Record<string, unknown> | undefined;
    try {
      await handle({ event: event as never, resolve: vi.fn() });
    } catch (e) {
      erreur = e as Record<string, unknown>;
    }
    expect(erreur?.status).toBe(302);
    expect(erreur?.location).toBe("/parametres#deux-facteurs");
  });

  it("admin_sans_2fa_accede_a_parametres_pour_configurer_2fa", async () => {
    mockGetSession.mockResolvedValue(sessionAdminSans2FA);
    const event = creerEventHook("/parametres");

    await expect(
      handle({ event: event as never, resolve: vi.fn() }),
    ).resolves.toBeDefined();
  });

  it("user_sans_2fa_accede_normalement", async () => {
    const sessionUserSans2FA = {
      session: { id: "s1" },
      user: { id: "u1", role: "user", twoFactorEnabled: false, mustChangeCredentials: false },
    };
    mockGetSession.mockResolvedValue(sessionUserSans2FA);
    const event = creerEventHook("/spectacles");

    await expect(
      handle({ event: event as never, resolve: vi.fn() }),
    ).resolves.toBeDefined();
  });
});

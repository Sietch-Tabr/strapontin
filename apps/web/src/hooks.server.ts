import { auth } from "$lib/auth";
import { svelteKitHandler } from "better-auth/svelte-kit";
import { redirect } from "@sveltejs/kit";
import { building } from "$app/environment";
import { sequence } from "@sveltejs/kit/hooks";
import type { Handle } from "@sveltejs/kit";
import { db } from "$lib/server/db";
import { userConfig } from "@strapontin/core/schema";
import { eq } from "drizzle-orm";

const ROUTES_PUBLIQUES = ["/login", "/setup", "/health"];

const securiser: Handle = async ({ event, resolve }) => {
  const reponse = await resolve(event);
  reponse.headers.set("X-Content-Type-Options", "nosniff");
  reponse.headers.set("X-Frame-Options", "DENY");
  reponse.headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
  reponse.headers.set(
    "Permissions-Policy",
    "camera=(), microphone=(), geolocation=()"
  );
  reponse.headers.set(
    "Strict-Transport-Security",
    "max-age=31536000; includeSubDomains"
  );
  reponse.headers.set("X-Robots-Tag", "noindex, nofollow");
  reponse.headers.set(
    "Content-Security-Policy",
    "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'"
  );
  return reponse;
};

const gererAuth: Handle = async ({ event, resolve }) => {
  // Routes de l'API Better Auth : thème depuis le cookie uniquement
  if (event.url.pathname.startsWith("/api/auth")) {
    const theme = event.cookies.get("theme") ?? "auto";
    event.locals.theme = theme as "auto" | "clair" | "sombre";
    return svelteKitHandler({
      event,
      resolve: (event) =>
        resolve(event, {
          transformPageChunk: ({ html }) =>
            theme === "sombre"
              ? html.replace("<html", '<html class="dark"')
              : html,
        }),
      auth,
      building,
    });
  }

  // Routes publiques : thème depuis le cookie uniquement
  if (ROUTES_PUBLIQUES.includes(event.url.pathname)) {
    const theme = event.cookies.get("theme") ?? "auto";
    event.locals.theme = theme as "auto" | "clair" | "sombre";
    return svelteKitHandler({
      event,
      resolve: (event) =>
        resolve(event, {
          transformPageChunk: ({ html }) =>
            theme === "sombre"
              ? html.replace("<html", '<html class="dark"')
              : html,
        }),
      auth,
      building,
    });
  }

  // Toutes les autres routes nécessitent une session
  const session = await auth.api.getSession({
    headers: event.request.headers,
  });

  if (!session) {
    redirect(302, "/login");
  }

  event.locals.session = session.session;
  event.locals.user = session.user;

  // Première connexion : forcer le choix des identifiants
  if (
    session.user.mustChangeCredentials &&
    event.url.pathname !== "/first-login"
  ) {
    redirect(302, "/first-login");
  }

  // Résoudre le thème : cookie en priorité.
  // Si absent (nouvel appareil), lire la préférence en DB et poser le cookie.
  let theme = event.cookies.get("theme") as "auto" | "clair" | "sombre" | undefined;
  if (!theme) {
    const config = await db
      .select({ theme: userConfig.theme })
      .from(userConfig)
      .where(eq(userConfig.userId, session.user.id))
      .then((rows) => rows[0] ?? null);
    theme = (config?.theme as "auto" | "clair" | "sombre" | undefined) ?? "auto";
    event.cookies.set("theme", theme, {
      path: "/",
      maxAge: 60 * 60 * 24 * 365,
      httpOnly: false,
      sameSite: "lax",
    });
  }
  event.locals.theme = theme;

  return svelteKitHandler({
    event,
    resolve: (event) =>
      resolve(event, {
        transformPageChunk: ({ html }) =>
          theme === "sombre"
            ? html.replace("<html", '<html class="dark"')
            : html,
      }),
    auth,
    building,
  });
};

export const handle: Handle = sequence(securiser, gererAuth);

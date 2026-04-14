import { auth } from "$lib/auth";
import { svelteKitHandler } from "better-auth/svelte-kit";
import { redirect } from "@sveltejs/kit";
import { building } from "$app/environment";
import { sequence } from "@sveltejs/kit/hooks";
import type { Handle } from "@sveltejs/kit";

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
  return reponse;
};

const gererAuth: Handle = async ({ event, resolve }) => {
  const theme = event.cookies.get("theme") ?? "light";
  event.locals.theme = theme;

  // Routes de l'API Better Auth : toujours autorisées
  if (event.url.pathname.startsWith("/api/auth")) {
    return svelteKitHandler({
      event,
      resolve: (event) =>
        resolve(event, {
          transformPageChunk: ({ html }) =>
            theme === "dark"
              ? html.replace("<html", '<html class="dark"')
              : html,
        }),
      auth,
      building,
    });
  }

  // Routes publiques : pas de vérification de session
  if (ROUTES_PUBLIQUES.includes(event.url.pathname)) {
    return svelteKitHandler({
      event,
      resolve: (event) =>
        resolve(event, {
          transformPageChunk: ({ html }) =>
            theme === "dark"
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

  return svelteKitHandler({
    event,
    resolve: (event) =>
      resolve(event, {
        transformPageChunk: ({ html }) =>
          theme === "dark" ? html.replace("<html", '<html class="dark"') : html,
      }),
    auth,
    building,
  });
};

export const handle: Handle = sequence(securiser, gererAuth);

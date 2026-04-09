import { auth } from "$lib/auth";
import { svelteKitHandler } from "better-auth/svelte-kit";
import { redirect } from "@sveltejs/kit";
import { building } from "$app/environment";
import type { Handle } from "@sveltejs/kit";

const ROUTES_PUBLIQUES = ["/login", "/setup", "/health"];

export const handle: Handle = async ({ event, resolve }) => {
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

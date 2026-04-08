import { auth } from "$lib/auth";
import { svelteKitHandler } from "better-auth/svelte-kit";
import type { Handle } from "@sveltejs/kit";

export const handle: Handle = async ({ event, resolve }) => {
  const theme = event.cookies.get("theme") ?? "light";
  event.locals.theme = theme;

  return svelteKitHandler({
    event,
    resolve: (event) =>
      resolve(event, {
        // Injection SSR de la classe dark sur <html> — évite le FOUC
        transformPageChunk: ({ html }) =>
          theme === "dark" ? html.replace("<html", '<html class="dark"') : html,
      }),
    auth,
  });
};

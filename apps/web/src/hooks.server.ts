import { auth } from "$lib/auth";
import { svelteKitHandler } from "better-auth/svelte-kit";
import { redirect } from "@sveltejs/kit";
import { building, dev } from "$app/environment";
import { sequence } from "@sveltejs/kit/hooks";
import type { Handle } from "@sveltejs/kit";
import { db, fermerDb } from "$lib/server/db";
import { userConfig } from "@strapontin/core/schema";
import { eq } from "drizzle-orm";

if (!building) {
  process.on("sveltekit:shutdown", async () => {
    await fermerDb();
    process.exit(0);
  });
}

const ROUTES_PUBLIQUES = ["/login", "/setup", "/health"];

// Bloque les accès directs par adresse IP (ex. http://192.168.20.79:3000)
// qui contournent Nginx Proxy Manager. Autorise localhost (healthcheck) et tout nom de domaine.
const estAdresseIP = (hote: string) => /^[\d.]+(?::\d+)?$/.test(hote);

const bloquerAccesDirectIP: Handle = async ({ event, resolve }) => {
  if (dev) return resolve(event);
  const hote = event.request.headers.get("host") ?? "";
  if (estAdresseIP(hote)) {
    return new Response("Accès non autorisé", { status: 403 });
  }
  return resolve(event);
};

const THEMES_VALIDES = new Set(["auto", "clair", "sombre"]);

// Normalise les anciennes valeurs de cookie ("dark"/"light") vers le nouveau format.
// Retourne null si la valeur est absente ou inconnue → déclenchera la lecture en DB.
function normaliserTheme(
  valeur: string | undefined,
): "auto" | "clair" | "sombre" | null {
  if (!valeur) return null;
  if (THEMES_VALIDES.has(valeur)) return valeur as "auto" | "clair" | "sombre";
  if (valeur === "dark") return "sombre";
  if (valeur === "light") return "clair";
  return null;
}

const securiser: Handle = async ({ event, resolve }) => {
  const reponse = await resolve(event);
  // CSP géré par svelte.config.js (mode nonce)
  // Les headers ci-dessous sont aussi posés par NPM — fallback si accès direct à l'app
  reponse.headers.set("X-Robots-Tag", "noindex, nofollow");
  reponse.headers.set("Cross-Origin-Opener-Policy", "same-origin");
  reponse.headers.set("Cross-Origin-Resource-Policy", "same-origin");
  reponse.headers.set("X-Frame-Options", "DENY");
  reponse.headers.set("X-Content-Type-Options", "nosniff");
  reponse.headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
  reponse.headers.set("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
  if (!dev) {
    reponse.headers.set(
      "Strict-Transport-Security",
      "max-age=63072000; includeSubDomains",
    );
  }
  return reponse;
};

const gererAuth: Handle = async ({ event, resolve }) => {
  // Routes de l'API Better Auth : thème depuis le cookie uniquement
  if (event.url.pathname.startsWith("/api/auth")) {
    const theme = normaliserTheme(event.cookies.get("theme")) ?? "auto";
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
  }

  // Routes publiques : thème depuis le cookie uniquement
  if (ROUTES_PUBLIQUES.includes(event.url.pathname)) {
    const theme = normaliserTheme(event.cookies.get("theme")) ?? "auto";
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

  // Résoudre le thème : cookie en priorité (normalisé pour compat anciens cookies).
  // Si absent ou invalide (nouvel appareil), lire la préférence en DB et poser le cookie.
  let theme = normaliserTheme(event.cookies.get("theme"));
  if (!theme) {
    const config = await db
      .select({ theme: userConfig.theme })
      .from(userConfig)
      .where(eq(userConfig.userId, session.user.id))
      .then((rows) => rows[0] ?? null);
    theme = normaliserTheme(config?.theme) ?? "auto";
    event.cookies.set("theme", theme, {
      path: "/",
      maxAge: 60 * 60 * 24 * 365,
      httpOnly: false,
      secure: true,
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

export const handle: Handle = sequence(
  bloquerAccesDirectIP,
  securiser,
  gererAuth,
);

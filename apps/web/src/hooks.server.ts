import { env } from "$env/dynamic/private";
import { redirect } from "@sveltejs/kit";
import { dev } from "$app/environment";
import { sequence } from "@sveltejs/kit/hooks";
import type { Handle } from "@sveltejs/kit";
import { estAdresseIP } from "$lib/server/reseau";

const JAVA_API_URL = env.JAVA_API_URL ?? "http://localhost:8080";

const ROUTES_PUBLIQUES = new Set([
  "/login",
  "/forgot-password",
  "/reset-password",
  "/setup",
  "/health",
]);

const bloquerAccesDirectIP: Handle = async ({ event, resolve }) => {
  if (dev) return resolve(event);
  const hote = event.request.headers.get("host") ?? "";
  if (estAdresseIP(hote)) {
    return new Response("Accès non autorisé", { status: 403 });
  }
  return resolve(event);
};

const THEMES_VALIDES = new Set(["auto", "clair", "sombre"]);

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
  reponse.headers.set("X-Robots-Tag", "noindex, nofollow");
  reponse.headers.set("Cross-Origin-Opener-Policy", "same-origin");
  reponse.headers.set("Cross-Origin-Resource-Policy", "same-origin");
  reponse.headers.set("X-Frame-Options", "DENY");
  reponse.headers.set("X-Content-Type-Options", "nosniff");
  reponse.headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
  reponse.headers.set(
    "Permissions-Policy",
    "geolocation=(), camera=(), microphone=()",
  );
  if (!dev) {
    reponse.headers.set(
      "Strict-Transport-Security",
      "max-age=63072000; includeSubDomains",
    );
  }
  return reponse;
};

const gererAuth: Handle = async ({ event, resolve }) => {
  const chemin = event.url.pathname;

  // Routes proxiées vers Java : pas de vérification de session ici
  if (chemin.startsWith("/api/")) {
    event.locals.theme = normaliserTheme(event.cookies.get("theme")) ?? "auto";
    return resolve(event);
  }

  // Routes publiques : thème depuis le cookie uniquement
  if (ROUTES_PUBLIQUES.has(chemin)) {
    const theme = normaliserTheme(event.cookies.get("theme")) ?? "auto";
    event.locals.theme = theme;
    return resolve(event, {
      transformPageChunk: ({ html }) =>
        theme === "sombre"
          ? html.replace("<html", '<html class="dark"')
          : html,
    });
  }

  // Routes authentifiées : vérifier la session via Java
  const sessionCookie = event.cookies.get("SESSION");
  if (!sessionCookie) {
    redirect(302, "/login");
  }

  let utilisateur: App.Locals["user"] = null;
  try {
    const reponse = await fetch(`${JAVA_API_URL}/api/auth/me`, {
      headers: { Cookie: `SESSION=${sessionCookie}` },
    });
    if (!reponse.ok) {
      redirect(302, "/login");
    }
    utilisateur = (await reponse.json()) as App.Locals["user"];
  } catch {
    redirect(302, "/login");
  }

  event.locals.user = utilisateur;

  // Première connexion
  if (utilisateur?.mustChangeCredentials && chemin !== "/first-login") {
    redirect(302, "/first-login");
  }

  // Admin sans 2FA → guard désactivé en Phase 5 (réactiver post-bascule)

  // Thème : cookie en priorité, sinon lecture depuis la config Java
  let theme = normaliserTheme(event.cookies.get("theme"));
  if (!theme) {
    try {
      const rep = await fetch(`${JAVA_API_URL}/api/user/config`, {
        headers: { Cookie: `SESSION=${sessionCookie}` },
      });
      if (rep.ok) {
        const config = (await rep.json()) as { theme?: string };
        theme = normaliserTheme(config.theme) ?? "auto";
        event.cookies.set("theme", theme, {
          path: "/",
          maxAge: 60 * 60 * 24 * 365,
          httpOnly: false,
          secure: !dev,
          sameSite: "lax",
        });
      }
    } catch {
      // Fallback silencieux
    }
    if (!theme) theme = "auto";
  }
  event.locals.theme = theme;

  return resolve(event, {
    transformPageChunk: ({ html }) =>
      theme === "sombre"
        ? html.replace("<html", '<html class="dark"')
        : html,
  });
};

export const handle: Handle = sequence(
  bloquerAccesDirectIP,
  securiser,
  gererAuth,
);

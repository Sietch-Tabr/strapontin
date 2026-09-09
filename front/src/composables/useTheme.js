import { onMounted, onUnmounted } from "vue";

// Valeur littérale imposée par la spec CSS media queries ("light"/"dark" uniquement) — jamais
// traduisible, contrairement aux classes applicatives. Exportée pour rester vérifiable par un test
// sans monter de composant (cf. useTheme.test.js).
export const MEDIA_QUERY_PREFERENCE_SOMBRE = "(prefers-color-scheme: dark)";

const prefSombre =
  typeof window !== "undefined"
    ? window.matchMedia(MEDIA_QUERY_PREFERENCE_SOMBRE)
    : null;

function appliquerTheme(valeur) {
  const sombre =
    valeur === "sombre" || (valeur === "auto" && !!prefSombre?.matches);
  document.documentElement.classList.toggle("sombre", sombre);
}

function lireThemeCookie() {
  return document.cookie.match(/(?:^|; )theme=([^;]*)/)?.[1] ?? "auto";
}

function enregistrerTheme(valeur) {
  document.cookie =
    "theme=" +
    valeur +
    "; path=/; max-age=" +
    365 * 24 * 3600 +
    "; SameSite=Strict";
  try {
    localStorage.setItem("strapontin-theme", valeur);
  } catch (e) {
    console.error("Échec écriture localStorage theme", e);
  }
  appliquerTheme(valeur);
}

export function useTheme() {
  let ecouteurAuto = null;

  onMounted(() => {
    const theme = lireThemeCookie();
    try {
      localStorage.setItem("strapontin-theme", theme);
    } catch (e) {
      console.error("Échec écriture localStorage theme", e);
    }
    appliquerTheme(theme);

    if (theme === "auto") {
      ecouteurAuto = () => appliquerTheme("auto");
      prefSombre.addEventListener("change", ecouteurAuto);
    }
  });

  onUnmounted(() => {
    if (ecouteurAuto) {
      prefSombre.removeEventListener("change", ecouteurAuto);
    }
  });

  return { enregistrerTheme, lireThemeCookie };
}

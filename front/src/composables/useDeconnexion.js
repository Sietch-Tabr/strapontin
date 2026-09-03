import { useToasts } from "./useToasts.js";

// Déconnexion partagée entre SidebarNav.vue (desktop) et DrawerNav.vue (mobile) —
// jusqu'ici dupliquée à l'identique dans les deux fichiers, sans retour utilisateur en cas
// de coupure réseau (le clic semblait alors ne rien faire).
export function useDeconnexion() {
  const { afficherToast } = useToasts();

  async function deconnecter() {
    try {
      // /api/auth/logout (AuthController), pas /logout : effacement explicite et contrôlé
      // du cookie SESSION, plutôt que de dépendre du filtre de logout par défaut de
      // Spring Security (non désactivé mais non testé/documenté comme chemin officiel).
      await fetch("/api/auth/logout", { method: "POST" });
      window.location.href = "/login";
    } catch (_) {
      afficherToast("Erreur réseau lors de la déconnexion.", "erreur");
    }
  }

  return { deconnecter };
}

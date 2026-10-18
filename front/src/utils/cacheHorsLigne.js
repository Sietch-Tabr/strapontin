// Dernières données connues, gardées dans le navigateur pour s'afficher quand la connexion est
// absente ou trop lente au lancement. Seules les listes de l'utilisateur sont conservées (jamais
// une recherche), et tout est effacé à la déconnexion, sur un 401 et sur les pages de connexion :
// un autre compte sur le même appareil ne voit jamais les données du précédent.

const PREFIXE = "strapontin:cache:";
const MAX_ENTREES = 40;
const TAILLE_MAX_CORPS = 1_500_000;
const DUREE_MAX_MS = 30 * 24 * 60 * 60 * 1000;
// Chemins exacts : une sous-ressource (/api/spectacles/matches/…) n'est pas une liste.
const CHEMINS_CACHABLES = new Set([
  "/api/accueil",
  "/api/spectacles",
  "/api/decouverte",
  "/api/artistes",
  "/api/salles",
  "/api/session",
]);

/// @param url adresse relative d'un appel d'API
/// @return `true` si la réponse d'un GET sur cette adresse peut être gardée pour le hors ligne
export function estCachable(url) {
  const adresse = new URL(url, "http://hote.invalide");
  // `q` lance une recherche (Wikidata, Deezer, Nominatim) : jamais gardée.
  return (
    CHEMINS_CACHABLES.has(adresse.pathname) && !adresse.searchParams.has("q")
  );
}

/// @param stockage objet de type `Storage` (localStorage), absent ou défaillant sans conséquence
/// @param maintenant horloge en millisecondes, injectable pour les tests
export function creerCacheHorsLigne({
  stockage,
  maintenant = () => Date.now(),
} = {}) {
  function cles() {
    try {
      return Array.from({ length: stockage.length }, (_, i) =>
        stockage.key(i),
      ).filter((c) => c?.startsWith(PREFIXE));
    } catch {
      return [];
    }
  }

  function lireEntree(cle) {
    try {
      const entree = JSON.parse(stockage.getItem(cle));
      return typeof entree?.corps === "string" &&
        typeof entree.enregistreLe === "number"
        ? entree
        : null;
    } catch {
      return null;
    }
  }

  // Garde les entrées les plus récentes ; `reserve` libère de la place avant une écriture.
  function elaguer(reserve = 0) {
    const entrees = cles()
      .map((cle) => ({ cle, entree: lireEntree(cle) }))
      .sort(
        (a, b) => (b.entree?.enregistreLe ?? 0) - (a.entree?.enregistreLe ?? 0),
      );
    for (const { cle } of entrees.slice(Math.max(0, MAX_ENTREES - reserve))) {
      try {
        stockage.removeItem(cle);
      } catch {
        // ignoré
      }
    }
  }

  return {
    /// @param url adresse relative du GET
    /// @param corps texte JSON de la réponse
    ecrire(url, corps) {
      if (!stockage || corps.length > TAILLE_MAX_CORPS) return;
      const valeur = JSON.stringify({ corps, enregistreLe: maintenant() });
      try {
        stockage.setItem(PREFIXE + url, valeur);
      } catch {
        // Quota atteint : on libère de la place une fois, sans insister.
        elaguer(MAX_ENTREES / 2);
        try {
          stockage.setItem(PREFIXE + url, valeur);
        } catch {
          return;
        }
      }
      elaguer();
    },

    /// @return `{corps, enregistreLe}` ou `null` si rien de gardé, de périmé ou d'illisible
    lire(url) {
      if (!stockage) return null;
      const entree = lireEntree(PREFIXE + url);
      return entree && maintenant() - entree.enregistreLe <= DUREE_MAX_MS
        ? entree
        : null;
    },

    /// Efface toutes les données gardées (déconnexion, session expirée, autre compte).
    purger() {
      if (!stockage) return;
      for (const cle of cles()) {
        try {
          stockage.removeItem(cle);
        } catch {
          // ignoré
        }
      }
    },
  };
}

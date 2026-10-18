// Quand l'application n'affiche que des données gardées, elle guette le retour du serveur et se
// recharge d'elle-même pour les remplacer par des données fraîches. Le sondage ne dépend pas des
// événements `online`/`offline`, que iOS Safari ne déclenche pas de façon fiable.

const CLE_DERNIER_RECHARGEMENT = "strapontin:reconnexion";
const DELAI_MIN_ENTRE_RECHARGEMENTS_MS = 60_000;

function memoireNavigateur() {
  try {
    return globalThis.sessionStorage;
  } catch {
    return undefined;
  }
}

/// @param joignable fonction asynchrone : `true` dès que le serveur répond
/// @param recharger recharge la page
/// @param intervalleMs délai entre deux sondages tant que la connexion manque
/// @param maintenant horloge en millisecondes
/// @param memoire objet de type `Storage` qui retient l'heure du dernier rechargement
export function creerSurveillanceReconnexion({
  joignable,
  recharger,
  intervalleMs = 15_000,
  maintenant = () => Date.now(),
  memoire = memoireNavigateur(),
}) {
  let minuterie = null;
  let enCours = false;

  // Un serveur qui répond au sondage mais reste trop lent pour l'application ramènerait ici en
  // boucle : un rechargement par minute au plus.
  function rechargementPermis() {
    try {
      const dernier = Number(memoire?.getItem(CLE_DERNIER_RECHARGEMENT) ?? 0);
      return maintenant() - dernier >= DELAI_MIN_ENTRE_RECHARGEMENTS_MS;
    } catch {
      return true;
    }
  }

  async function sonderMaintenant() {
    if (enCours) return false;
    enCours = true;
    try {
      if (!(await joignable()) || !rechargementPermis()) return false;
      try {
        memoire?.setItem(CLE_DERNIER_RECHARGEMENT, String(maintenant()));
      } catch {
        // ignoré : au pire un rechargement de plus
      }
      recharger();
      return true;
    } finally {
      enCours = false;
    }
  }

  return {
    sonderMaintenant,
    demarrer() {
      if (minuterie === null)
        minuterie = setInterval(sonderMaintenant, intervalleMs);
    },
    arreter() {
      clearInterval(minuterie);
      minuterie = null;
    },
  };
}

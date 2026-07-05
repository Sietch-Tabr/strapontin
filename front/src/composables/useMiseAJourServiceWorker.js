/**
 * Écoute le cycle de mise à jour du service worker : positionne `miseAJourEnCours` à `true`
 * puis déclenche le rechargement dès qu'un nouveau service worker prend le contrôle de la page
 * (événement `controllerchange`). Relaie aussi les messages de navigation par ancre envoyés par
 * le service worker (`{ type: 'nav-ancre', url }`).
 *
 * @param {import('vue').Ref<boolean>} miseAJourEnCours réf réactive affichant l'overlay de mise à jour
 * @param {EventTarget|undefined|null} conteneurServiceWorker `navigator.serviceWorker`, ou `undefined`/`null` si l'API n'est pas disponible (ne fait rien dans ce cas)
 * @param {{ recharger?: () => void, naviguerVersAncre?: (url: string) => void }} [options]
 */
export function ecouterMiseAJourServiceWorker(
  miseAJourEnCours,
  conteneurServiceWorker,
  options = {},
) {
  if (!conteneurServiceWorker) return;

  const recharger = options.recharger ?? (() => window.location.reload());
  const naviguerVersAncre = options.naviguerVersAncre ?? (() => {});

  conteneurServiceWorker.addEventListener("controllerchange", () => {
    miseAJourEnCours.value = true;
    recharger();
  });
  conteneurServiceWorker.addEventListener("message", (event) => {
    if (event.data?.type === "nav-ancre") {
      naviguerVersAncre(event.data.url);
    }
  });
}

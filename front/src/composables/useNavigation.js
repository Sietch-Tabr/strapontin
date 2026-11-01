import { nextTick } from "vue";
import { useRoute, useRouter } from "vue-router";

// Au-delà de la durée de l'animation, une navigation encore en attente n'est plus animée.
const DELAI_ABANDON_TRANSITION_MS = 300;

/**
 * Navigue vers `href` via le router, avec transition animée si le navigateur la supporte.
 *
 * Si le callback de la View Transition échoue ou expire (le navigateur applique un timeout
 * interne, ~4s sous Chromium — observé en production : un clic sur un lien juste après un
 * déploiement, avant rechargement, peut faire échouer/traîner le chargement du chunk JS lazy
 * de la route ciblée, dont le hash a changé), on se replie sur une navigation complète hors SPA
 * pour récupérer des assets à jour plutôt que de laisser l'utilisateur bloqué sur la page
 * courante avec une rejection non gérée.
 *
 * @param {import('vue-router').Router} router instance du router
 * @param {string} href destination
 * @param {{ demarrerTransition?: (cb: () => Promise<void>) => { updateCallbackDone: Promise<void> }, naviguerHorsSpa?: (href: string) => void }} [options]
 */
export function naviguer(router, href, options = {}) {
  const demarrerTransition =
    options.demarrerTransition ??
    (typeof document !== "undefined"
      ? document.startViewTransition?.bind(document)
      : undefined);
  const naviguerHorsSpa =
    options.naviguerHorsSpa ??
    ((h) => {
      if (typeof window !== "undefined") window.location.href = h;
    });

  if (router.resolve(href).matched.length === 0) {
    naviguerHorsSpa(href);
    return;
  }
  if (!demarrerTransition) {
    router.push(href);
    return;
  }
  const transition = demarrerTransition(async () => {
    await router.push(href);
    await nextTick();
  });
  transition.updateCallbackDone.catch(() => naviguerHorsSpa(href));
  // ready et finished rejettent aussi quand la transition est abandonnée ou expire : sans
  // catch, l'erreur remonte à la console et au journal admin.
  transition.ready?.catch(() => {});
  transition.finished?.catch(() => {});
  // Une garde de navigation qui attend l'utilisateur (dialogue de modifications non enregistrées)
  // garde le callback en attente et le rendu gelé : on abandonne l'animation pour rendre la main.
  const minuterie = setTimeout(
    () => transition.skipTransition?.(),
    DELAI_ABANDON_TRANSITION_MS,
  );
  const arreter = () => clearTimeout(minuterie);
  transition.updateCallbackDone.then(arreter, arreter);
}

export function useNavigation() {
  const router = useRouter();
  const route = useRoute();

  function estActif(href) {
    if (href === "/") return route.path === "/";
    return route.path === href || route.path.startsWith(href + "/");
  }

  return { naviguer: (href) => naviguer(router, href), estActif };
}

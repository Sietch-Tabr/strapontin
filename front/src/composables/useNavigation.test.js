import { describe, it, expect, vi } from "vitest";
import { naviguer } from "./useNavigation.js";

function creerFauxRouter({
  matched = [{}],
  push = vi.fn().mockResolvedValue(undefined),
} = {}) {
  return {
    resolve: vi.fn(() => ({ matched })),
    push,
  };
}

describe("naviguer", () => {
  it("appelle naviguerHorsSpa quand la route est inconnue du router", () => {
    const router = creerFauxRouter({ matched: [] });
    const naviguerHorsSpa = vi.fn();

    naviguer(router, "/externe", { naviguerHorsSpa });

    expect(naviguerHorsSpa).toHaveBeenCalledWith("/externe");
    expect(router.push).not.toHaveBeenCalled();
  });

  it("appelle push directement sans support de View Transition", () => {
    const router = creerFauxRouter();

    naviguer(router, "/spectacles", { demarrerTransition: undefined });

    expect(router.push).toHaveBeenCalledWith("/spectacles");
  });

  it("démarre la transition et pousse la route quand le navigateur la supporte", async () => {
    const router = creerFauxRouter();
    let callbackTransition;
    const demarrerTransition = vi.fn((cb) => {
      callbackTransition = cb();
      return { updateCallbackDone: callbackTransition };
    });

    naviguer(router, "/artistes", { demarrerTransition });
    await callbackTransition;

    expect(demarrerTransition).toHaveBeenCalledOnce();
    expect(router.push).toHaveBeenCalledWith("/artistes");
  });

  // Bug observé en production (logs admin, client-js) : "View transition update callback timed
  // out" après un clic sur un lien juste après un déploiement, avant rechargement — le chunk JS
  // lazy de la route ciblée peut échouer/traîner (hash de build obsolète), et le navigateur
  // abandonne la transition sans que rien ne rattrape l'erreur. Le repli hors SPA permet de
  // récupérer des assets à jour au lieu de laisser l'utilisateur bloqué sur la page courante.
  it("se replie sur naviguerHorsSpa si la transition échoue ou expire", async () => {
    const router = creerFauxRouter();
    const naviguerHorsSpa = vi.fn();
    const demarrerTransition = vi.fn(() => ({
      updateCallbackDone: Promise.reject(
        new Error("View transition update callback timed out."),
      ),
    }));

    naviguer(router, "/salles", { demarrerTransition, naviguerHorsSpa });
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(naviguerHorsSpa).toHaveBeenCalledWith("/salles");
  });
});

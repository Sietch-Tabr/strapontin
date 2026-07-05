import { describe, it, expect, vi } from "vitest";
import { ref } from "vue";
import { ecouterMiseAJourServiceWorker } from "./useMiseAJourServiceWorker.js";

function creerFauxConteneurServiceWorker() {
  const ecouteurs = {};
  return {
    addEventListener: (type, callback) => {
      ecouteurs[type] = callback;
    },
    declencher: (type, event) => ecouteurs[type]?.(event),
  };
}

describe("ecouterMiseAJourServiceWorker", () => {
  it("positionne miseAJourEnCours et declenche le rechargement au controllerchange", () => {
    const miseAJourEnCours = ref(false);
    const recharger = vi.fn();
    const conteneur = creerFauxConteneurServiceWorker();

    ecouterMiseAJourServiceWorker(miseAJourEnCours, conteneur, { recharger });
    conteneur.declencher("controllerchange");

    expect(miseAJourEnCours.value).toBe(true);
    expect(recharger).toHaveBeenCalledOnce();
  });

  it("relaie un message nav-ancre vers naviguerVersAncre avec l'url", () => {
    const miseAJourEnCours = ref(false);
    const naviguerVersAncre = vi.fn();
    const conteneur = creerFauxConteneurServiceWorker();

    ecouterMiseAJourServiceWorker(miseAJourEnCours, conteneur, {
      naviguerVersAncre,
    });
    conteneur.declencher("message", {
      data: { type: "nav-ancre", url: "/spectacles#concert-123" },
    });

    expect(naviguerVersAncre).toHaveBeenCalledWith("/spectacles#concert-123");
  });

  it("ignore un message dont le type n'est pas nav-ancre", () => {
    const miseAJourEnCours = ref(false);
    const naviguerVersAncre = vi.fn();
    const conteneur = creerFauxConteneurServiceWorker();

    ecouterMiseAJourServiceWorker(miseAJourEnCours, conteneur, {
      naviguerVersAncre,
    });
    conteneur.declencher("message", { data: { type: "autre" } });

    expect(naviguerVersAncre).not.toHaveBeenCalled();
  });

  it("ne fait rien si aucun conteneur service worker n'est fourni", () => {
    const miseAJourEnCours = ref(false);

    expect(() =>
      ecouterMiseAJourServiceWorker(miseAJourEnCours, null),
    ).not.toThrow();
    expect(miseAJourEnCours.value).toBe(false);
  });
});

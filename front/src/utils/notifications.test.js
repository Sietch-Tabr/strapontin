import { describe, it, expect } from "vitest";
import {
  avecEmailArtistes,
  avecPushArtistes,
  canalJoignable,
} from "./notifications.js";

const base = {
  frequenceNotifications: "jamais",
  notifArtistes: false,
  notifSalles: false,
  notifArtistesPush: false,
  notifSallesPush: false,
  notifNouvelleVersion: false,
};

describe("canalJoignable", () => {
  it("est faux quand la fréquence est « jamais », même avec des canaux cochés", () => {
    expect(
      canalJoignable(
        { ...base, notifArtistes: true, notifArtistesPush: true },
        2,
      ),
    ).toBe(false);
  });

  it("est vrai avec l'email activé et une fréquence active", () => {
    expect(
      canalJoignable(
        {
          ...base,
          frequenceNotifications: "hebdomadaire",
          notifArtistes: true,
        },
        0,
      ),
    ).toBe(true);
  });

  it("est faux avec le push seul et aucun appareil abonné", () => {
    const config = {
      ...base,
      frequenceNotifications: "quotidien",
      notifArtistesPush: true,
      notifSallesPush: true,
    };
    expect(canalJoignable(config, 0)).toBe(false);
    expect(canalJoignable(config, null)).toBe(false);
  });

  it("est vrai avec le push et au moins un appareil abonné", () => {
    expect(
      canalJoignable(
        {
          ...base,
          frequenceNotifications: "temps_reel",
          notifSallesPush: true,
        },
        1,
      ),
    ).toBe(true);
  });

  it("est faux sans configuration", () => {
    expect(canalJoignable(null, 3)).toBe(false);
  });
});

describe("avecEmailArtistes", () => {
  it("active l'email et passe une fréquence « jamais » à l'hebdomadaire", () => {
    expect(avecEmailArtistes(base, true)).toMatchObject({
      notifArtistes: true,
      frequenceNotifications: "hebdomadaire",
    });
  });

  it("garde la fréquence déjà choisie", () => {
    expect(
      avecEmailArtistes({ ...base, frequenceNotifications: "quotidien" }, true)
        .frequenceNotifications,
    ).toBe("quotidien");
  });

  it("repasse à « jamais » quand plus aucun canal n'est coché", () => {
    expect(
      avecEmailArtistes(
        {
          ...base,
          frequenceNotifications: "hebdomadaire",
          notifArtistes: true,
        },
        false,
      ).frequenceNotifications,
    ).toBe("jamais");
  });

  it("garde la fréquence quand un autre canal reste coché", () => {
    expect(
      avecEmailArtistes(
        {
          ...base,
          frequenceNotifications: "hebdomadaire",
          notifArtistes: true,
          notifArtistesPush: true,
        },
        false,
      ).frequenceNotifications,
    ).toBe("hebdomadaire");
  });
});

describe("avecPushArtistes", () => {
  it("active le push des artistes avec une fréquence active", () => {
    expect(avecPushArtistes(base)).toMatchObject({
      notifArtistesPush: true,
      frequenceNotifications: "hebdomadaire",
    });
  });
});

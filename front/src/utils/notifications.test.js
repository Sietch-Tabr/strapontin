import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import {
  avecEmailArtistes,
  avecPushArtistes,
  canalJoignable,
  FREQUENCES,
  PLAGE_SILENCIEUSE,
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

describe("avecEmailArtistes", () => {
  it("retire aussi la nouvelle version quand plus aucun canal ne reste (« jamais » ne sert rien)", () => {
    const config = {
      ...base,
      frequenceNotifications: "hebdomadaire",
      notifArtistes: true,
      notifNouvelleVersion: true,
    };
    expect(avecEmailArtistes(config, false)).toMatchObject({
      frequenceNotifications: "jamais",
      notifArtistes: false,
      notifNouvelleVersion: false,
    });
  });
});

describe("libellés de notification partagés", () => {
  it("annoncent les récapitulatifs à midi, plage silencieuse comprise", () => {
    const libelles = FREQUENCES.map((f) => f.libelle).join(" ");
    expect(libelles).toContain("quotidien (12h)");
    expect(libelles).toContain("dimanche 12h");
    expect(PLAGE_SILENCIEUSE).toContain("22h et 7h");
  });

  // Un horaire recopié en dur dans un écran finirait par diverger du serveur (Aide restée à 8h).
  it.each([
    "pages/AidePage.vue",
    "components/parametres/ParametresNotifications.vue",
    "components/layout/ChoixNotificationsOnboarding.vue",
  ])("%s lit les fréquences dans utils/notifications.js", (fichier) => {
    const source = readFileSync(
      new URL(`../${fichier}`, import.meta.url),
      "utf8",
    );
    expect(source).toMatch(/notifications\.js/);
    expect(source).not.toMatch(
      /récapitulatif (quotidien|hebdomadaire)[^\n]*\b(8|10)h/i,
    );
    expect(source).not.toMatch(/chaque (jour|dimanche) à (8|10)h/);
  });
});

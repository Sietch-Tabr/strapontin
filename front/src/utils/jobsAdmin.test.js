import { describe, expect, test } from "vitest";
import { grouperJobsParTheme, THEME_A_CLASSER } from "./jobsAdmin.js";

const THEMES = [
  { code: "RECUPERATION", libelle: "Récupération des concerts" },
  { code: "PURGES", libelle: "Purges" },
];
const CATALOGUE = [
  {
    type: "fetch:artistes:batch",
    libelle: "Concerts des artistes suivis",
    description: "d1",
    theme: "RECUPERATION",
  },
  {
    type: "fetch:salles:all",
    libelle: "Concerts des salles suivies",
    description: "d2",
    theme: "RECUPERATION",
  },
  {
    type: "purger:concerts",
    libelle: "Concerts passés et disparus",
    description: "d3",
    theme: "PURGES",
  },
];

describe("grouperJobsParTheme", () => {
  test("grouperJobsParTheme_jobsCatalogues_rangesParThemeDansLOrdreDuCatalogue", () => {
    const groupes = grouperJobsParTheme(
      [
        { typeJob: "purger:concerts" },
        { typeJob: "fetch:salles:all" },
        { typeJob: "fetch:artistes:batch" },
      ],
      CATALOGUE,
      THEMES,
    );

    expect(groupes.map((g) => g.code)).toEqual(["RECUPERATION", "PURGES"]);
    expect(groupes[0].jobs.map((j) => j.libelle)).toEqual([
      "Concerts des artistes suivis",
      "Concerts des salles suivies",
    ]);
  });

  test("grouperJobsParTheme_jobNonRepertorie_rangeDansAClasserSousSonIdentifiant", () => {
    const groupes = grouperJobsParTheme(
      [{ typeJob: "nouveau:job" }],
      CATALOGUE,
      THEMES,
    );

    expect(groupes).toHaveLength(1);
    expect(groupes[0].code).toBe(THEME_A_CLASSER);
    expect(groupes[0].jobs[0].libelle).toBe("nouveau:job");
  });

  test("grouperJobsParTheme_themeSansJob_nEstPasAffiche", () => {
    const groupes = grouperJobsParTheme(
      [{ typeJob: "purger:concerts" }],
      CATALOGUE,
      THEMES,
    );

    expect(groupes.map((g) => g.code)).toEqual(["PURGES"]);
  });
});

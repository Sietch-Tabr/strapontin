import { describe, expect, test } from "vitest";
import { extraireSlugDepuisUrl, slugDeduit } from "./digitickSlug.js";

describe("slugDeduit", () => {
  test("slugDeduit_nomAvecAccentsEtEspaces_retourneSlugSansAccentNiEspace", () => {
    expect(slugDeduit("Le Splendid")).toBe("lesplendid");
  });

  test("slugDeduit_nomAvecApostropheEtAccent_retourneSlugNettoye", () => {
    expect(slugDeduit("L'Olympia")).toBe("lolympia");
  });

  test("slugDeduit_nomDejaEnMinuscules_estInchange", () => {
    expect(slugDeduit("zenith")).toBe("zenith");
  });
});

describe("extraireSlugDepuisUrl", () => {
  test("extraireSlugDepuisUrl_urlAvecParametreSite_retourneLeSite", () => {
    expect(
      extraireSlugDepuisUrl("https://web.digitick.com/?site=le-splendid"),
    ).toBe("le-splendid");
  });

  test("extraireSlugDepuisUrl_urlAvecMotifIndexCss_retourneLeSlugExtrait", () => {
    expect(
      extraireSlugDepuisUrl(
        "https://web.digitick.com/index-css2-le-splendid-pg101.html",
      ),
    ).toBe("le-splendid");
  });

  test("extraireSlugDepuisUrl_slugSaisiDirectement_retourneLeSlugTrimme", () => {
    expect(extraireSlugDepuisUrl("  le-splendid  ")).toBe("le-splendid");
  });

  test("extraireSlugDepuisUrl_urlSansMotifReconnu_retourneLaValeurTrimmee", () => {
    expect(
      extraireSlugDepuisUrl("https://web.digitick.com/autre-page.html"),
    ).toBe("https://web.digitick.com/autre-page.html");
  });
});

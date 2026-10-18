import { describe, expect, test } from "vitest";
import {
  detailSuggestion,
  libelleSourceSuggestion,
} from "./suggestionsArtistes.js";

describe("libelleSourceSuggestion", () => {
  test("libelleSourceSuggestion_sourcesConnues_retourneUnLibelleLisible", () => {
    expect(libelleSourceSuggestion("wikidata")).toBe("Wikidata");
    expect(libelleSourceSuggestion("deezer")).toBe("Deezer");
    expect(libelleSourceSuggestion("base")).toBe("Déjà connu");
  });

  test("libelleSourceSuggestion_sourceInconnue_retourneLaSource", () => {
    expect(libelleSourceSuggestion("autre")).toBe("autre");
  });
});

describe("detailSuggestion", () => {
  test("detailSuggestion_descriptionWikidata_estPreferee", () => {
    expect(detailSuggestion({ source: "wikidata", description: "band" })).toBe(
      "band",
    );
  });

  test("detailSuggestion_deezerAvecFans_donneLaPopularite", () => {
    expect(detailSuggestion({ source: "deezer", fans: 1385 })).toMatch(
      /^1\s385 fans sur Deezer$/,
    );
  });

  test("detailSuggestion_deezerSansFan_resteAuSingulier", () => {
    expect(detailSuggestion({ source: "deezer", fans: 0 })).toBe(
      "0 fan sur Deezer",
    );
  });

  test("detailSuggestion_base_dit_d_ouVientLeResultat", () => {
    expect(detailSuggestion({ source: "base" })).toContain("Strapontin");
  });

  test("detailSuggestion_wikidataSansDescription_estVide", () => {
    expect(detailSuggestion({ source: "wikidata" })).toBe("");
  });
});

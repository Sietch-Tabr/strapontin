import { describe, it, expect } from "vitest";
import { MEDIA_QUERY_PREFERENCE_SOMBRE } from "./useTheme.js";

describe("MEDIA_QUERY_PREFERENCE_SOMBRE", () => {
  it("utilise la valeur littérale dark, jamais traduite en français", () => {
    expect(MEDIA_QUERY_PREFERENCE_SOMBRE).toBe("(prefers-color-scheme: dark)");
  });

  it("ne contient pas le mot sombre, invalide pour cette media feature CSS", () => {
    expect(MEDIA_QUERY_PREFERENCE_SOMBRE).not.toMatch(/sombre/);
  });
});

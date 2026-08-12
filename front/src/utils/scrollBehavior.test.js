import { describe, expect, test } from "vitest";
import { scrollBehavior } from "./scrollBehavior.js";

describe("scrollBehavior", () => {
  test("scrollBehavior_navigationClassiqueSansPositionMemorisee_retourneHautDePage", () => {
    expect(scrollBehavior({}, {}, null)).toEqual({ top: 0, left: 0 });
  });

  test("scrollBehavior_navigationPrecedentSuivantAvecPositionMemorisee_restaureLaPosition", () => {
    const positionMemorisee = { top: 420, left: 0 };
    expect(scrollBehavior({}, {}, positionMemorisee)).toBe(positionMemorisee);
  });
});

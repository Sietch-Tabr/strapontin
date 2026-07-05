import { describe, expect, test } from "vitest";
import { parseJsonOuNull } from "./reponseJson.js";

describe("parseJsonOuNull", () => {
  test("parseJsonOuNull_texteVide_retourneNull", () => {
    expect(parseJsonOuNull("")).toBeNull();
  });

  test("parseJsonOuNull_jsonValide_retourneObjetParse", () => {
    expect(parseJsonOuNull('{"message":"Test","type":"info"}')).toEqual({
      message: "Test",
      type: "info",
    });
  });
});

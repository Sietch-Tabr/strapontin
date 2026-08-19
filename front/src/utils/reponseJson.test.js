import { describe, expect, test } from "vitest";
import { corpsJsonOuVide, parseJsonOuNull } from "./reponseJson.js";

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

describe("corpsJsonOuVide", () => {
  test("corpsJsonOuVide_jsonValide_retourneObjetParse", async () => {
    const reponse = { json: () => Promise.resolve({ erreur: "Non trouvé." }) };
    await expect(corpsJsonOuVide(reponse)).resolves.toEqual({
      erreur: "Non trouvé.",
    });
  });

  test("corpsJsonOuVide_corpsNonJson_retourneObjetVide", async () => {
    const reponse = {
      json: () => Promise.reject(new SyntaxError("Unexpected token")),
    };
    await expect(corpsJsonOuVide(reponse)).resolves.toEqual({});
  });
});

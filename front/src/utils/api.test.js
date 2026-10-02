import { afterEach, describe, expect, test, vi } from "vitest";
import {
  appelApi,
  ENTETE_APPLICATION,
  lireJson,
  MESSAGE_SERVEUR_INDISPONIBLE,
  VALEUR_ENTETE_APPLICATION,
} from "./api.js";

describe("appelApi", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  test("appelApi_ajouteLEnteteApplicatifSansPerdreLesAutres", async () => {
    const fetchSimule = vi.fn(() => Promise.resolve({ ok: true }));
    vi.stubGlobal("fetch", fetchSimule);

    await appelApi("/api/test", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });

    const [url, options] = fetchSimule.mock.calls[0];
    expect(url).toBe("/api/test");
    expect(options.method).toBe("POST");
    expect(options.headers.get(ENTETE_APPLICATION)).toBe(
      VALEUR_ENTETE_APPLICATION,
    );
    expect(options.headers.get("Content-Type")).toBe("application/json");
  });

  test("appelApi_reponseDErreurNonJson_resoutSansRejeter", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        Promise.resolve(new Response("<html>502</html>", { status: 502 })),
      ),
    );

    const reponse = await appelApi("/api/test");

    expect(reponse.ok).toBe(false);
    expect(reponse.status).toBe(502);
    await expect(lireJson(reponse)).resolves.toEqual({
      erreur: MESSAGE_SERVEUR_INDISPONIBLE,
    });
  });
});

describe("lireJson", () => {
  test("lireJson_jsonValide_retourneObjetParse", async () => {
    const reponse = new Response('{"erreur":"Non trouvé."}');
    await expect(lireJson(reponse)).resolves.toEqual({ erreur: "Non trouvé." });
  });

  test("lireJson_corpsVide_retourneObjetVide", async () => {
    await expect(
      lireJson(new Response(null, { status: 204 })),
    ).resolves.toEqual({});
  });

  test("lireJson_erreurServeurAvecMessage_conserveLeMessageDuServeur", async () => {
    const reponse = new Response('{"erreur":"Erreur de base de données."}', {
      status: 500,
    });
    await expect(lireJson(reponse)).resolves.toEqual({
      erreur: "Erreur de base de données.",
    });
  });
});

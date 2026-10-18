import { describe, expect, test } from "vitest";
import { messagesCouverture } from "./couverture.js";

describe("messagesCouverture", () => {
  test("messagesCouverture_aucunDepartement_retourneAucunMessage", () => {
    expect(messagesCouverture([])).toEqual([]);
  });

  test("messagesCouverture_outreMer_parleDeCouvertureEtNonDAbsenceDeConcerts", () => {
    const [message] = messagesCouverture(["974"]);
    expect(message.cle).toBe("outre-mer");
    expect(message.texte).toContain("974");
    expect(message.texte).toContain("couvrent mal ce département");
    expect(message.texte).toContain("ne veut pas dire qu'il n'y en a pas");
  });

  test("messagesCouverture_plusieursDepartementsOutreMer_accordeAuPluriel", () => {
    const [message] = messagesCouverture(["971", "972"]);
    expect(message.texte).toContain("971, 972");
    expect(message.texte).toContain("couvrent mal ces départements");
  });

  test("messagesCouverture_metropole_garde_leMessageHabituel", () => {
    const [message] = messagesCouverture(["75"]);
    expect(message.cle).toBe("metropole");
    expect(message.texte).toContain("Aucun concert n'est proposé");
    expect(message.texte).not.toContain("billetteries locales");
  });

  test("messagesCouverture_melange_separeOutreMerEtMetropole", () => {
    const messages = messagesCouverture(["75", "973", "2A"]);
    expect(messages.map((m) => m.cle)).toEqual(["outre-mer", "metropole"]);
    expect(messages[0].texte).toContain("973");
    expect(messages[1].texte).toContain("75, 2A");
  });
});

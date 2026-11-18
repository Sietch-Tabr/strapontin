import { describe, expect, it } from "vitest";
import {
  aDerniereReussite,
  corpsDemande,
  demandeTerminee,
  detailVisible,
  empreinteEtat,
  estInstallee,
  ETAPES,
  motifImpossible,
  phraseEchec,
  presentationStatut,
  statutEtape,
} from "./miseAJour.js";

const etat = (resultat, etape, versionCible = "3.2.0") => ({
  resultat,
  etape,
  versionCible,
});

describe("statutEtape", () => {
  it("sans état, toute étape est à venir", () => {
    expect(statutEtape("VERIFICATION", null)).toBe("a-venir");
  });

  it("une mise à jour réussie a toutes ses étapes faites", () => {
    for (const { cle } of ETAPES) {
      expect(statutEtape(cle, etat("REUSSI", "CONTROLE"))).toBe("faite");
    }
  });

  it("en cours : étapes passées faites, courante en cours, suivantes à venir", () => {
    const courant = etat("EN_COURS", "SAUVEGARDE");
    expect(statutEtape("TELECHARGEMENT", courant)).toBe("faite");
    expect(statutEtape("SAUVEGARDE", courant)).toBe("en-cours");
    expect(statutEtape("INSTALLATION", courant)).toBe("a-venir");
  });

  it("en échec : l'étape où l'échec s'est produit est en échec", () => {
    const echec = etat("ECHEC", "PROVENANCE");
    expect(statutEtape("TELECHARGEMENT", echec)).toBe("faite");
    expect(statutEtape("PROVENANCE", echec)).toBe("echec");
    expect(statutEtape("SAUVEGARDE", echec)).toBe("a-venir");
  });

  it("un échec sans étape (demande illisible) laisse toutes les étapes à venir", () => {
    expect(statutEtape("VERIFICATION", etat("ECHEC", null))).toBe("a-venir");
  });

  it("les clés d'étape sont celles du module, dans l'ordre", () => {
    expect(ETAPES.map((e) => e.cle)).toEqual([
      "VERIFICATION",
      "TELECHARGEMENT",
      "PROVENANCE",
      "SAUVEGARDE",
      "EXTRACTION",
      "INSTALLATION",
      "CONTROLE",
    ]);
  });
});

describe("presentationStatut", () => {
  it("donne un libellé français et une classe de la charte pour chaque statut", () => {
    expect(presentationStatut("faite")).toEqual({
      libelle: "Fait",
      classe: "badge badge-succes",
    });
    expect(presentationStatut("en-cours").libelle).toBe("En cours");
    expect(presentationStatut("echec").classe).toBe("badge badge-danger");
    expect(presentationStatut("a-venir").libelle).toBe("À venir");
  });
});

describe("motifImpossible", () => {
  const possible = {
    totpActif: true,
    enCours: false,
    versionProposee: "3.2.0",
  };

  it("n'a pas de motif quand tout est réuni", () => {
    expect(motifImpossible(possible)).toBeNull();
  });

  it("exige le TOTP en premier", () => {
    expect(motifImpossible({ ...possible, totpActif: false })).toMatch(
      /deux facteurs/,
    );
  });

  it("refuse pendant une mise à jour", () => {
    expect(motifImpossible({ ...possible, enCours: true })).toMatch(
      /déjà demandée ou en cours/,
    );
  });

  it("dit que l'application est à jour sans version proposée", () => {
    expect(motifImpossible({ ...possible, versionProposee: null })).toBe(
      "L'application est à jour.",
    );
  });
});

describe("corpsDemande", () => {
  it("porte la version, le mot de passe et le code sans espaces autour", () => {
    expect(JSON.parse(corpsDemande("3.2.0", "mdp", " 123456 "))).toEqual({
      version: "3.2.0",
      motDePasse: "mdp",
      codeTotp: "123456",
    });
  });
});

describe("demandeTerminee", () => {
  const avant = empreinteEtat(etat("ECHEC", "SAUVEGARDE"));

  it("n'est pas terminée tant que l'état n'a pas changé", () => {
    expect(
      demandeTerminee(
        { enCours: false, etat: etat("ECHEC", "SAUVEGARDE") },
        avant,
      ),
    ).toBe(false);
  });

  it("n'est pas terminée tant que la mise à jour court", () => {
    expect(
      demandeTerminee(
        { enCours: true, etat: etat("EN_COURS", "PROVENANCE") },
        avant,
      ),
    ).toBe(false);
  });

  it("est terminée dès qu'un nouvel état final est écrit", () => {
    expect(
      demandeTerminee(
        { enCours: false, etat: etat("REUSSI", "CONTROLE") },
        avant,
      ),
    ).toBe(true);
  });

  it("sans état avant, le premier état final termine la demande", () => {
    expect(
      demandeTerminee(
        { enCours: false, etat: etat("ECHEC", null) },
        empreinteEtat(null),
      ),
    ).toBe(true);
  });
});

describe("phraseEchec", () => {
  const donnees = (message) => ({
    versionInstallee: "3.1.5",
    etat: { message },
  });

  it("ajoute le point manquant entre le motif et la suite", () => {
    expect(
      phraseEchec(donnees("Le déploiement a échoué : retour automatique")),
    ).toBe(
      "La mise à jour a échoué : Le déploiement a échoué : retour automatique. L'application reste sur la version 3.1.5.",
    );
  });

  it("garde la ponctuation existante", () => {
    expect(phraseEchec(donnees("Sauvegarde impossible."))).toBe(
      "La mise à jour a échoué : Sauvegarde impossible. L'application reste sur la version 3.1.5.",
    );
  });

  it("tolère un motif absent", () => {
    expect(phraseEchec(donnees(null))).toBe(
      "La mise à jour a échoué. L'application reste sur la version 3.1.5.",
    );
  });
});

describe("estInstallee", () => {
  it("est vrai pour une réussite dont la cible est la version en service", () => {
    expect(
      estInstallee({
        versionInstallee: "3.2.0",
        etat: etat("REUSSI", "CONTROLE"),
      }),
    ).toBe(true);
  });

  it("est faux pour un échec, une autre cible ou sans état", () => {
    expect(
      estInstallee({
        versionInstallee: "3.2.0",
        etat: etat("ECHEC", "CONTROLE"),
      }),
    ).toBe(false);
    expect(
      estInstallee({
        versionInstallee: "3.1.5",
        etat: etat("REUSSI", "CONTROLE"),
      }),
    ).toBe(false);
    expect(estInstallee({ versionInstallee: "3.2.0", etat: null })).toBe(false);
  });
});

describe("aDerniereReussite", () => {
  it("est vrai pour une réussite datée", () => {
    expect(
      aDerniereReussite({
        etat: { ...etat("REUSSI", "CONTROLE"), fin: "2026-11-17T21:27:39Z" },
      }),
    ).toBe(true);
  });

  it("est faux pour un échec, sans date ou sans état", () => {
    expect(
      aDerniereReussite({
        etat: { ...etat("ECHEC", "CONTROLE"), fin: "2026-11-17T21:27:39Z" },
      }),
    ).toBe(false);
    expect(
      aDerniereReussite({ etat: { ...etat("REUSSI", "CONTROLE"), fin: null } }),
    ).toBe(false);
    expect(aDerniereReussite({ etat: null })).toBe(false);
  });
});

describe("detailVisible", () => {
  const base = { demandeEnAttente: false, enCours: false };

  it("reste masqué pour une réussite relue après rechargement", () => {
    expect(
      detailVisible({ ...base, etat: etat("REUSSI", "CONTROLE") }, false),
    ).toBe(false);
  });

  it("s'affiche pour la page qui a suivi l'installation", () => {
    expect(
      detailVisible({ ...base, etat: etat("REUSSI", "CONTROLE") }, true),
    ).toBe(true);
  });

  it("s'affiche pour un échec, même après rechargement", () => {
    expect(
      detailVisible({ ...base, etat: etat("ECHEC", "DEPLOIEMENT") }, false),
    ).toBe(true);
  });

  it("s'affiche pendant une mise à jour en cours", () => {
    expect(
      detailVisible(
        { ...base, enCours: true, etat: etat("EN_COURS", "SAUVEGARDE") },
        false,
      ),
    ).toBe(true);
  });

  it("reste masqué sans étape ou avec une demande en attente", () => {
    expect(detailVisible({ ...base, etat: null }, true)).toBe(false);
    expect(
      detailVisible(
        {
          ...base,
          demandeEnAttente: true,
          etat: etat("ECHEC", "TELECHARGEMENT"),
        },
        true,
      ),
    ).toBe(false);
  });
});

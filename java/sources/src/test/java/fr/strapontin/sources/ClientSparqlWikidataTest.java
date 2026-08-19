/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/// Tests unitaires de {@link ClientSparqlWikidata#echapperLitteral} — échappement des littéraux
/// insérés dans une requête SPARQL construite par concaténation.
class ClientSparqlWikidataTest {

  @Test
  void echapperLitteral_chaineSansCaractereSpecial_retourneInchangee() {
    assertEquals("Radiohead", ClientSparqlWikidata.echapperLitteral("Radiohead"));
  }

  @Test
  void echapperLitteral_guillemet_echappe() {
    assertEquals("Le \\\"Vrai\\\" Nom", ClientSparqlWikidata.echapperLitteral("Le \"Vrai\" Nom"));
  }

  @Test
  void echapperLitteral_antislash_echappe() {
    assertEquals("a\\\\b", ClientSparqlWikidata.echapperLitteral("a\\b"));
  }

  @Test
  void echapperLitteral_antislashAvantGuillemet_ordreCorrect() {
    // L'antislash doit être échappé avant le guillemet, sinon le \" produit par l'échappement
    // du guillemet serait lui-même ré-échappé.
    assertEquals("a\\\\\\\"b", ClientSparqlWikidata.echapperLitteral("a\\\"b"));
  }

  @Test
  void echapperLitteral_sautDeLigne_echappe() {
    assertEquals("ligne1\\nligne2", ClientSparqlWikidata.echapperLitteral("ligne1\nligne2"));
  }

  @Test
  void echapperLitteral_retourChariot_echappe() {
    assertEquals("a\\rb", ClientSparqlWikidata.echapperLitteral("a\rb"));
  }
}

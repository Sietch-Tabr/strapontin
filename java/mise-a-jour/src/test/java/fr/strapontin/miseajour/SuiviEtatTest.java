/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.exploitation.Etape;
import fr.strapontin.exploitation.EtatMiseAJour;
import fr.strapontin.exploitation.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests unitaires pour {@link SuiviEtat}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class SuiviEtatTest {

  private static final Clock HORLOGE =
      Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);

  @TempDir Path dossier;

  private JsonNode lire() throws IOException {
    return new JsonMapper().readTree(Files.readString(dossier.resolve(EtatMiseAJour.FICHIER)));
  }

  @Test
  void demarrer_ecritUnEtatEnCours() throws IOException {
    new SuiviEtat(dossier, HORLOGE).demarrer(Version.analyser("3.2.0"));
    final JsonNode etat = lire();
    assertEquals("EN_COURS", etat.get("resultat").asString());
    assertEquals("VERIFICATION", etat.get("etape").asString());
    assertEquals("3.2.0", etat.get("versionCible").asString());
    assertTrue(etat.get("fin").isNull());
  }

  @Test
  void echouer_garde_lEtapeEtLeMessage() throws IOException {
    final SuiviEtat etat = new SuiviEtat(dossier, HORLOGE);
    etat.demarrer(Version.analyser("3.2.0"));
    etat.etape(Etape.SAUVEGARDE);
    etat.echouer("Sauvegarde impossible");
    final JsonNode json = lire();
    assertEquals("ECHEC", json.get("resultat").asString());
    assertEquals("SAUVEGARDE", json.get("etape").asString());
    assertEquals("Sauvegarde impossible", json.get("message").asString());
    assertEquals("2026-11-17T10:00:00Z", json.get("fin").asString());
  }

  @Test
  void echouer_messageTropLong_estTronque() throws IOException {
    final SuiviEtat etat = new SuiviEtat(dossier, HORLOGE);
    etat.echouer("x".repeat(900));
    assertEquals(500, lire().get("message").asString().length());
  }

  @Test
  void reussir_ecritUnResultatReussi() throws IOException {
    final SuiviEtat etat = new SuiviEtat(dossier, HORLOGE);
    etat.demarrer(Version.analyser("3.2.0"));
    etat.reussir();
    assertEquals("REUSSI", lire().get("resultat").asString());
    assertTrue(Files.notExists(dossier.resolve(EtatMiseAJour.FICHIER + ".nouveau")));
  }
}

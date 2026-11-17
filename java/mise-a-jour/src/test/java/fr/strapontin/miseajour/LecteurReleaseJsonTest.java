/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.exploitation.FichierRelease;
import fr.strapontin.exploitation.Version;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests de {@link LecteurReleaseJson} sur une réponse réelle de l'API GitHub (celle de la
 * pré-version v3.2.0-dev.1, capturée telle quelle) : les champs lus et les hôtes des adresses de
 * téléchargement sont ceux que GitHub renvoie vraiment, pas ceux qu'on suppose.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class LecteurReleaseJsonTest {

  private static String reponseReelle() throws IOException {
    try (InputStream flux =
        LecteurReleaseJsonTest.class.getResourceAsStream("/release/release-reelle.json")) {
      return new String(flux.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void lire_reponseReelleDeGithub_retourneLesFichiersDeLaRelease() throws IOException {
    final ConfigurationSource configuration =
        ConfigurationSource.github("Sietch-Tabr/strapontin", "essai");
    final AdressesAutorisees adresses =
        new AdressesAutorisees(configuration.hotesAutorises(), configuration.httpsExige());

    final ReleaseGithub release =
        LecteurReleaseJson.lire(reponseReelle(), Version.analyser("3.2.0-dev.1"), adresses);

    assertEquals("v3.2.0-dev.1", release.etiquette());
    assertTrue(release.preRelease());
    assertFalse(release.brouillon());
    // La réponse capturée précède l'ajout de exploitation-controle.jar au contrat.
    final Set<String> attendus = new HashSet<>(FichierRelease.noms());
    attendus.remove(FichierRelease.CONTROLE.nom());
    assertEquals(attendus, release.assets().keySet());
  }
}

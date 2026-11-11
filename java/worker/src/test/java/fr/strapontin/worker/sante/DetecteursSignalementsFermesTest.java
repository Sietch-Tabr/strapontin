/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tout code qui publie un signalement prévoit aussi sa fermeture : un détecteur qui signale sans
 * jamais fermer laisse ouvert un signalement dont la condition a disparu.
 *
 * @since 3.1.4
 * @author Kwaite
 */
class DetecteursSignalementsFermesTest {

  private static final Pattern POSE_UN_SIGNALEMENT =
      Pattern.compile("[sS]ignalement\\w*\\.signaler\\(");
  private static final Path RACINE = Path.of("src/main/java/fr/strapontin/worker");

  // Fichiers qui publient un signalement dont la fermeture est assurée ailleurs, avec le lieu.
  private static final List<String> FERMES_AILLEURS =
      List.of(
          // Fermés par ControleIntegriteService.fermerSignalementsEvenementiels.
          "EnrichissementSallesService.java",
          "EnrichissementArtistesService.java",
          // Fermé par ClotureSignalementsCoordonnees (appelé par EnrichissementSallesService).
          "ClotureSignalementsCoordonnees.java");

  @Test
  void detecteurs_chaquePublicationDeSignalement_prevoitSaFermeture() throws IOException {
    final List<String> sansFermeture = new ArrayList<>();
    try (Stream<Path> fichiers = Files.walk(RACINE)) {
      for (final Path fichier : fichiers.filter(f -> f.toString().endsWith(".java")).toList()) {
        final String nom = fichier.getFileName().toString();
        final String source = Files.readString(fichier);
        final boolean signale = POSE_UN_SIGNALEMENT.matcher(source).find();
        final boolean ferme =
            source.contains(".clore(")
                || source.contains(".cloreEntite(")
                || source.contains("resolu_le");
        if (signale
            && !ferme
            && !FERMES_AILLEURS.contains(nom)
            && !nom.equals("SignalementMatchingService.java")) {
          sansFermeture.add(nom);
        }
      }
    }
    assertTrue(
        sansFermeture.isEmpty(),
        "Détecteurs qui signalent sans prévoir de fermeture : " + sansFermeture);
  }
}

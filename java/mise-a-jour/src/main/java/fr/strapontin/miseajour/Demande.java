/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import fr.strapontin.exploitation.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Demande de mise à jour déposée par le web : version, demandeur, instant.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public record Demande(Version version, String demandeur, Instant horodatage) {

  private static final int DEMANDE_MAX = 4096;
  private static final int DEMANDEUR_MAX = 100;

  /**
   * Lit et valide une demande : un fichier ordinaire et court, une version
   * valide, un demandeur sans caractère de contrôle.
   *
   * @param fichier fichier JSON de la demande
   * @return la demande
   * @throws InstallationException si le fichier est illisible ou invalide
   */
  public static Demande lire(final Path fichier) {
    try {
      if (!Files.isRegularFile(fichier) || Files.size(fichier) > DEMANDE_MAX) {
        throw new InstallationException("Demande de mise à jour invalide");
      }
      final JsonNode json = new JsonMapper().readTree(Files.readString(fichier));
      return new Demande(
          Version.analyser(texte(json, "version")),
          demandeur(texte(json, "demandeur")),
          Instant.parse(texte(json, "horodatage")));
    } catch (final IOException | JacksonException | IllegalArgumentException e) {
      throw new InstallationException("Demande de mise à jour illisible", e);
    } catch (final DateTimeParseException e) {
      throw new InstallationException("Horodatage de la demande invalide", e);
    }
  }

  private static String texte(final JsonNode json, final String champ) {
    final JsonNode valeur = json.get(champ);
    if (valeur == null || !valeur.isString()) {
      throw new IllegalArgumentException("Champ absent : " + champ);
    }
    return valeur.asString();
  }

  private static String demandeur(final String texte) {
    if (texte.isBlank()
        || texte.length() > DEMANDEUR_MAX
        || texte.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Demandeur invalide");
    }
    return texte;
  }
}

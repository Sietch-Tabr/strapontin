/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import fr.strapontin.exploitation.Version;
import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Version de l'application installée, lue dans le JAR actif : l'attribut
 * {@code Implementation-Version} du manifeste, que Maven y écrit à la
 * construction (le CI vérifie qu'il vaut la version de la release).
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class VersionInstallee {

  private static final String ATTRIBUT = "Implementation-Version";

  private VersionInstallee() {}

  /**
   * Lit la version d'un JAR d'application.
   *
   * @param jar JAR exécutable de l'application
   * @return la version qu'il porte
   * @throws InstallationException si le JAR est absent, illisible ou sans version
   */
  public static Version lire(final Path jar) {
    try (JarFile archive = new JarFile(jar.toFile())) {
      final Manifest manifeste = archive.getManifest();
      final String valeur =
          manifeste == null ? null : manifeste.getMainAttributes().getValue(ATTRIBUT);
      if (valeur == null) {
        throw new InstallationException("Version installée introuvable dans " + jar.getFileName());
      }
      return Version.analyser(valeur);
    } catch (final IOException | IllegalArgumentException e) {
      throw new InstallationException("Version installée illisible : " + jar.getFileName(), e);
    }
  }
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import fr.strapontin.exploitation.Version;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Version de l'application installée, lue dans le JAR actif : le fichier
 * {@code build-info.properties} que Maven y écrit à la construction.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class VersionInstallee {

  private static final String ENTREE = "BOOT-INF/classes/META-INF/build-info.properties";
  private static final String CLE = "build.version";

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
      final JarEntry entree = archive.getJarEntry(ENTREE);
      if (entree == null) {
        throw new InstallationException("Version installée introuvable dans " + jar.getFileName());
      }
      final Properties proprietes = new Properties();
      try (InputStream flux = archive.getInputStream(entree)) {
        proprietes.load(flux);
      }
      return Version.analyser(proprietes.getProperty(CLE, ""));
    } catch (final IOException | IllegalArgumentException e) {
      throw new InstallationException("Version installée illisible : " + jar.getFileName(), e);
    }
  }
}

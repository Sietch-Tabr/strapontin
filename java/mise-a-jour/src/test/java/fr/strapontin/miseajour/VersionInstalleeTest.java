/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fr.strapontin.exploitation.Version;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests unitaires pour {@link VersionInstallee}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class VersionInstalleeTest {

  @TempDir Path dossier;

  /* package-private : les tests de l'installateur construisent le même JAR. */
  static Path jar(final Path fichier, final String contenuInfo) throws IOException {
    try (OutputStream sortie = Files.newOutputStream(fichier);
        JarOutputStream jar = new JarOutputStream(sortie)) {
      jar.putNextEntry(new JarEntry("BOOT-INF/classes/META-INF/build-info.properties"));
      jar.write(contenuInfo.getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
    }
    return fichier;
  }

  @Test
  void lire_jarAvecVersion_retourneLaVersion() throws IOException {
    final Path web = jar(dossier.resolve("web.jar"), "build.version=3.1.5\n");
    assertEquals(Version.analyser("3.1.5"), VersionInstallee.lire(web));
  }

  @Test
  void lire_preVersion_laConserve() throws IOException {
    final Path web = jar(dossier.resolve("web.jar"), "build.version=3.2.0-dev.1\n");
    assertEquals(Version.analyser("3.2.0-dev.1"), VersionInstallee.lire(web));
  }

  @Test
  void lire_jarSansInformation_leveUneException() throws IOException {
    final Path vide = dossier.resolve("vide.jar");
    try (OutputStream sortie = Files.newOutputStream(vide);
        JarOutputStream jar = new JarOutputStream(sortie)) {
      jar.putNextEntry(new JarEntry("autre.txt"));
      jar.closeEntry();
    }
    assertThrows(InstallationException.class, () -> VersionInstallee.lire(vide));
  }

  @Test
  void lire_versionIllisible_leveUneException() throws IOException {
    final Path web = jar(dossier.resolve("web.jar"), "build.version=quoi\n");
    assertThrows(InstallationException.class, () -> VersionInstallee.lire(web));
  }

  @Test
  void lire_fichierAbsent_leveUneException() {
    final Path absent = dossier.resolve("absent.jar");
    assertThrows(InstallationException.class, () -> VersionInstallee.lire(absent));
  }
}

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
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
  static Path jar(final Path fichier, final String version) throws IOException {
    final Manifest manifeste = new Manifest();
    final Attributes attributs = manifeste.getMainAttributes();
    attributs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    // Même structure qu'un JAR Spring Boot : manifeste de Maven, build-info à la racine de
    // META-INF.
    attributs.put(Attributes.Name.MAIN_CLASS, "org.springframework.boot.loader.launch.JarLauncher");
    attributs.putValue("Start-Class", "fr.strapontin.web.WebApplication");
    attributs.put(Attributes.Name.IMPLEMENTATION_VERSION, version);
    try (OutputStream sortie = Files.newOutputStream(fichier);
        JarOutputStream jar = new JarOutputStream(sortie, manifeste)) {
      jar.putNextEntry(new JarEntry("META-INF/build-info.properties"));
      jar.write(("build.version=" + version + "\n").getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
    }
    return fichier;
  }

  @Test
  void lire_jarAvecVersion_retourneLaVersion() throws IOException {
    final Path web = jar(dossier.resolve("web.jar"), "3.1.5");
    assertEquals(Version.analyser("3.1.5"), VersionInstallee.lire(web));
  }

  @Test
  void lire_preVersion_laConserve() throws IOException {
    final Path web = jar(dossier.resolve("web.jar"), "3.2.0-dev.1");
    assertEquals(Version.analyser("3.2.0-dev.1"), VersionInstallee.lire(web));
  }

  @Test
  void lire_jarSansImplementationVersion_leveUneException() throws IOException {
    final Path sans = dossier.resolve("sans.jar");
    final Manifest manifeste = new Manifest();
    manifeste.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    try (OutputStream sortie = Files.newOutputStream(sans);
        JarOutputStream jar = new JarOutputStream(sortie, manifeste)) {
      jar.putNextEntry(new JarEntry("META-INF/build-info.properties"));
      jar.write("build.version=3.1.5\n".getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
    }
    assertThrows(InstallationException.class, () -> VersionInstallee.lire(sans));
  }

  @Test
  void lire_jarSansManifeste_leveUneException() throws IOException {
    final Path vide = dossier.resolve("vide.jar");
    try (OutputStream sortie = Files.newOutputStream(vide);
        ZipOutputStream zip = new ZipOutputStream(sortie)) {
      zip.putNextEntry(new ZipEntry("autre.txt"));
      zip.closeEntry();
    }
    assertThrows(InstallationException.class, () -> VersionInstallee.lire(vide));
  }

  @Test
  void lire_versionIllisible_leveUneException() throws IOException {
    final Path web = jar(dossier.resolve("web.jar"), "quoi");
    assertThrows(InstallationException.class, () -> VersionInstallee.lire(web));
  }

  @Test
  void lire_fichierAbsent_leveUneException() {
    final Path absent = dossier.resolve("absent.jar");
    assertThrows(InstallationException.class, () -> VersionInstallee.lire(absent));
  }
}

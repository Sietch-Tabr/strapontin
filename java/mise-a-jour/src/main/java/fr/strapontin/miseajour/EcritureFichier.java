/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Écriture d'un fichier téléchargé : plafond de taille, taille annoncée
 * respectée, empreinte SHA-256 calculée à la volée, renommage atomique.
 *
 * @since 3.2.0
 * @author Kwaite
 */
final class EcritureFichier {

  private static final int TAILLE_TAMPON = 8192;

  private EcritureFichier() {}

  /**
   * Écrit le flux dans le dossier et contrôle sa taille.
   * @param flux contenu reçu
   * @param asset fichier attendu
   * @param dossier dossier de destination
   * @param tailleMax plafond de taille en octets
   * @return le fichier écrit et son empreinte
   */
  /* package-private : usage interne à la source de releases. */
  static Telechargement ecrire(
      final InputStream flux, final Asset asset, final Path dossier, final long tailleMax)
      throws IOException {
    final Path partiel = dossier.resolve(asset.nom() + ".partiel");
    final MessageDigest empreinte = nouvelleEmpreinte();
    final long total;
    boolean reussi = false;
    try {
      total = copier(flux, partiel, empreinte, Math.min(tailleMax, asset.taille()));
      if (total != asset.taille()) {
        throw new SourceInaccessibleException("Fichier incomplet : " + asset.nom());
      }
      final Path definitif = dossier.resolve(asset.nom());
      Files.move(
          partiel, definitif, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      reussi = true;
      return new Telechargement(definitif, HexFormat.of().formatHex(empreinte.digest()), total);
    } finally {
      if (!reussi) {
        Files.deleteIfExists(partiel);
      }
    }
  }

  private static long copier(
      final InputStream flux, final Path cible, final MessageDigest empreinte, final long limite)
      throws IOException {
    long total = 0;
    try (OutputStream sortie = Files.newOutputStream(cible)) {
      final byte[] tampon = new byte[TAILLE_TAMPON];
      for (int lus = flux.read(tampon); lus >= 0; lus = flux.read(tampon)) {
        total += lus;
        if (total > limite) {
          throw new SourceInaccessibleException("Fichier plus gros qu'annoncé ou trop volumineux");
        }
        empreinte.update(tampon, 0, lus);
        sortie.write(tampon, 0, lus);
      }
    }
    return total;
  }

  private static MessageDigest nouvelleEmpreinte() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponible", e);
    }
  }
}

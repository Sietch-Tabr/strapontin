/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import java.util.List;

/**
 * Origine des attestations d'un fichier, retrouvées par son empreinte.
 *
 * @since 3.2.0
 * @author Kwaite
 */
@FunctionalInterface
public interface FournisseurAttestations {

  /**
   * Liste les attestations publiées pour une empreinte.
   *
   * @param depot dépôt {@code propriétaire/nom}
   * @param sha256 empreinte SHA-256 du fichier, en hexadécimal
   * @return les bundles Sigstore au format JSON, vide s'il n'y en a pas
   * @throws SourceInaccessibleException si la source ne répond pas
   */
  List<String> bundles(String depot, String sha256);
}

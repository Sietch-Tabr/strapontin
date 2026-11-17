/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import java.nio.file.Path;

/**
 * Vérifie qu'un fichier a bien été produit par l'identité attendue.
 *
 * @since 3.2.0
 * @author Kwaite
 */
@FunctionalInterface
public interface VerificateurProvenance {

  /**
   * Vérifie la provenance d'un fichier.
   *
   * @param fichier fichier à contrôler
   * @param identite identité attendue du workflow
   * @throws ProvenanceException si la provenance n'est pas prouvée
   */
  void verifier(Path fichier, IdentiteAttendue identite);
}

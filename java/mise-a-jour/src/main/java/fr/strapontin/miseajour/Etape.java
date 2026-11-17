/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

/**
 * Étapes d'une mise à jour, dans l'ordre où l'installateur les exécute.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public enum Etape {
  /** Comparaison avec la version installée et lecture de la release. */
  VERIFICATION,
  /** Téléchargement des fichiers de la release. */
  TELECHARGEMENT,
  /** Contrôle des sommes SHA-256 puis de la provenance Sigstore. */
  PROVENANCE,
  /** Sauvegarde de la base avant tout remplacement. */
  SAUVEGARDE,
  /** Extraction et contrôle de l'archive d'exploitation. */
  EXTRACTION,
  /** Dépôt des JARs puis exécution de update.sh. */
  INSTALLATION,
  /** Relecture de la version installée. */
  CONTROLE
}

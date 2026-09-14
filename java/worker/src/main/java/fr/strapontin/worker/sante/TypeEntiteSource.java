/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

/**
 * Table et colonne de rattachement d'un type d'entité suivi
 * (détail : CLAUDE.md, section Flyway).
 * @since 2.8.7
 * @author Kwaite
 */
enum TypeEntiteSource {
  SALLE("salle_sources", "salle_id"),
  ARTISTE("artiste_sources", "artiste_id");

  private final String nomTable;
  private final String nomColonneId;

  TypeEntiteSource(final String nomTable, final String nomColonneId) {
    this.nomTable = nomTable;
    this.nomColonneId = nomColonneId;
  }

  /**
   * @return nom de la table ({@code salle_sources} ou {@code artiste_sources})
   */
  /* package-private : appelé par SuiviSanteSourcesService (même package). */
  String table() {
    return nomTable;
  }

  /**
   * @return nom de la colonne de rattachement ({@code salle_id} ou
   *     {@code artiste_id} )
   */
  /* package-private : appelé par SuiviSanteSourcesService (même package). */
  String colonneId() {
    return nomColonneId;
  }
}

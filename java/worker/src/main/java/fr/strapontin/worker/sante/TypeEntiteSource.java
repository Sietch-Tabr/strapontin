/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

/**
 * Table et colonne de rattachement associées à un type d'entité suivi par ce package ({@code
 * salle_sources}/{@code salle_id} ou {@code artiste_sources}/{@code artiste_id}).
 *
 * <p>Remplace un couple de paramètres {@code String table, String colonneId} passés en clair aux
 * méthodes qui construisent une requête SQL par concaténation — un enum exclut structurellement
 * tout appel avec une valeur non constante, contrairement à un {@code String} dont rien ne garantit
 * qu'il reste toujours l'un des deux littéraux attendus.
 *
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

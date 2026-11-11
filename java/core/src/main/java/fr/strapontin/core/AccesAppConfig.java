/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Seul chemin de lecture et d'écriture de {@code app_config}. La valeur est
 * lue et écrite telle que stockée : chiffrée pour les clés de configuration
 * (par les services de configuration), en JSON clair pour les compteurs. Un
 * changement de format de la table ne se fait qu'ici.
 * @since 3.1.4
 * @author Kwaite
 */
public final class AccesAppConfig {

  private static final String CLE = "cle";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * @param jdbc accès JDBC
   */
  public AccesAppConfig(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param cle clé de configuration
   * @return la valeur telle que stockée, vide si la clé est absente
   */
  public Optional<String> lire(final String cle) {
    final List<String> lignes =
        jdbc.queryForList(
            "SELECT valeur_chiffree FROM app_config WHERE cle = :cle",
            Map.of(CLE, cle),
            String.class);
    return lignes.isEmpty() ? Optional.empty() : Optional.ofNullable(lignes.get(0));
  }

  /**
   * Crée ou remplace une valeur.
   * @param cle clé de configuration
   * @param valeur valeur à stocker, telle quelle
   */
  public void ecrire(final String cle, final String valeur) {
    jdbc.update(
        """
        INSERT INTO app_config (cle, valeur_chiffree)
        VALUES (:cle, :valeur)
        ON CONFLICT (cle) DO UPDATE
          SET valeur_chiffree = EXCLUDED.valeur_chiffree,
              mis_a_jour_le   = now()
        """,
        Map.of(CLE, cle, "valeur", valeur));
  }

  /**
   * @param cle clé de configuration à retirer
   */
  public void supprimer(final String cle) {
    jdbc.update("DELETE FROM app_config WHERE cle = :cle", Map.of(CLE, cle));
  }

  /**
   * @return les clés présentes
   */
  public Set<String> cles() {
    return new HashSet<>(
        jdbc.query("SELECT cle FROM app_config", Map.of(), (rs, _) -> rs.getString(CLE)));
  }
}

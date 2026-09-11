/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Suivi de la version du changelog vue par chaque utilisateur.
 *
 * @since 2.2.0
 * @author Kwaite
 */
@Service
class ChangelogService {

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * @param jdbc template JDBC nommé
   */
  ChangelogService(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Indique si l'utilisateur n'a pas encore vu le changelog de la version donnée.
   *
   * @param userId identifiant de l'utilisateur
   * @param versionCourante version actuelle de l'application
   * @return {@code true} si la dernière version vue diffère de {@code versionCourante}
   */
  boolean estNonVu(String userId, String versionCourante) {
    List<String> lignes =
        jdbc.queryForList(
            "SELECT changelog_version_vue FROM user_config WHERE user_id = :u",
            Map.of("u", userId),
            String.class);
    String versionVue = lignes.isEmpty() ? null : lignes.get(0);
    return !versionCourante.equals(versionVue);
  }

  /**
   * Marque une version du changelog comme vue pour l'utilisateur.
   *
   * <p>Upsert plutôt qu'une simple mise à jour : un utilisateur qui n'a encore jamais écrit dans
   * {@code user_config} (aucun changement de thème, de préférences...) n'y a pas encore de ligne —
   * un {@code UPDATE} seul ne ferait alors rien, laissant le badge "nouveautés" affiché
   * indéfiniment malgré un appel réussi.
   *
   * @param userId identifiant de l'utilisateur
   * @param versionCourante version actuelle de l'application
   */
  void marquerVue(String userId, String versionCourante) {
    jdbc.update(
        """
        INSERT INTO user_config (user_id, changelog_version_vue)
        VALUES (:u, :v)
        ON CONFLICT (user_id) DO UPDATE SET changelog_version_vue = :v, updated_at = now()
        """,
        Map.of("v", versionCourante, "u", userId));
  }
}

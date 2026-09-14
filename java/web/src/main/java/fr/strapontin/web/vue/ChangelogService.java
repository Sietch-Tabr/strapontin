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
  /* package-private : instancié uniquement par le contrôleur du même
   * package. */
  ChangelogService(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Indique si l'utilisateur n'a pas encore vu le changelog de la version
   * donnée.
   * @param userId identifiant de l'utilisateur
   * @param versionCourante version actuelle de l'application
   * @return {@code true} si la dernière version vue diffère
   */
  /* package-private : appelé par le contrôleur du même package. */
  boolean estNonVu(final String userId, final String versionCourante) {
    final List<String> lignes =
        jdbc.queryForList(
            "SELECT changelog_version_vue FROM user_config WHERE user_id = :u",
            Map.of("u", userId),
            String.class);
    final String versionVue = lignes.isEmpty() ? null : lignes.get(0);
    return !versionCourante.equals(versionVue);
  }

  /**
   * Marque une version du changelog comme vue — upsert plutôt qu'un simple
   * {@code UPDATE}, un utilisateur sans ligne {@code user_config} existante
   * verrait sinon le badge "nouveautés" rester affiché malgré un appel
   * réussi.
   * @param userId identifiant de l'utilisateur
   * @param versionCourante version actuelle de l'application
   */
  /* package-private : appelé par le contrôleur du même package. */
  void marquerVue(final String userId, final String versionCourante) {
    jdbc.update(
        """
        INSERT INTO user_config (user_id, changelog_version_vue)
        VALUES (:u, :v)
        ON CONFLICT (user_id) DO UPDATE SET changelog_version_vue = :v, updated_at = now()
        """,
        Map.of("v", versionCourante, "u", userId));
  }
}

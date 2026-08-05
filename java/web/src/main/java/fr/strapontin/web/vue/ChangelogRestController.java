/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.List;
import java.util.Map;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour le journal des modifications.
///
/// <p> : la version courante est lue dynamiquement via {@link BuildProperties} (généré par le
/// goal Maven {@code build-info}, {@code java/web/pom.xml}) plutôt qu'une constante à maintenir
/// manuellement à chaque release — {@code user_config.changelog_version_vue} pilote désormais un
/// badge "nouveautés" ({@link #nouveaute}), complétant l'intention initiale du mécanisme (jusque
/// là une écriture pure sans aucun lecteur).
///
/// @since 2.2.0
/// @author Kwaite
@RestController
@RequestMapping("/api/changelog")
public class ChangelogRestController {

  private final NamedParameterJdbcTemplate jdbc;
  private final BuildProperties buildProperties;

  /// @param jdbc            template JDBC nommé
  /// @param buildProperties métadonnées de build (version courante de l'application)
  public ChangelogRestController(NamedParameterJdbcTemplate jdbc, BuildProperties buildProperties) {
    this.jdbc = jdbc;
    this.buildProperties = buildProperties;
  }

  /// Indique si l'utilisateur n'a pas encore vu le changelog de la version courante.
  ///
  /// @param auth contexte d'authentification
  /// @return {@code {"nonVu": true|false}}
  @GetMapping("/nouveaute")
  public ResponseEntity<Map<String, Object>> nouveaute(Authentication auth) {
    String userId = ((UtilisateurDetails) auth.getPrincipal()).id();
    List<String> lignes =
        jdbc.queryForList(
            "SELECT changelog_version_vue FROM user_config WHERE user_id = :u",
            Map.of("u", userId),
            String.class);
    String versionVue = lignes.isEmpty() ? null : lignes.get(0);
    boolean nonVu = !buildProperties.getVersion().equals(versionVue);
    return ResponseEntity.ok(Map.of("nonVu", nonVu));
  }

  /// Marque la version courante du changelog comme vue pour l'utilisateur.
  ///
  /// @param auth contexte d'authentification
  /// @return 204 No Content
  @PostMapping("/marquer-vue")
  public ResponseEntity<Void> marquerVue(Authentication auth) {
    String userId = ((UtilisateurDetails) auth.getPrincipal()).id();
    jdbc.update(
        "UPDATE user_config SET changelog_version_vue = :v WHERE user_id = :u",
        Map.of("v", buildProperties.getVersion(), "u", userId));
    return ResponseEntity.noContent().build();
  }
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Map;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST pour le journal des modifications — version courante lue
 * via {@link BuildProperties} (goal Maven {@code build-info}) plutôt qu'une
 * constante manuelle. {@code changelog_version_vue} pilote le badge
 * "nouveautés" ({@link #nouveaute}).
 * @since 2.2.0
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/changelog")
public class ChangelogRestController {

  private final ChangelogService changelogService;
  private final BuildProperties buildProperties;

  /**
   * @param changelogService suivi de la version du changelog vue par
   *     utilisateur
   * @param buildProperties métadonnées de build (version courante de
   *     l'application)
   */
  public ChangelogRestController(
      final ChangelogService changelogService, final BuildProperties buildProperties) {
    this.changelogService = changelogService;
    this.buildProperties = buildProperties;
  }

  /**
   * Indique si l'utilisateur n'a pas encore vu le changelog de la version
   * courante.
   *
   * @param auth contexte d'authentification
   * @return {@code {"nonVu": true|false}}
   */
  @GetMapping("/nouveaute")
  public ResponseEntity<Map<String, Object>> nouveaute(final Authentication auth) {
    final String userId = ((UtilisateurDetails) auth.getPrincipal()).id();
    final boolean nonVu = changelogService.estNonVu(userId, buildProperties.getVersion());
    return ResponseEntity.ok(Map.of("nonVu", nonVu));
  }

  /**
   * Marque la version courante du changelog comme vue pour l'utilisateur.
   *
   * @param auth contexte d'authentification
   * @return 204 No Content
   */
  @PostMapping("/marquer-vue")
  public ResponseEntity<Void> marquerVue(final Authentication auth) {
    final String userId = ((UtilisateurDetails) auth.getPrincipal()).id();
    changelogService.marquerVue(userId, buildProperties.getVersion());
    return ResponseEntity.noContent().build();
  }
}

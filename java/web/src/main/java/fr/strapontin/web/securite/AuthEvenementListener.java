/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.admin.LogAdminService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * Écoute les événements Spring Security et les consigne dans
 * {@code logs_admin} (connexion réussie, échec).
 * @since 2.0.0
 * @author Kwaite
 */
@Component
public class AuthEvenementListener {

  // même seuil que le verrouillage TOTP (TotpChallengeService, 5 échecs).
  private static final int SEUIL_ECHECS_ALERTE = 5;
  private static final Duration FENETRE_ECHECS_ALERTE = Duration.ofMinutes(15);

  private final LogAdminService logAdminService;
  private final AlerteActiviteUtilisateurService alerteActiviteUtilisateurService;
  private final NamedParameterJdbcTemplate jdbc;

  /**
   * @param logAdminService journalisation des événements d'authentification
   * @param alerteActiviteUtilisateurService alertes d'échecs de connexion répétés
   * @param jdbc accès JDBC (mise à jour de la dernière connexion)
   */
  public AuthEvenementListener(
      final LogAdminService logAdminService,
      final AlerteActiviteUtilisateurService alerteActiviteUtilisateurService,
      final NamedParameterJdbcTemplate jdbc) {
    this.logAdminService = logAdminService;
    this.alerteActiviteUtilisateurService = alerteActiviteUtilisateurService;
    this.jdbc = jdbc;
  }

  /**
   * Consigne une connexion réussie.
   *
   * @param event l'événement Spring Security
   */
  @EventListener
  public void surSuccesAuthentification(final AuthenticationSuccessEvent event) {
    final String identifiant = event.getAuthentication().getName();
    final String ip = obtenirIp(event.getAuthentication());
    final Map<String, Object> meta = new HashMap<>();
    meta.put("identifiant", identifiant);
    meta.put("ip", ip != null ? ip : "?");
    if (event.getAuthentication().getPrincipal() instanceof UtilisateurDetails ud) {
      meta.put("userId", ud.id());
      // reconnexion = annulation automatique de toute purge d'inactivité en cours.
      jdbc.update(
          "UPDATE users SET derniere_connexion_le = now(), purge_le = NULL WHERE id = :id",
          Map.of("id", ud.id()));
    }
    logAdminService.logAdmin("info", "auth", "Connexion réussie : " + identifiant, meta);
  }

  /**
   * Consigne un échec d'authentification.
   *
   * @param event l'événement Spring Security
   */
  @EventListener
  public void surEchecAuthentification(final AbstractAuthenticationFailureEvent event) {
    final String identifiant = event.getAuthentication().getName();
    final String ip = obtenirIp(event.getAuthentication());
    final String raison =
        event
            .getException()
            .getClass()
            .getSimpleName()
            .replace("Exception", "")
            .toLowerCase(Locale.ROOT);
    final Map<String, Object> meta = new HashMap<>();
    meta.put("identifiant", identifiant);
    meta.put("ip", ip != null ? ip : "?");
    meta.put("raison", raison);
    logAdminService.logAdmin("warn", "auth", "Échec connexion : " + identifiant, meta);

    final long depuisMs = Instant.now().minus(FENETRE_ECHECS_ALERTE).toEpochMilli();
    if (logAdminService.compterEchecsConnexionRecents(identifiant, depuisMs)
        == SEUIL_ECHECS_ALERTE) {
      alerteActiviteUtilisateurService.notifierEchecsConnexionRepetes(identifiant);
    }
  }

  private String obtenirIp(final Authentication auth) {
    if (auth.getDetails() instanceof WebAuthenticationDetails details) {
      return details.getRemoteAddress();
    }
    return null;
  }
}

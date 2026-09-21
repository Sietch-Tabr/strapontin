/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.email.EmailServiceSecurite;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Alertes proactives à l'utilisateur sur une activité suspecte détectée
 * sur son propre compte (nouvel appareil, échecs de connexion répétés) —
 * distinct d'{@link fr.strapontin.web.email.EmailAlerteService}, qui
 * alerte l'admin, pas l'utilisateur concerné.
 * @since 3.0.0
 * @author Kwaite
 */
@Service
public class AlerteActiviteUtilisateurService {

  private static final Logger LOG = LoggerFactory.getLogger(AlerteActiviteUtilisateurService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final EmailServiceSecurite emailServiceSecurite;

  /**
   * @param jdbc accès JDBC
   * @param emailServiceSecurite service d'envoi des alertes de sécurité utilisateur
   */
  public AlerteActiviteUtilisateurService(
      final NamedParameterJdbcTemplate jdbc, final EmailServiceSecurite emailServiceSecurite) {
    this.jdbc = jdbc;
    this.emailServiceSecurite = emailServiceSecurite;
  }

  /**
   * Notifie l'utilisateur d'une connexion depuis un appareil jamais vu.
   *
   * @param email l'adresse email de l'utilisateur
   * @param userAgent l'en-tête {@code User-Agent} du nouvel appareil
   */
  public void notifierNouvelAppareil(final String email, final String userAgent) {
    if (email == null || email.isBlank()) {
      return;
    }
    emailServiceSecurite.envoyerAlerteNouvelAppareil(email, userAgent);
    LOG.info("Alerte nouvel appareil envoyée : emailHash={}", email.hashCode());
  }

  /**
   * Notifie l'utilisateur d'une rafale d'échecs de connexion sur son
   * compte.
   *
   * @param identifiant l'identifiant ciblé par les échecs
   */
  public void notifierEchecsConnexionRepetes(final String identifiant) {
    final List<Map<String, Object>> cible =
        jdbc.queryForList(
            "SELECT email FROM users WHERE lower(username) = lower(:identifiant)",
            Map.of("identifiant", identifiant));
    if (cible.isEmpty()) {
      return;
    }
    final String email = (String) cible.get(0).get("email");
    emailServiceSecurite.envoyerAlerteEchecsConnexion(email);
    LOG.info(
        "Alerte échecs de connexion répétés envoyée : identifiantHash={}", identifiant.hashCode());
  }
}

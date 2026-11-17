/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Alerte e-mail de fin de mise à jour de l'application, à l'administrateur
 * (destinataire d'alerte), que la mise à jour ait réussi ou non.
 *
 * @since 3.2.0
 * @author Kwaite
 */
@Service
public class AlerteMiseAJourService {

  private static final Logger LOG = LoggerFactory.getLogger(AlerteMiseAJourService.class);

  private final EmailSender emailSender;

  /**
   * @param emailSender envoi Resend et résolution du destinataire
   */
  public AlerteMiseAJourService(final EmailSender emailSender) {
    this.emailSender = emailSender;
  }

  /**
   * Envoie l'alerte ; sans clé Resend ni destinataire, rien n'est envoyé.
   *
   * @param reussie vrai si la version est installée
   * @param version version visée
   * @param message détail rapporté par le module de mise à jour
   */
  public void envoyer(final boolean reussie, final String version, final String message) {
    final Optional<EmailSender.ContexteEmail> ctx = emailSender.obtenirContexte();
    final Optional<String> destinataire = emailSender.obtenirDestinataireAlerte();
    if (ctx.isEmpty() || destinataire.isEmpty()) {
      LOG.warn("Alerte de mise à jour non envoyée : Resend ou destinataire absent");
      return;
    }
    final String sujet =
        reussie
            ? "Strapontin — version " + version + " installée"
            : "Strapontin — échec de la mise à jour vers " + version;
    final String corps =
        EmailTemplatesAlertes.alerteMiseAJour(reussie, version, message, ctx.get().baseUrl());
    try {
      emailSender.envoyer(
          ctx.get(),
          destinataire.get(),
          ctx.get().expediteur(),
          sujet,
          corps,
          sujet + ". " + message + " Administration : " + ctx.get().baseUrl() + "/admin");
      LOG.info("Alerte de mise à jour envoyée");
    } catch (final IllegalStateException e) {
      LOG.error("Échec d'envoi de l'alerte de mise à jour", e);
    }
  }
}

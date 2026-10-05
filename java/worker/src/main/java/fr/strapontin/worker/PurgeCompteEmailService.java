/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import fr.strapontin.worker.quota.GestionQuotaResendWorker;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Envoi email (Resend) des relances de purge de compte — même
 * infrastructure indépendante que {@link NotificationEmailDigestService}
 * (worker n'a pas accès à l'{@code EmailSender} du module web).
 * @since 3.0.0
 * @author Kwaite
 */
@Service
public class PurgeCompteEmailService {

  private static final Logger LOG = LoggerFactory.getLogger(PurgeCompteEmailService.class);

  private final ConfigServiceWorker configService;
  private final GestionQuotaResendWorker gestionQuotaResendWorker;
  private final NotificationDigestService.FabriqueResend fabriqueResend;

  /**
   * @param configService lecture de RESEND_API_KEY/CONTACT_EMAIL/PUBLIC_BASE_URL
   * @param gestionQuotaResendWorker garde-fou de quota Resend worker
   */
  @Autowired
  public PurgeCompteEmailService(
      final ConfigServiceWorker configService,
      final GestionQuotaResendWorker gestionQuotaResendWorker) {
    this(configService, gestionQuotaResendWorker, Resend::new);
  }

  /* package-private : constructeur de test, fabrique Resend substituable
   * (même package). */
  PurgeCompteEmailService(
      final ConfigServiceWorker configService,
      final GestionQuotaResendWorker gestionQuotaResendWorker,
      final NotificationDigestService.FabriqueResend fabriqueResend) {
    this.configService = configService;
    this.gestionQuotaResendWorker = gestionQuotaResendWorker;
    this.fabriqueResend = fabriqueResend;
  }

  /**
   * Relance un compte inactif avant sa suppression définitive.
   *
   * @param email destinataire
   * @param joursGrace nombre de jours avant suppression effective
   * @return {@code true} si l'email a été envoyé
   */
  public boolean envoyerRelanceInactivite(final String email, final int joursGrace) {
    return envoyer(
        email,
        "Votre compte Strapontin va être supprimé pour inactivité",
        baseUrl -> PurgeCompteEmailTemplate.relanceInactivite(joursGrace, baseUrl));
  }

  /**
   * Relance un compte invité jamais activé avant suppression du compte.
   *
   * @param email destinataire
   * @param lienActivation lien d'activation (nouveau token, valable 7 jours)
   * @param dateLimite dernier jour de validité du lien, annoncé dans l'email
   * @return {@code true} si l'email a été envoyé
   */
  public boolean envoyerRelanceInvitation(
      final String email, final String lienActivation, final LocalDate dateLimite) {
    return envoyer(
        email,
        "Votre invitation Strapontin expire bientôt",
        baseUrl -> PurgeCompteEmailTemplate.relanceInvitation(lienActivation, dateLimite, baseUrl));
  }

  private boolean envoyer(
      final String email,
      final String sujet,
      final java.util.function.Function<String, String> corps) {
    final Optional<String> cle = configService.lireConfig("RESEND_API_KEY");
    if (cle.isEmpty()) {
      LOG.debug("RESEND_API_KEY absent — relance de purge ignorée");
      return false;
    }
    if (!gestionQuotaResendWorker.reserverEnvoi()) {
      return false;
    }
    final String expediteur = configService.lireConfig("CONTACT_EMAIL").orElse("");
    final String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");
    try {
      final Resend resend = fabriqueResend.creer(cle.get());
      final CreateEmailOptions options =
          CreateEmailOptions.builder()
              .from(expediteur)
              .replyTo(expediteur)
              .to(List.of(email))
              .subject(sujet)
              .html(corps.apply(baseUrl))
              .text(sujet)
              .build();
      resend.emails().send(options);
      LOG.info("Relance de purge envoyée : {}", email);
      return true;
    } catch (Exception e) {
      LOG.error("Échec envoi relance de purge : {}", email, e);
      return false;
    }
  }
}

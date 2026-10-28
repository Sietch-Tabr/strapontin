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
 * Envoi email (Resend) des messages liés au compte (relances de purge,
 * perte du dernier canal de notification) — même
 * infrastructure indépendante que {@link NotificationEmailDigestService}
 * (worker n'a pas accès à l'{@code EmailSender} du module web).
 * @since 3.0.0
 * @author Kwaite
 */
@Service
public class EmailCompteService {

  private static final Logger LOG = LoggerFactory.getLogger(EmailCompteService.class);

  private final ConfigServiceWorker configService;
  private final GestionQuotaResendWorker gestionQuotaResendWorker;
  private final FabriqueResend fabriqueResend;

  /**
   * @param configService lecture de RESEND_API_KEY/CONTACT_EMAIL/PUBLIC_BASE_URL
   * @param gestionQuotaResendWorker garde-fou de quota Resend worker
   */
  @Autowired
  public EmailCompteService(
      final ConfigServiceWorker configService,
      final GestionQuotaResendWorker gestionQuotaResendWorker) {
    this(configService, gestionQuotaResendWorker, Resend::new);
  }

  /* package-private : constructeur de test, fabrique Resend substituable
   * (même package). */
  EmailCompteService(
      final ConfigServiceWorker configService,
      final GestionQuotaResendWorker gestionQuotaResendWorker,
      final FabriqueResend fabriqueResend) {
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
        baseUrl -> EmailCompteTemplate.relanceInactivite(joursGrace, baseUrl));
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
        baseUrl -> EmailCompteTemplate.relanceInvitation(lienActivation, dateLimite, baseUrl));
  }

  /**
   * Prévient un utilisateur que son push est arrêté faute d'appareil, avec
   * la cause et ce qu'il doit refaire : envoyé quels que soient ses canaux email.
   *
   * @param email destinataire
   * @param cause cause lisible du retrait du dernier appareil, peut être null
   * @return {@code true} si l'email a été envoyé
   */
  public boolean envoyerAvertissementCanalPerdu(final String email, final String cause) {
    return envoyer(
        email,
        "Vos notifications push Strapontin sont arrêtées",
        baseUrl -> EmailCompteTemplate.avertissementCanalPerdu(baseUrl, cause));
  }

  /**
   * Prévient l'utilisateur que ses notifications push n'ont jamais pris effet
   * (aucun appareil abonné), alors que l'email est désactivé.
   * @param email destinataire
   * @return {@code true} si l'email est parti
   */
  public boolean envoyerAvertissementPushJamaisActive(final String email) {
    return envoyer(
        email,
        "Vos notifications Strapontin ne sont pas activées",
        EmailCompteTemplate::avertissementPushJamaisActive);
  }

  private boolean envoyer(
      final String email,
      final String sujet,
      final java.util.function.Function<String, String> corps) {
    final Optional<String> cle = configService.lireConfig("RESEND_API_KEY");
    if (cle.isEmpty()) {
      LOG.debug("RESEND_API_KEY absent — email de compte ignoré");
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
      LOG.info("Email de compte envoyé : {}", sujet);
      return true;
    } catch (Exception e) {
      LOG.error("Échec envoi email de compte : {}", sujet, e);
      return false;
    }
  }
}

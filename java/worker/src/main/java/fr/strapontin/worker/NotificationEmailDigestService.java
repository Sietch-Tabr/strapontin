/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import fr.strapontin.worker.quota.GestionQuotaResendWorker;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Envoi email (Resend) du digest de notification, pour un utilisateur donné.
 *
 * <p>Extrait de {@link NotificationDigestService} pour rester sous le seuil de couplage entre
 * objets (20).
 *
 * @since 2.9.1
 * @author Kwaite
 */
class NotificationEmailDigestService {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationEmailDigestService.class);

  private final ConfigServiceWorker configService;
  private final GestionQuotaResendWorker gestionQuotaResendWorker;
  private final NotificationDigestService.FabriqueResend fabriqueResend;

  /*
   * package-private : instancié uniquement par NotificationDigestService (même
   * package).
   */
  NotificationEmailDigestService(
      final ConfigServiceWorker configService,
      final GestionQuotaResendWorker gestionQuotaResendWorker,
      final NotificationDigestService.FabriqueResend fabriqueResend) {
    this.configService = configService;
    this.gestionQuotaResendWorker = gestionQuotaResendWorker;
    this.fabriqueResend = fabriqueResend;
  }

  /* package-private : appelé par NotificationDigestService (même package). */
  boolean envoyerEmail(
      final String email, final List<NotificationDigestService.ConcertNotif> concerts) {
    if (concerts.isEmpty()) {
      return false;
    }
    final Optional<String> cle = configService.lireConfig("RESEND_API_KEY");
    if (cle.isEmpty()) {
      LOG.debug("RESEND_API_KEY absent — digest email ignoré");
      return false;
    }
    if (!gestionQuotaResendWorker.reserverEnvoi()) {
      // notification_envoyee_le n'est pas marquée pour ce canal (comportement
      // déjà existant, cf. clé "RESEND_API_KEY absent" ci-dessus) — retenté au
      // prochain cycle de digest plutôt que perdu.
      return false;
    }
    final String expediteur = configService.lireConfig("CONTACT_EMAIL").orElse("");
    final String baseUrl = configService.lireConfig("PUBLIC_BASE_URL").orElse("");

    final int nb = concerts.size();
    final String sujet =
        nb == 1
            ? "1 nouveau concert correspond à vos préférences"
            : "%d nouveaux concerts correspondent à vos préférences".formatted(nb);
    try {
      final Resend resend = fabriqueResend.creer(cle.get());
      final CreateEmailOptions options =
          CreateEmailOptions.builder()
              .from(expediteur)
              .replyTo(expediteur)
              .to(List.of(email))
              .subject(sujet)
              .html(EmailDigestTemplate.construireCorpsEmail(sujet, concerts, baseUrl))
              .text(sujet)
              .build();
      resend.emails().send(options);
      LOG.info("Digest email envoyé : {} ({} concert(s))", email, nb);
      return true;
    } catch (Exception e) {
      LOG.error("Échec envoi digest email : {}", email, e);
      return false;
    }
  }
}

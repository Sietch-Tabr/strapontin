/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import fr.strapontin.core.CompteurEnvoisResend;
import fr.strapontin.core.HorairesJobs;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Alerte l'admin par email au franchissement du seuil quota
 * Resend (80%, détail : CLAUDE.md, section Resend).
 * @since 2.7.2
 * @author Kwaite
 */
@Service
class AlerteQuotaResendService {

  private static final Logger LOG = LoggerFactory.getLogger(AlerteQuotaResendService.class);

  private final GestionQuotaResend gestionQuotaResend;
  private final EmailAlerteService emailAlerteService;
  private final EmailSender emailSender;

  /* package-private : instancié uniquement par Spring (même package). */
  AlerteQuotaResendService(
      final GestionQuotaResend gestionQuotaResend,
      final EmailAlerteService emailAlerteService,
      final EmailSender emailSender) {
    this.gestionQuotaResend = gestionQuotaResend;
    this.emailAlerteService = emailAlerteService;
    this.emailSender = emailSender;
  }

  /**
   * Vérifie le seuil toutes les 15 minutes (compteur recalé sur l'usage réel
   * Resend) et envoie une alerte si franchi et pas encore signalé pour la
   * fenêtre jour/mois courante.
   */
  @Scheduled(cron = HorairesJobs.ALERTE_QUOTA_RESEND, zone = HorairesJobs.FUSEAU)
  /* package-private : appelé par le scheduler Spring (même package). */
  void verifier() {
    emailSender.recalerQuotaSurUsageReel();
    final CompteurEnvoisResend compteur = gestionQuotaResend.lireCompteurCourant();
    if (!gestionQuotaResend.seuilFranchiNonSignale(compteur)) {
      return;
    }

    final Optional<String> destinataire = emailAlerteService.obtenirDestinataireAlerte();
    if (destinataire.isEmpty()) {
      LOG.warn("alerteQuotaResend: aucun destinataire d'alerte disponible");
      return;
    }

    final boolean envoye =
        emailAlerteService.envoyerAlerteQuotaResend(destinataire.get(), compteur);
    if (envoye) {
      gestionQuotaResend.marquerAlerteEnvoyee(compteur);
    } else {
      LOG.warn(
          "alerteQuotaResend: échec d'envoi — seuil non marqué signalé, retenté au cycle suivant");
    }
  }
}

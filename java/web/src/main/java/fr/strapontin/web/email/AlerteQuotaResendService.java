/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import fr.strapontin.core.CompteurEnvoisResend;
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

  /* package-private : instancié uniquement par Spring (même package). */
  AlerteQuotaResendService(
      final GestionQuotaResend gestionQuotaResend, final EmailAlerteService emailAlerteService) {
    this.gestionQuotaResend = gestionQuotaResend;
    this.emailAlerteService = emailAlerteService;
  }

  /**
   * Vérifie le seuil toutes les 15 minutes et envoie une alerte si franchi et
   * pas encore signalé pour la fenêtre jour/mois courante.
   */
  @Scheduled(cron = "0 */15 * * * *")
  /* package-private : appelé par le scheduler Spring (même package). */
  void verifier() {
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

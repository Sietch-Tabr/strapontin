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
 * Alerte l'administrateur par email quand le quota d'envoi Resend jour ou mois franchit 80% (suite
 * de l'incident — boucle de bounces ayant épuisé le quota en 14 minutes sans garde-fou local).
 *
 * <p>Même pattern que {@code AlerteLogsAdminService} (polling toutes les 15 minutes, ne re-signale
 * pas un seuil déjà signalé) mais l'état "déjà signalé" est porté par {@link
 * CompteurEnvoisResend#alerteJourEnvoyee()}/{@link CompteurEnvoisResend#alerteMoisEnvoyee()}
 * plutôt qu'un curseur séparé — remis à {@code false} au rollover jour/mois par {@link
 * GestionQuotaResend}, dans le même document {@code app_config}.
 *
 * @since 2.7.2
 * @author Kwaite
 */
@Service
class AlerteQuotaResendService {

  private static final Logger LOG = LoggerFactory.getLogger(AlerteQuotaResendService.class);

  private final GestionQuotaResend gestionQuotaResend;
  private final EmailAlerteService emailAlerteService;

  AlerteQuotaResendService(
      final GestionQuotaResend gestionQuotaResend, final EmailAlerteService emailAlerteService) {
    this.gestionQuotaResend = gestionQuotaResend;
    this.emailAlerteService = emailAlerteService;
  }

  /**
   * Vérifie le seuil toutes les 15 minutes et envoie une alerte si franchi et pas encore signalé
   * pour la fenêtre jour/mois courante.
   */
  @Scheduled(cron = "0 */15 * * * *")
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

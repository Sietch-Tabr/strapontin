/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.sante.SuiviSanteSourcesService;
import org.springframework.stereotype.Component;

/**
 * Collaborateurs de reporting partagés par les exécuteurs de fetch (batch artistes, région, salles)
 * — notification immédiate, bilan/durée de job, suivi passif des absences par source.
 *
 * <p>Regroupe trois dépendances co-localisées dans {@link WorkerFetchService} et les exécuteurs
 * qu'il assemble (voir {@link FetchArtistesBatchExecutor}), pour rester sous le seuil de nombre de
 * paramètres de constructeur (8).
 *
 * @param notificationDigestService déclenchement des notifications immédiates
 * @param workerStatsService journalisation du bilan et de la durée de chaque job
 * @param suiviSanteSourcesService suivi passif des échecs de fetch par salle/artiste
 * @since 2.9.1
 * @author Kwaite
 */
@Component
public record FetchRapportage(
    NotificationDigestService notificationDigestService,
    WorkerStatsService workerStatsService,
    SuiviSanteSourcesService suiviSanteSourcesService) {}

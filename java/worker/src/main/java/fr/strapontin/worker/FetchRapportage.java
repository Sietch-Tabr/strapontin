/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.sante.SuiviSanteSourcesService;
import org.springframework.stereotype.Component;

/**
 * Collaborateurs de reporting partagés par les exécuteurs de fetch —
 * notification immédiate, bilan/durée de job, suivi passif des
 * absences par source. Regroupe trois dépendances co-localisées pour
 * rester sous le seuil de paramètres de constructeur (8).
 * @since 2.9.1
 * @author Kwaite
 */
@Component
public record FetchRapportage(
    NotificationDigestService notificationDigestService,
    WorkerStatsService workerStatsService,
    SuiviSanteSourcesService suiviSanteSourcesService) {}

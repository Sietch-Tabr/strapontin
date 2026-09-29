/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.sante.SuiviSanteSourcesService;
import org.springframework.stereotype.Component;

/**
 * Collaborateurs post-fetch partagés par les exécuteurs — notification,
 * bilan de job, suivi passif par source, relevé des lieux Eventim ; regroupés
 * pour rester sous le seuil de paramètres de constructeur (8).
 * @since 2.9.1
 * @author Kwaite
 */
@Component
public record FetchRapportage(
    NotificationDigestService notificationDigestService,
    WorkerStatsService workerStatsService,
    SuiviSanteSourcesService suiviSanteSourcesService,
    EnregistreurLieuxEventim enregistreurLieuxEventim) {}

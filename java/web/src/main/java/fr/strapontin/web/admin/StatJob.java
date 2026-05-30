/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.admin;

import java.time.Instant;

/// Statistiques d'un job Quartz (réponse GET /api/admin/jobs).
///
/// @since 2.1.0
/// @author Kwaite
public record StatJob(
    String typeJob,
    Instant derniereExec,
    Integer dureeMs,
    Integer nbInseres,
    Integer nbMisAJour,
    String erreur,
    Instant prochaineExec) {}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Concert flagué par la purge nocturne comme candidat à la suppression
 * définitive, en attente de revue manuelle depuis l'admin Concerts.
 * @since 2.8.3
 * @author Kwaite
 */
public record ConcertSuppressionSuggeree(
    UUID id,
    String artiste,
    String salle,
    String ville,
    LocalDate date,
    LocalTime heure,
    int absencesConsecutives,
    Instant suppressionSuggereeLe) {}

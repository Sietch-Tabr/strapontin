/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ligne du référentiel artistes pour l'onglet admin « Artistes ». {@code
 * interventionDepuis} date du doute sur l'artiste Ticketmaster qui explique un
 * statut « Invalide » (sinon null).
 * @since 2.6.0
 * @author Kwaite
 */
public record ArtisteReferentiel(
    UUID id,
    String nom,
    String nomAffiche,
    long nbConcerts,
    List<String> sources,
    String statut,
    String wikidataId,
    String deezerId,
    String identifiantTmExterne,
    Instant interventionDepuis) {}

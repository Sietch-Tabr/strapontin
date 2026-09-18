/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.List;
import java.util.UUID;

/**
 * Ligne du référentiel salles pour l'onglet admin « Salles ».
 * @since 2.6.0
 * @author Kwaite
 */
public record SalleReferentiel(
    UUID id,
    String nom,
    String nomAffiche,
    String ville,
    String dept,
    long nbConcerts,
    List<String> sources,
    String statut,
    String wikidataId,
    String nomParent) {}

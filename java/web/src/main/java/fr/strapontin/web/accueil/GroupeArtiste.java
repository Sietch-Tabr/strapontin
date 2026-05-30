/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.accueil;

import java.util.List;

/// Groupe de concerts par artiste dans la vue accueil.
///
/// @since 2.1.0
/// @author Kwaite
public record GroupeArtiste(
    String artiste,
    String artisteNorm,
    boolean estTournee,
    boolean estNew,
    List<ConcertAccueil> concerts) {}

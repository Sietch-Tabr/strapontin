/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.accueil;

import java.util.List;

/**
 * Groupe de concerts par artiste dans la vue accueil — {@code artisteId}
 * sert au masquage/démasquage par groupe, {@code artisteNorm} aux clés
 * Vue et ancres de navigation.
 * @since 2.1.0
 * @author Kwaite
 */
public record GroupeArtiste(
    String artiste,
    String artisteNorm,
    String artisteId,
    boolean estTournee,
    boolean estNew,
    List<ConcertAccueil> concerts) {}

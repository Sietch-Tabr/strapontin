/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.accueil;

import java.util.List;

/**
 * Réponse de GET /api/accueil : inclusion effective des indisponibles
 * (préférence comprise) et nombre d'indisponibles correspondant aux filtres.
 * @since 2.1.0
 * @author Kwaite
 */
public record ReponseAccueil(
    List<GroupeArtiste> artistesGroupes,
    int nbArtistes,
    int nbSalles,
    boolean inclureIndisponibles,
    int nbIndisponibles) {}

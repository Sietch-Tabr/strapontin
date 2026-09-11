/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.accueil;

import java.util.List;

/**
 * Réponse de l'endpoint GET /api/accueil.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record ReponseAccueil(List<GroupeArtiste> artistesGroupes, int nbArtistes, int nbSalles) {}

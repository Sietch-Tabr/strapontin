/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import java.util.List;

/**
 * Réponse de l'endpoint GET /api/salles.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record ReponseSalles(
    List<SalleUtilisateur> salles, List<ResultatSalle> resultats, boolean dejaLaSuivie) {}

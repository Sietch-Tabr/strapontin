/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import java.util.List;

/**
 * Configuration d'un utilisateur (réponse GET /api/user/config).
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record ConfigUtilisateur(
    List<String> departements,
    boolean filtreDisponibles,
    String frequenceNotifications,
    boolean notifArtistes,
    boolean notifSalles,
    boolean notifArtistesPush,
    boolean notifSallesPush,
    String theme,
    boolean resendDisponible) {}

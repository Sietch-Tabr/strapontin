/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.time.Instant;

/**
 * Représentation admin d'un utilisateur (réponse GET /api/admin/utilisateurs) ; {@code parrain}
 * est l'identifiant de l'utilisateur qui l'a invité, {@code plafondInvitations} son plafond propre
 * (nul : plafond par défaut).
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record UtilisateurAdmin(
    String id,
    String email,
    String role,
    String username,
    String displayUsername,
    boolean mustChangeCredentials,
    Instant createdAt,
    Instant derniereActivite,
    Instant revocationAdminLe,
    int nbSalles,
    int nbArtistes,
    boolean verrouille,
    String parrain,
    Integer plafondInvitations) {}

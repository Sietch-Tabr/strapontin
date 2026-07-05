/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

/// Résultat d'une réinitialisation de mot de passe déclenchée par un administrateur.
///
/// @param email     email du compte cible
/// @param lienReset lien de réinitialisation, exposé à l'admin uniquement si Resend n'est pas
///     disponible (#815) — sinon transmis par email au seul utilisateur concerné
/// @since 2.6.4
/// @author Kwaite
public record ResultatReset(String email, String lienReset) {}

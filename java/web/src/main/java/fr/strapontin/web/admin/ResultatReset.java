/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

/**
 * Résultat d'une réinitialisation de mot de passe déclenchée par un admin.
 * @param email email du compte cible
 * @param lienReset lien de reset, exposé à l'admin seulement si Resend
 *     indisponible (sinon transmis par email au seul utilisateur)
 * @since 2.6.4
 * @author Kwaite
 */
public record ResultatReset(String email, String lienReset) {}

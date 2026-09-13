/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

/**
 * Résultat de la création d'un utilisateur par un admin.
 * @param email email du compte créé
 * @param identifiant identifiant temporaire généré
 * @param lienReset lien d'invitation/réinitialisation envoyé par email
 * @since 2.1.0
 * @author Kwaite
 */
public record ResultatCreation(String email, String identifiant, String lienReset) {}

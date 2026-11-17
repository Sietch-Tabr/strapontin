/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.miseajour;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Demande d'installation d'une version, avec la réauthentification de
 * l'administrateur.
 *
 * @param version version à installer, parmi celles proposées
 * @param motDePasse mot de passe actuel de l'administrateur
 * @param codeTotp code TOTP en cours
 */
public record RequeteMiseAJour(
    @NotBlank @Size(max = 40) String version,
    @NotBlank @Size(max = 200) String motDePasse,
    @NotBlank @Size(max = 20) String codeTotp) {}

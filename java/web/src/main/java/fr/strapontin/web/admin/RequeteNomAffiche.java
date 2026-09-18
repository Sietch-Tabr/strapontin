/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import jakarta.validation.constraints.Size;

/**
 * Corps JSON de la requête PATCH de modification du nom affiché d'une salle ou
 * d'un artiste.
 *
 * @param nomAffiche null/vide pour revenir au nom automatique
 * @since 2.6.0
 * @author Kwaite
 */
public record RequeteNomAffiche(
    @Size(max = 200, message = "Nom trop long (200 caractères max).") String nomAffiche) {}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import jakarta.validation.constraints.Size;

/**
 * Corps JSON d'une requête portant un slug Digitick.
 *
 * @param slug vide pour supprimer le slug existant ({@link
 *   SourcesController#mettreAJourDigitickSlug})
 * @since 2.4.0
 * @author Kwaite
 */
public record RequeteSlug(@Size(max = 100, message = "Slug Digitick invalide.") String slug) {}

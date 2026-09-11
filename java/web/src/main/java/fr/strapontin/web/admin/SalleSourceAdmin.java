/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.UUID;

/**
 * Salle pour la gestion admin des sources Digitick.
 *
 * @param salleId    UUID stable de la salle
 * @param nom          nom affiché
 * @param digitickSlug slug Digitick (null si non configuré)
 * @param ville        ville (null si non renseignée)
 * @param dept         numéro de département (null si non renseigné)
 * @since 2.3.0
 * @author Kwaite
 */
public record SalleSourceAdmin(
    UUID salleId, String nom, String digitickSlug, String ville, String dept) {}

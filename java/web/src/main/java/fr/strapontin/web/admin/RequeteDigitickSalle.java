/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import jakarta.validation.constraints.Size;

/**
 * Corps JSON pour créer/modifier une salle Digitick — nom/slug
 * obligatoires à la création, optionnels (null = inchangé) en
 * modification (vérifié par chaque contrôleur, ce record ne borne que
 * la longueur).
 * @since 2.4.0
 * @author Kwaite
 */
public record RequeteDigitickSalle(
    @Size(max = 200, message = "Nom de salle invalide.") String nom,
    @Size(max = 100, message = "Slug Digitick invalide.") String slug,
    @Size(max = 100, message = "Ville invalide.") String ville,
    @Size(max = 3, message = "Département invalide.") String dept) {}

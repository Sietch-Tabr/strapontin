/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import jakarta.validation.constraints.Size;

/// Corps JSON pour la création ou la modification d'une salle Digitick.
///
/// <p>Le caractère obligatoire de {@code nom}/{@code slug} diffère selon l'endpoint (obligatoires à
/// la création, optionnels — {@code null} = inchangé — en modification) : ce record ne porte que
/// des bornes de longueur, la présence est vérifiée séparément par chaque contrôleur.
///
/// @param nom nom d'affichage de la salle
/// @param slug slug Digitick
/// @param ville ville (aide à la recherche avant le premier fetch)
/// @param dept numéro de département (ex. {@code "77"})
/// @since 2.4.0
/// @author Kwaite
public record RequeteDigitickSalle(
    @Size(max = 200, message = "Nom de salle invalide.") String nom,
    @Size(max = 100, message = "Slug Digitick invalide.") String slug,
    @Size(max = 100, message = "Ville invalide.") String ville,
    @Size(max = 3, message = "Département invalide.") String dept) {}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

/// Corps JSON pour la création ou la modification d'une salle Digitick.
///
/// @param nom   nom d'affichage de la salle (obligatoire)
/// @param slug  slug Digitick (obligatoire à la création, optionnel en modification)
/// @param ville ville (optionnel — aide à la recherche avant le premier fetch)
/// @param cp    code postal sur 5 caractères (optionnel)
/// @since 2.4.0
/// @author Kwaite
public record RequeteDigitickSalle(String nom, String slug, String ville, String cp) {}

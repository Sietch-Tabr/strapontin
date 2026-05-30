/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

/// Onglet intra-page utilisé par le fragment {@code layout/fragments/onglets}.
///
/// @param ancre  ancre HTML cible (ex. "#preferences")
/// @param label  libellé de l'onglet
/// @param icone  identifiant d'icône Lucide (ex. "icon-sliders-horizontal")
/// @since 2.1.0
/// @author Kwaite
public record OngletItem(String ancre, String label, String icone) {}

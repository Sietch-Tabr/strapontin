/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.vue;

/// Élément de navigation injecté dans tous les templates via [ModeleGlobalAdvice].
///
/// @param href   URL de la page
/// @param page   identifiant court utilisé pour {@code currentPage} (ex. "accueil")
/// @param label  libellé affiché dans la nav
/// @param icone  identifiant d'icône Lucide (ex. "icon-home")
/// @since 2.1.0
/// @author Kwaite
public record NavigationItem(String href, String page, String label, String icone) {}

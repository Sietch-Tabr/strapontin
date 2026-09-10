/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.time.Instant;

/// Une entrée de la vue unifiée des signalements de matching (#1193).
///
/// @param categorie {@code "nouveau"} (table {@code signalements_matching}, résolvable via cet
///   onglet) ou l'un des mécanismes déjà établis ({@code "necessite_intervention"}, {@code
///   "contamination_salle"}, {@code "doublon_salle"}) — affichés en lecture seule, déjà résolvables
///   depuis les onglets Salles/Artistes existants
/// @param id identifiant de la ligne (numérique pour {@code "nouveau"}, UUID d'entité sinon)
/// @param type type précis du signalement
/// @param entiteType {@code "salle"} ou {@code "artiste"}
/// @param entiteId identifiant de l'entité concernée
/// @param confiance niveau de confiance
/// @param detecteLe date de détection, peut être {@code null} pour les mécanismes établis sans
///   horodatage dédié
/// @param details métadonnées structurées (JSON brut), peut être {@code null}
/// @since 2.9.0
/// @author Kwaite
public record EntreeSignalement(
    String categorie,
    String id,
    String type,
    String entiteType,
    String entiteId,
    String confiance,
    Instant detecteLe,
    String details) {}

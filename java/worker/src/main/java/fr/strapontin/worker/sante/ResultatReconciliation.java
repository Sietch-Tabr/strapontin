/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

/// Bilan d'une exécution de {@link ReconciliationNocturneService} (#706).
///
/// @param sallesVerifiees        nombre de salles TM suspectes vérifiées (health-check)
/// @param sallesRedecouvertes    nombre de salles TM mortes ré-associées automatiquement
/// @param ambigues               nombre de redécouvertes ambiguës (0 ou ≥2 candidats), en attente
///     de correction manuelle via #710
/// @param enrichissementsSalle   nombre de salles enrichies (Wikidata/OSM, #711)
/// @param enrichissementsArtiste nombre d'artistes enrichis (Wikidata/Deezer, #711/#716)
/// @param incoherencesWikidata   nombre de salles dont le {@code wikidata_id} déjà connu
///     contredit la {@code ville} connue (#1005)
/// @since 2.6.0
/// @author Kwaite
public record ResultatReconciliation(
    int sallesVerifiees,
    int sallesRedecouvertes,
    int ambigues,
    int enrichissementsSalle,
    int enrichissementsArtiste,
    int incoherencesWikidata) {}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

/// Bilan d'une exécution de {@link ReconciliationNocturneService}.
///
/// @param sallesVerifiees nombre de salles TM suspectes vérifiées (health-check)
/// @param sallesRedecouvertes nombre de salles TM mortes ré-associées automatiquement
/// @param ambigues nombre de redécouvertes ambiguës (0 ou ≥2 candidats), en attente de correction
///   manuelle
/// @param enrichissementsSalle nombre de salles enrichies (Wikidata/OSM)
/// @param enrichissementsArtiste nombre d'artistes enrichis (Wikidata/Deezer)
/// @param incoherencesWikidata nombre de salles dont le {@code wikidata_id} déjà connu contredit la
///   {@code ville} connue
/// @param typesWikidataInvalides nombre de {@code wikidata_id} déjà stockés invalidés car leur type
///   Wikidata est désormais exclu (gare/tram/administratif/commerce)
/// @param nomsIncoherentsTM nombre d'artistes suivis dont le nom retourné par TM diverge de
///   l'artiste suivi (tribute/homonyme rattaché par erreur)
/// @param sallesOrphelinesResolues nombre de concerts sans {@code salle_id} résolus par une
///   nouvelle tentative de correspondance floue (voir {@code ReconciliateurSalles})
/// @param sallesFusionneesParCleForte nombre de paires de salles déjà établies fusionnées
///   automatiquement (même artiste+date+heure+dept partagé, voir {@code
///   FusionSallesClePorteeService})
/// @since 2.6.0
/// @author Kwaite
public record ResultatReconciliation(
    int sallesVerifiees,
    int sallesRedecouvertes,
    int ambigues,
    int enrichissementsSalle,
    int enrichissementsArtiste,
    int incoherencesWikidata,
    int typesWikidataInvalides,
    int nomsIncoherentsTM,
    int sallesOrphelinesResolues,
    int sallesFusionneesParCleForte) {}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

/**
 * Bilan d'une exécution de {@link ReconciliationNocturneService}.
 *
 * @param sallesVerifiees nombre de salles TM suspectes vérifiées (health-check)
 * @param sallesRedecouvertes nombre de salles TM mortes ré-associées automatiquement
 * @param ambigues nombre de redécouvertes ambiguës (0 ou ≥2 candidats), en attente de correction
 *   manuelle
 * @param enrichissementsSalle nombre de salles enrichies (Wikidata/OSM)
 * @param enrichissementsArtiste nombre d'artistes enrichis (Wikidata/Deezer)
 * @param incoherencesWikidata nombre de salles dont le {@code wikidata_id} déjà connu contredit la
 *   {@code ville} connue
 * @param typesWikidataInvalides nombre de {@code wikidata_id} déjà stockés invalidés car leur type
 *   Wikidata est désormais exclu (gare/tram/administratif/commerce)
 * @param nomsIncoherentsTM nombre d'artistes suivis dont le nom retourné par TM diverge de
 *   l'artiste suivi (tribute/homonyme rattaché par erreur)
 * @param sallesOrphelinesResolues nombre de concerts sans {@code salle_id} résolus par une
 *   nouvelle tentative de correspondance floue (voir {@code ReconciliateurSalles})
 * @param sallesFusionneesParCleForte nombre de paires de salles déjà établies fusionnées
 *   automatiquement (même artiste+date+heure+dept partagé, voir {@code
 *   FusionSallesClePorteeService})
 * @param contaminationsMultiSalleSignalees nombre de salles signalées comme hébergeant
 *   probablement plusieurs lieux réels distincts (voir {@link
 *   CoherenceMultiSalleService#detecterContamination})
 * @param doublonsMultiSalleSignales nombre de paires de salles signalées comme doublons potentiels
 *   sans {@code wikidata_id} ni concert partagé (voir {@link
 *   CoherenceMultiSalleService#detecterDoublons})
 * @param motsGenericsSuggeres nombre de mots-types de salle génériques nouvellement suggérés (voir
 *   {@link SuggestionMotsGenericsService})
 * @param integriteSignalements nombre de signalements posés par les 4 invariants généralistes de
 *   {@link ControleIntegriteService}
 * @since 2.6.0
 * @author Kwaite
 */
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
    int sallesFusionneesParCleForte,
    int contaminationsMultiSalleSignalees,
    int doublonsMultiSalleSignales,
    int motsGenericsSuggeres,
    int integriteSignalements) {}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

/**
 * Bilan d'une exécution de {@link ReconciliationNocturneService} — un
 * compteur par collaborateur (santé, enrichissement, réconciliation,
 * intégrité), noms de champs auto-descriptifs.
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
    int evenementsFusionnesArtiste,
    int fichesOrigineReportMarquees,
    int positionsRealignees,
    int contaminationsMultiSalleSignalees,
    int doublonsMultiSalleSignales,
    int motsGenericsSuggeres,
    int integriteSignalements) {}

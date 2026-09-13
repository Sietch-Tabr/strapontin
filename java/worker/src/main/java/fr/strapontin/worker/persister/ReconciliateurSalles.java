/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceSalle;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Façade de réconciliation des salles entre fetches successifs — délègue à trois collaborateurs par
 * responsabilité (v2.8.7, extraction pure, aucun changement fonctionnel ni de contrat) : {@link
 * ReconciliationNomsSalles} (variantes de nommage intra-lot/inter-jobs, absorption des renommages),
 * {@link ResolveurSalleParConcert} (résolution/création d'une salle pour un concert entrant, par
 * identifiant source/tokens/clé forte) et {@link ReconciliateurSallesOrphelines} (retentative
 * nocturne des concerts sans salle).
 *
 * <p>Utilise {@link CorrespondanceSalle#estDoublonSalle} (module {@code core}, partagé avec {@code
 * sources} — voir {@code WikidataSalleSource}) pour déterminer si deux noms désignent la même
 * salle.
 *
 * @since 2.5.7
 * @author Kwaite
 */
@Service
public class ReconciliateurSalles {

  private final ReconciliationNomsSalles reconciliationNoms;
  private final ResolveurSalleParConcert resolveurSalle;
  private final ReconciliateurSallesOrphelines reconciliateurOrphelines;

  /**
   * @param reconciliationNoms réconciliation des variantes de nommage
   * @param resolveurSalle résolution/création d'une salle pour un concert entrant
   * @param reconciliateurOrphelines retentative nocturne des concerts sans salle
   */
  public ReconciliateurSalles(
      final ReconciliationNomsSalles reconciliationNoms,
      final ResolveurSalleParConcert resolveurSalle,
      final ReconciliateurSallesOrphelines reconciliateurOrphelines) {
    this.reconciliationNoms = reconciliationNoms;
    this.resolveurSalle = resolveurSalle;
    this.reconciliateurOrphelines = reconciliateurOrphelines;
  }

  /**
   * Calcule le coefficient de Jaccard sur les tokens des deux chaînes.
   *
   * @param nomA première chaîne normalisée
   * @param nomB deuxième chaîne normalisée
   * @return coefficient de Jaccard entre 0.0 et 1.0
   * @see CorrespondanceSalle#jaccardTokens
   */
  public static double jaccardTokens(final String nomA, final String nomB) {
    return CorrespondanceSalle.jaccardTokens(nomA, nomB);
  }

  /**
   * Vérifie si deux noms de salle normalisés désignent la même salle.
   *
   * @param nomA premier nom normalisé
   * @param nomB deuxième nom normalisé
   * @return {@code true} si les salles sont considérées identiques
   * @see CorrespondanceSalle#estDoublonSalle
   */
  public static boolean estDoublonSalle(final String nomA, final String nomB) {
    return CorrespondanceSalle.estDoublonSalle(nomA, nomB);
  }

  /**
   * @see ReconciliationNomsSalles#reconcilierSalles
   */
  /* package-private : implémente la façade publique, appelé uniquement au sein du package persister. */
  List<ValeurConcert> reconcilierSalles(final List<ValeurConcert> lot) {
    return reconciliationNoms.reconcilierSalles(lot);
  }

  /**
   * @see ReconciliationNomsSalles#absorberRenommagesSalles
   */
  /* package-private : implémente la façade publique, appelé uniquement au sein du package persister. */
  void absorberRenommagesSalles(
      final List<ValeurConcert> concerts, final Map<String, UUID> cachesSalles) {
    reconciliationNoms.absorberRenommagesSalles(concerts, cachesSalles);
  }

  /**
   * @see ResolveurSalleParConcert#upsertSalleSource
   */
  /* package-private : implémente la façade publique, appelé uniquement au sein du package persister. */
  UUID upsertSalleSource(final ValeurConcert concert) {
    return resolveurSalle.upsertSalleSource(concert);
  }

  /**
   * @see ResolveurSalleParConcert#chercherSalleParTokens
   */
  /* package-private : implémente la façade publique, appelé uniquement au sein du package persister. */
  UUID chercherSalleParTokens(final String nom, final String dept) {
    return resolveurSalle.chercherSalleParTokens(nom, dept);
  }

  /**
   * @see ResolveurSalleParConcert#chercherSalleParCleForte
   */
  /* package-private : implémente la façade publique, appelé uniquement au sein du package persister. */
  UUID chercherSalleParCleForte(final ValeurConcert concert) {
    return resolveurSalle.chercherSalleParCleForte(concert);
  }

  /**
   * @see ResolveurSalleParConcert#raccrocherSallesParNomNorm
   */
  /* package-private : implémente la façade publique, appelé uniquement au sein du package persister. */
  List<ValeurConcert> raccrocherSallesParNomNorm(final List<ValeurConcert> concerts) {
    return resolveurSalle.raccrocherSallesParNomNorm(concerts);
  }

  /**
   * @see ResolveurSalleParConcert#deptsConnus
   */
  /* package-private : implémente la façade publique, appelé uniquement au sein du package persister. */
  Map<UUID, String> deptsConnus(final Set<UUID> salleIds) {
    return resolveurSalle.deptsConnus(salleIds);
  }

  /**
   * @see ReconciliateurSallesOrphelines#retenterSallesOrphelines
   */
  public int retenterSallesOrphelines() {
    return reconciliateurOrphelines.retenterSallesOrphelines();
  }
}

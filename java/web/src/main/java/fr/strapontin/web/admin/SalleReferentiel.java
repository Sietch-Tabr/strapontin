/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ligne du référentiel salles pour l'onglet admin « Salles ». {@code
 * suspicions} explique un statut « À vérifier » ou « Invalide » : une entrée
 * par source suspecte ; {@code lieuEventim} est le nom exact du lieu interrogé
 * chez Eventim (null s'il n'y en a pas).
 * @since 2.6.0
 * @author Kwaite
 */
public record SalleReferentiel(
    UUID id,
    String nom,
    String nomAffiche,
    String ville,
    String dept,
    long nbConcerts,
    List<String> sources,
    String statut,
    String wikidataId,
    String nomParent,
    List<SuspicionSource> suspicions,
    String lieuEventim) {

  /**
   * Source d'une salle qui n'a rien renvoyé lors de plusieurs fetchs consécutifs.
   *
   * @param source nom de la source (ticketmaster, eventim)
   * @param nbVides nombre de fetchs consécutifs sans résultat
   * @param depuis début de la suspicion
   */
  public record SuspicionSource(String source, int nbVides, Instant depuis) {}
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.QuotaTM;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * État mutable partagé entre {@link TicketmasterSource} et {@link TicketmasterPageFetcher} :
 * dernier quota TM extrait, concerts manqués par plafonnement de pagination et bilan
 * d'appels/erreurs HTTP.
 *
 * <p>Regroupe ces trois compteurs en un seul type injecté, pour réduire le couplage entre objets de
 * {@link TicketmasterSource} (auparavant trois champs de types distincts).
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class EtatFetchTM {

  private final AtomicReference<QuotaTM> dernierQuota = new AtomicReference<>();
  private final AtomicInteger concertsManquesCumules = new AtomicInteger();
  private final CompteurBilanFetch compteurBilan = new CompteurBilanFetch();

  EtatFetchTM() {}

  /**
   * Retourne le dernier quota TM extrait, ou {@code null} si aucun appel n'a été fait.
   *
   * @return quota courant ou null
   */
  QuotaTM quota() {
    return dernierQuota.get();
  }

  /**
   * Met à jour le dernier quota TM extrait.
   *
   * @param quota nouveau quota, ignoré si {@code null}
   */
  void mettreAJourQuota(final QuotaTM quota) {
    if (quota != null) {
      dernierQuota.set(quota);
    }
  }

  /**
   * Ajoute au compteur de concerts non récupérés par plafonnement de la pagination.
   *
   * @param n nombre de concerts manqués à ajouter
   */
  void ajouterConcertsManques(final int n) {
    concertsManquesCumules.addAndGet(n);
  }

  /**
   * Retourne le nombre cumulé de concerts manqués depuis le dernier prélèvement, et remet le
   * compteur à zéro.
   *
   * @return le nombre de concerts manqués depuis le dernier prélèvement
   */
  int preleverConcertsManques() {
    return concertsManquesCumules.getAndSet(0);
  }

  /** Enregistre un appel HTTP effectué. */
  void enregistrerAppel() {
    compteurBilan.enregistrerAppel();
  }

  /** Enregistre une erreur d'appel HTTP. */
  void enregistrerErreur() {
    compteurBilan.enregistrerErreur();
  }

  /**
   * Retourne le bilan agrégé appels/erreurs HTTP depuis le dernier prélèvement et remet les
   * compteurs à zéro.
   *
   * @return le bilan depuis le dernier prélèvement
   */
  BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }
}

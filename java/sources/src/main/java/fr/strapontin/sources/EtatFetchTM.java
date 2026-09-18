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
 * État mutable partagé entre {@link TicketmasterSource} et {@link
 * TicketmasterPageFetcher} : quota TM, concerts manqués (pagination),
 * bilan d'appels/erreurs — regroupe trois compteurs en un type injecté.
 * @since 2.9.1
 * @author Kwaite
 */
final class EtatFetchTM {

  private final AtomicReference<QuotaTM> dernierQuota = new AtomicReference<>();
  private final AtomicInteger concertsManquesCumules = new AtomicInteger();
  private final CompteurBilanFetch compteurBilan = new CompteurBilanFetch();

  /* package-private : instancié uniquement par TicketmasterSource (même
   * package). */
  EtatFetchTM() {}

  /**
   * Retourne le dernier quota TM extrait, ou {@code null} si aucun appel n'a
   * été fait.
   *
   * @return quota courant ou null
   */
  /* package-private : appelé par TicketmasterSource/TicketmasterPageFetcher
   * (même package). */
  QuotaTM quota() {
    return dernierQuota.get();
  }

  /**
   * Met à jour le dernier quota TM extrait.
   *
   * @param quota nouveau quota, ignoré si {@code null}
   */
  /* package-private : appelé par TicketmasterSource/TicketmasterPageFetcher
   * (même package). */
  void mettreAJourQuota(final QuotaTM quota) {
    if (quota != null) {
      dernierQuota.set(quota);
    }
  }

  /**
   * Ajoute au compteur de concerts non récupérés par plafonnement de la
   * pagination.
   *
   * @param nombre nombre de concerts manqués à ajouter
   */
  /* package-private : appelé par TicketmasterSource/TicketmasterPageFetcher
   * (même package). */
  void ajouterConcertsManques(final int nombre) {
    concertsManquesCumules.addAndGet(nombre);
  }

  /**
   * Retourne le nombre cumulé de concerts manqués depuis le dernier
   * prélèvement, et remet le compteur à zéro.
   *
   * @return le nombre de concerts manqués depuis le dernier prélèvement
   */
  /* package-private : appelé par TicketmasterSource/TicketmasterPageFetcher
   * (même package). */
  int preleverConcertsManques() {
    return concertsManquesCumules.getAndSet(0);
  }

  /** Enregistre un appel HTTP effectué. */
  /* package-private : appelé par TicketmasterSource/TicketmasterPageFetcher
   * (même package). */
  void enregistrerAppel() {
    compteurBilan.enregistrerAppel();
  }

  /** Enregistre une erreur d'appel HTTP. */
  /* package-private : appelé par TicketmasterSource/TicketmasterPageFetcher
   * (même package). */
  void enregistrerErreur() {
    compteurBilan.enregistrerErreur();
  }

  /**
   * Retourne le bilan agrégé appels/erreurs HTTP depuis le dernier prélèvement
   * et remet les compteurs à zéro.
   *
   * @return le bilan depuis le dernier prélèvement
   */
  /* package-private : appelé par TicketmasterSource/TicketmasterPageFetcher
   * (même package). */
  BilanFetch preleverBilan() {
    return compteurBilan.preleverBilan();
  }
}

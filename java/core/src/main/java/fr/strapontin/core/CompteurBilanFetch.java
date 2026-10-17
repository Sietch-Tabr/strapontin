/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compteur mutable thread-safe d'appels/erreurs, prélevé périodiquement en
 * [BilanFetch]. Composable dans toute source de concerts ou tout canal de
 * notification sans imposer d'héritage commun.
 *
 * @since 2.7.0
 * @author Kwaite
 */
public final class CompteurBilanFetch {

  private final AtomicInteger appelsCumules = new AtomicInteger();
  private final AtomicInteger erreursCumulees = new AtomicInteger();
  private final AtomicInteger reponsesVidesCumulees = new AtomicInteger();
  private final Map<String, AtomicInteger> causesCumulees = new ConcurrentHashMap<>();

  /** Constructeur par défaut. */
  public CompteurBilanFetch() {}

  /** Enregistre une tentative d'appel (HTTP ou envoi). */
  public void enregistrerAppel() {
    appelsCumules.incrementAndGet();
  }

  /** Enregistre un appel en échec. */
  public void enregistrerErreur() {
    erreursCumulees.incrementAndGet();
  }

  /**
   * Enregistre un appel en échec en précisant sa cause.
   * @param cause cause lisible (ex. « connexion refusée »), comptée par type
   */
  public void enregistrerErreur(final String cause) {
    erreursCumulees.incrementAndGet();
    causesCumulees.computeIfAbsent(cause, _ -> new AtomicInteger()).incrementAndGet();
  }

  /** Enregistre un appel réussi dont la réponse ne contient aucun résultat. */
  public void enregistrerReponseVide() {
    reponsesVidesCumulees.incrementAndGet();
  }

  /**
   * Retourne le bilan cumulé depuis le dernier prélèvement et remet les
   * compteurs à zéro.
   *
   * @return le bilan depuis le dernier appel
   */
  public BilanFetch preleverBilan() {
    final Map<String, Integer> causes = new HashMap<>();
    causesCumulees.keySet().forEach(cause -> causes.put(cause, causesCumulees.remove(cause).get()));
    return new BilanFetch(
        appelsCumules.getAndSet(0),
        erreursCumulees.getAndSet(0),
        reponsesVidesCumulees.getAndSet(0),
        causes);
  }
}

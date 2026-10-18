/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Limiteur de débit par clé en fenêtre glissante : sur n'importe quelle
 * période de la durée de la fenêtre, une clé ne passe jamais plus de
 * {@code capacite} requêtes. Implémentation partagée par tous les points
 * d'entrée limités (connexion, signalements, erreurs client).
 * @since 3.0.6
 * @author Kwaite
 */
public final class LimiteurDebit {

  private final int capacite;
  private final long fenetreNanos;
  private final Clock horloge;
  // L'accès rafraîchit l'expiration : un journal n'est jamais évincé avant que
  // ses dates soient périmées.
  private final Cache<String, Journal> journaux;

  /** Dates des requêtes récentes d'une clé, protégées par leur propre verrou. */
  private static final class Journal {
    private final ReentrantLock verrou = new ReentrantLock();
    private final Deque<Long> dates = new ArrayDeque<>();
  }

  /**
   * @param capacite nombre de requêtes autorisées sur toute fenêtre glissante
   * @param fenetre durée de la fenêtre glissante
   * @param expiration durée de conservation d'une clé inactive (au moins la fenêtre)
   * @param tailleMax nombre maximal de clés suivies simultanément
   */
  public LimiteurDebit(
      final int capacite, final Duration fenetre, final Duration expiration, final long tailleMax) {
    this(capacite, fenetre, expiration, tailleMax, Clock.systemUTC());
  }

  /* package-private : horloge injectée par les tests (même package). */
  LimiteurDebit(
      final int capacite,
      final Duration fenetre,
      final Duration expiration,
      final long tailleMax,
      final Clock horloge) {
    this.capacite = capacite;
    this.fenetreNanos = Objects.requireNonNull(fenetre).toNanos();
    this.horloge = Objects.requireNonNull(horloge);
    this.journaux =
        Caffeine.newBuilder()
            .expireAfterAccess(Objects.requireNonNull(expiration))
            .maximumSize(tailleMax)
            .build();
  }

  /**
   * Enregistre une requête de la clé si la fenêtre glissante le permet ; une
   * requête refusée n'est pas comptée.
   * @param cle identifiant de l'appelant (adresse, utilisateur, chemin)
   * @return {@code true} si autorisée, {@code false} si le quota est épuisé
   */
  public boolean autoriser(final String cle) {
    final Journal journal = journaux.get(cle, _ -> new Journal());
    final long maintenant = maintenantNanos();
    journal.verrou.lock();
    try {
      purger(journal.dates, maintenant);
      if (journal.dates.size() >= capacite) {
        return false;
      }
      journal.dates.addLast(maintenant);
      return true;
    } finally {
      journal.verrou.unlock();
    }
  }

  /**
   * Indique si le quota de la clé est atteint, sans rien consommer ni créer
   * de suivi pour une clé inconnue.
   *
   * @param cle identifiant de l'appelant
   * @return {@code true} si une requête de plus serait refusée
   */
  public boolean estEpuise(final String cle) {
    final Journal journal = journaux.getIfPresent(cle);
    if (journal == null) {
      return false;
    }
    journal.verrou.lock();
    try {
      purger(journal.dates, maintenantNanos());
      return journal.dates.size() >= capacite;
    } finally {
      journal.verrou.unlock();
    }
  }

  /**
   * Compte un événement pour la clé sans le refuser (échec constaté après coup).
   *
   * @param cle identifiant de l'appelant
   * @return {@code true} si ce décompte vient d'atteindre le quota
   */
  public boolean enregistrer(final String cle) {
    final Journal journal = journaux.get(cle, _ -> new Journal());
    final long maintenant = maintenantNanos();
    journal.verrou.lock();
    try {
      purger(journal.dates, maintenant);
      journal.dates.addLast(maintenant);
      return journal.dates.size() == capacite;
    } finally {
      journal.verrou.unlock();
    }
  }

  /**
   * Efface le suivi d'une clé.
   *
   * @param cle identifiant de l'appelant
   */
  public void oublier(final String cle) {
    journaux.invalidate(cle);
  }

  private void purger(final Deque<Long> dates, final long maintenant) {
    while (!dates.isEmpty() && maintenant - dates.peekFirst() >= fenetreNanos) {
      dates.pollFirst();
    }
  }

  /** Remet tous les quotas à plein (tests de bout en bout en profil dev). */
  public void reinitialiser() {
    journaux.invalidateAll();
  }

  private long maintenantNanos() {
    final Instant instant = horloge.instant();
    return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
  }
}

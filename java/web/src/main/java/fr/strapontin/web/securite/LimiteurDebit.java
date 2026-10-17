/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

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
  // L'accès rafraîchit l'expiration : un journal n'est jamais évincé avant que ses dates soient
  // périmées.
  private final Cache<String, Deque<Long>> journaux;

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
   * Enregistre une requête de la clé si la fenêtre glissante le permet. Une
   * requête refusée n'est pas comptée.
   *
   * @param cle identifiant de l'appelant (adresse, utilisateur, couple adresse et chemin)
   * @return {@code true} si la requête est autorisée, {@code false} si le quota est épuisé
   */
  public boolean autoriser(final String cle) {
    final Deque<Long> journal = journaux.get(cle, _ -> new ArrayDeque<>());
    final long maintenant = maintenantNanos();
    synchronized (journal) {
      purger(journal, maintenant);
      if (journal.size() >= capacite) {
        return false;
      }
      journal.addLast(maintenant);
      return true;
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
    final Deque<Long> journal = journaux.getIfPresent(cle);
    if (journal == null) {
      return false;
    }
    synchronized (journal) {
      purger(journal, maintenantNanos());
      return journal.size() >= capacite;
    }
  }

  /**
   * Compte un événement pour la clé sans le refuser (échec constaté après coup).
   *
   * @param cle identifiant de l'appelant
   * @return {@code true} si ce décompte vient d'atteindre le quota
   */
  public boolean enregistrer(final String cle) {
    final Deque<Long> journal = journaux.get(cle, _ -> new ArrayDeque<>());
    final long maintenant = maintenantNanos();
    synchronized (journal) {
      purger(journal, maintenant);
      journal.addLast(maintenant);
      return journal.size() == capacite;
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

  private void purger(final Deque<Long> journal, final long maintenant) {
    while (!journal.isEmpty() && maintenant - journal.peekFirst() >= fenetreNanos) {
      journal.pollFirst();
    }
  }

  /** Remet tous les quotas à plein (tests de bout en bout en profil dev). */
  public void reinitialiser() {
    journaux.invalidateAll();
  }

  private long maintenantNanos() {
    final var instant = horloge.instant();
    return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
  }
}

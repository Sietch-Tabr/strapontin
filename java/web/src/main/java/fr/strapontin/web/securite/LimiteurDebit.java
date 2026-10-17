/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Objects;

/**
 * Limiteur de débit par clé : chaque clé dispose d'un quota rechargé en
 * bloc à la fin de la fenêtre. Implémentation partagée par tous les points
 * d'entrée limités (connexion, signalements, erreurs client).
 * @since 3.0.6
 * @author Kwaite
 */
public final class LimiteurDebit {

  private final int capacite;
  private final Duration fenetre;
  private final Cache<String, Bucket> buckets;

  /**
   * @param capacite nombre de requêtes autorisées par fenêtre
   * @param fenetre durée au bout de laquelle le quota est rechargé
   * @param expiration durée de conservation d'une clé inactive (au moins la fenêtre)
   * @param tailleMax nombre maximal de clés suivies simultanément
   */
  public LimiteurDebit(
      final int capacite, final Duration fenetre, final Duration expiration, final long tailleMax) {
    this.capacite = capacite;
    this.fenetre = Objects.requireNonNull(fenetre);
    this.buckets =
        Caffeine.newBuilder()
            .expireAfterWrite(Objects.requireNonNull(expiration))
            .maximumSize(tailleMax)
            .build();
  }

  /**
   * Consomme une unité du quota de la clé.
   *
   * @param cle identifiant de l'appelant (adresse, utilisateur, couple adresse et chemin)
   * @return {@code true} si la requête est autorisée, {@code false} si le quota est épuisé
   */
  public boolean autoriser(final String cle) {
    return buckets.get(cle, _ -> creerBucket()).tryConsume(1);
  }

  /** Remet tous les quotas à plein (tests de bout en bout en profil dev). */
  public void reinitialiser() {
    buckets.invalidateAll();
  }

  private Bucket creerBucket() {
    final Bandwidth limite =
        Bandwidth.builder().capacity(capacite).refillIntervally(capacite, fenetre).build();
    return Bucket.builder().addLimit(limite).build();
  }
}

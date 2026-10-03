/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.BilanFetch;
import fr.strapontin.core.CompteurBilanFetch;
import fr.strapontin.core.QuotaTM;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.client.HttpClientErrorException;

/**
 * État mutable partagé entre {@link TicketmasterSource} et {@link
 * TicketmasterPageFetcher} : quota TM, concerts manqués (pagination),
 * bilan d'appels/erreurs — regroupe trois compteurs en un type injecté.
 * @since 2.9.1
 * @author Kwaite
 */
final class EtatFetchTM {

  private static final int HTTP_TROP_DE_REQUETES = 429;

  private final AtomicReference<QuotaTM> dernierQuota = new AtomicReference<>();
  private final AtomicBoolean refusPourQuota = new AtomicBoolean();
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
      if (quota.dispo() > 0) {
        refusPourQuota.set(false);
      }
    }
  }

  /**
   * @param erreur erreur d'un appel Ticketmaster
   * @return {@code true} si Ticketmaster refuse l'appel parce que le quota du
   *     jour est épuisé (429 {@code QuotaViolation}, distinct du Spike Arrest)
   */
  /* package-private : appelé par TicketmasterPageFetcher/TicketmasterHealthCheck. */
  static boolean estQuotaEpuise(final Exception erreur) {
    return erreur instanceof HttpClientErrorException http
        && http.getStatusCode().value() == HTTP_TROP_DE_REQUETES
        && http.getResponseBodyAsString().contains("QuotaViolation");
  }

  /**
   * Quota du jour épuisé : disponible ramené à zéro, ce que les jobs lisent
   * avant d'appeler Ticketmaster (dernier total et remise à zéro conservés).
   */
  /* package-private : appelé par TicketmasterPageFetcher/TicketmasterHealthCheck. */
  void signalerQuotaEpuise() {
    refusPourQuota.set(true);
    final QuotaTM precedent = dernierQuota.get();
    dernierQuota.set(
        new QuotaTM(
            0,
            precedent != null ? precedent.total() : 0,
            precedent != null ? precedent.reset() : 0,
            Instant.now().toString()));
  }

  /**
   * @return {@code true} si Ticketmaster a signalé le quota du jour épuisé
   *     depuis la dernière réponse portant un quota disponible
   */
  /* package-private : appelé par TicketmasterSource (même package). */
  boolean quotaEpuise() {
    return refusPourQuota.get();
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

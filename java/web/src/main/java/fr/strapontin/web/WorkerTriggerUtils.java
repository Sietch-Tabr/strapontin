/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import org.slf4j.Logger;
import org.springframework.web.client.RestClient;

/**
 * Déclenchement asynchrone (fire-and-forget) d'un endpoint du worker — motif
 * jusqu'ici dupliqué verbatim dans {@code ArtisteService}/{@code
 * SalleService}/{@code PreferencesService}. Un thread virtuel dédié évite de
 * bloquer l'appelant sans gérer le cycle de vie d'un executor.
 * @since 2.8.7
 * @author Kwaite
 */
public final class WorkerTriggerUtils {

  private WorkerTriggerUtils() {}

  /**
   * Déclenche un POST vers le worker (thread virtuel), sans propager d'erreur.
   * @param restClient client HTTP partagé
   * @param log logger de l'appelant, pour journaliser un échec
   * @param gabaritUrl URL du endpoint, variables en {@code {nom}}
   * @param messageEchec message de log en cas d'échec
   * @param variables valeurs du gabarit, encodées une seule fois
   */
  public static void declencherWorkerAsync(
      final RestClient restClient,
      final Logger log,
      final String gabaritUrl,
      final String messageEchec,
      final Object... variables) {
    Thread.startVirtualThread(
        () -> {
          try {
            restClient.post().uri(gabaritUrl, variables).retrieve().toBodilessEntity();
          } catch (Exception e) {
            log.warn("{} : {}", messageEchec, e.getMessage());
          }
        });
  }

  /**
   * Réponse HTTP brute (statut + corps), utilisée pour reproduire avec
   * {@link RestClient} le comportement de l'ancien
   * {@code java.net.http.HttpClient} : lire le statut sans lever d'exception
   * sur les codes non-2xx (contrairement à {@code retrieve()} par défaut).
   */
  public record ReponseHttp(int statut, String corps) {}
}

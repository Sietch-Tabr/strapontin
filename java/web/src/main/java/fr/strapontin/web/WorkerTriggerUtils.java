/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import org.slf4j.Logger;
import org.springframework.web.client.RestClient;

/// Déclenchement asynchrone (fire-and-forget) d'un endpoint du worker — motif jusqu'ici dupliqué
/// verbatim dans {@code ArtisteService}, {@code SalleService} et {@code PreferencesService}.
///
/// <p>Un thread virtuel dédié évite de bloquer l'appelant sans avoir à gérer le cycle de vie d'un
/// executor pour un appel ponctuel.
///
/// @since 2.8.7
/// @author Kwaite
public final class WorkerTriggerUtils {

  private WorkerTriggerUtils() {}

  /// Déclenche un appel POST vers le worker sur un thread virtuel, sans attendre ni propager
  /// d'exception à l'appelant.
  ///
  /// @param restClient client HTTP partagé
  /// @param log logger de l'appelant, pour journaliser un échec
  /// @param url URL complète du endpoint worker à appeler
  /// @param messageEchec message de log en cas d'échec (l'exception y est jointe)
  public static void declencherWorkerAsync(
      RestClient restClient, Logger log, String url, String messageEchec) {
    Thread.startVirtualThread(
        () -> {
          try {
            restClient.post().uri(url).retrieve().toBodilessEntity();
          } catch (Exception e) {
            log.warn(messageEchec, e);
          }
        });
  }

  /// Réponse HTTP brute (statut + corps), utilisée pour reproduire avec {@link RestClient} le
  /// comportement de l'ancien {@code java.net.http.HttpClient} : lire le statut sans lever
  /// d'exception sur les codes non-2xx (contrairement à {@code retrieve()} par défaut).
  public record ReponseHttp(int statut, String corps) {}
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Map;
import java.util.Optional;

/**
 * Bilan agrégé d'appels (HTTP ou envois) et d'échecs pour une source ou un canal de notification
 * depuis le dernier prélèvement, avec logique d'escalade partagée vers {@code logs_admin} — remplace les
 * records de bilan précédemment dupliqués entre {@code TicketmasterSource} et {@code EventimSource}.
 *
 * @param appels nombre total d'appels tentés depuis le dernier prélèvement
 * @param erreursHttp nombre d'appels en échec depuis le dernier prélèvement
 * @since 2.7.0
 * @author Kwaite
 */
public record BilanFetch(int appels, int erreursHttp) {

  /**
   * Nombre minimal d'appels sur un cycle pour qu'un taux d'échec de 100% déclenche une escalade en
   * niveau {@code error} plutôt qu'un simple {@code warn} — évite une fausse alerte sur un cycle
   * mono-appel victime d'un aléa réseau isolé.
   */
  public static final int SEUIL_APPELS_MIN_ALERTE_DEFAUT = 3;

  /**
   * Entrée à insérer dans {@code logs_admin} pour ce bilan.
   *
   * @param niveau {@code "warn"} ou {@code "error"}
   * @param message message lisible
   * @param meta métadonnées structurées
   */
  public record EntreeLog(String niveau, String message, Map<String, Object> meta) {}

  /**
   * Indique si ce bilan représente une panne totale : 100% d'échec sur au moins {@code
   * seuilAppelsMin} appels.
   *
   * @param seuilAppelsMin nombre minimal d'appels pour déclencher l'escalade
   * @return {@code true} si panne totale
   */
  public boolean panneTotale(final int seuilAppelsMin) {
    return appels >= seuilAppelsMin && erreursHttp == appels;
  }

  /**
   * Évalue ce bilan et retourne l'entrée à journaliser dans {@code logs_admin}, si quelque chose est à
   * signaler (panne totale ou échec partiel). Vide si aucun appel n'a été tenté ou si tous ont
   * réussi.
   *
   * @param typeJob type de job ou de cycle courant (ex. {@code "fetch:region"})
   * @param nomSource nom affiché de la source ou du canal (ex. {@code "Digitick"})
   * @param seuilAppelsMin nombre minimal d'appels pour déclencher l'escalade en {@code error}
   * @return entrée à journaliser, vide si rien à signaler
   */
  public Optional<EntreeLog> evaluer(
      final String typeJob, final String nomSource, final int seuilAppelsMin) {
    if (appels == 0) {
      return Optional.empty();
    }
    final boolean panneTotale = panneTotale(seuilAppelsMin);
    if (!panneTotale && erreursHttp == 0) {
      return Optional.empty();
    }

    final String niveau = panneTotale ? "error" : "warn";
    final String message =
        typeJob
            + " — "
            + nomSource
            + " "
            + (panneTotale ? "intégralement en échec" : "en échec partiel")
            + " sur ce cycle ("
            + erreursHttp
            + "/"
            + appels
            + " appels HTTP)";
    final Map<String, Object> meta = Map.of("appels", appels, "erreursHttp", erreursHttp);
    return Optional.of(new EntreeLog(niveau, message, meta));
  }
}

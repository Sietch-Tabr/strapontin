/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Map;
import java.util.Optional;

/**
 * Bilan agrégé d'appels et d'échecs depuis le dernier prélèvement.
 * @param appels appels tentés
 * @param erreursHttp appels en échec
 * @param reponsesVides appels réussis sans résultat
 * @param causes nombre d'échecs par cause lisible, vide si elles ne sont pas attribuées
 * @since 2.7.0
 * @author Kwaite
 */
public record BilanFetch(
    int appels, int erreursHttp, int reponsesVides, Map<String, Integer> causes) {

  /**
   * Nombre minimal d'appels sur un cycle pour qu'un taux d'échec de 100%
   * déclenche une escalade en niveau {@code error} plutôt qu'un simple
   * {@code warn} — évite une fausse alerte sur un cycle mono-appel victime d'un
   * aléa réseau isolé.
   */
  public static final int SEUIL_APPELS_MIN_ALERTE_DEFAUT = 3;

  /**
   * Nombre minimal d'appels pour qu'une source qui ne répond que des
   * réponses vides soit signalée : sur un petit lot, aucun résultat reste
   * plausible quand les lieux cherchés sont peu connus.
   */
  public static final int SEUIL_APPELS_MIN_REPONSES_VIDES = 20;

  /** Copie défensive des causes. */
  public BilanFetch {
    causes = causes == null ? Map.of() : Map.copyOf(causes);
  }

  /**
   * Bilan sans cause attribuée aux échecs.
   *
   * @param appels nombre total d'appels tentés
   * @param erreursHttp nombre d'appels en échec
   * @param reponsesVides appels réussis sans résultat
   */
  public BilanFetch(final int appels, final int erreursHttp, final int reponsesVides) {
    this(appels, erreursHttp, reponsesVides, Map.of());
  }

  /**
   * Bilan sans réponse vide comptée.
   *
   * @param appels nombre total d'appels tentés
   * @param erreursHttp nombre d'appels en échec
   */
  public BilanFetch(final int appels, final int erreursHttp) {
    this(appels, erreursHttp, 0);
  }

  /**
   * Indique si la source a répondu sans erreur mais sans aucun résultat sur
   * tous ses appels, en nombre suffisant pour que ce ne soit pas un hasard.
   *
   * @return {@code true} si toutes les réponses sont vides
   */
  public boolean repondVide() {
    return appels >= SEUIL_APPELS_MIN_REPONSES_VIDES && erreursHttp == 0 && reponsesVides == appels;
  }

  /**
   * Entrée à insérer dans {@code logs_admin} pour ce bilan.
   *
   * @param niveau {@code "warn"} ou {@code "error"}
   * @param message message lisible
   * @param meta métadonnées structurées
   */
  public record EntreeLog(String niveau, String message, Map<String, Object> meta) {}

  /**
   * Indique si ce bilan représente une panne totale : 100% d'échec sur au moins
   * {@code seuilAppelsMin} appels.
   *
   * @param seuilAppelsMin nombre minimal d'appels pour déclencher l'escalade
   * @return {@code true} si panne totale
   */
  public boolean panneTotale(final int seuilAppelsMin) {
    return appels >= seuilAppelsMin && erreursHttp == appels;
  }

  /**
   * Évalue ce bilan, entrée à journaliser si signalable (détail :
   * CLAUDE.md, Services notables).
   * @param typeJob type de job ou de cycle courant
   * @param nomSource nom affiché de la source ou du canal
   * @param seuilAppelsMin appels min. pour escalader en error
   * @return entrée à journaliser, vide si rien à signaler
   */
  public Optional<EntreeLog> evaluer(
      final String typeJob, final String nomSource, final int seuilAppelsMin) {
    if (appels == 0) {
      return Optional.empty();
    }
    final boolean panneTotale = panneTotale(seuilAppelsMin);
    if (!panneTotale && erreursHttp == 0) {
      return repondVide()
          ? Optional.of(
              new EntreeLog(
                  "warn",
                  typeJob
                      + " — "
                      + nomSource
                      + " répond sans aucun résultat sur ce cycle ("
                      + reponsesVides
                      + "/"
                      + appels
                      + " réponses vides)",
                  Map.of("appels", appels, "reponsesVides", reponsesVides)))
          : Optional.empty();
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
            + " appels HTTP)"
            + decrireCauses();
    final Map<String, Object> meta =
        causes.isEmpty()
            ? Map.of("appels", appels, "erreursHttp", erreursHttp)
            : Map.of("appels", appels, "erreursHttp", erreursHttp, "causes", causes);
    return Optional.of(new EntreeLog(niveau, message, meta));
  }

  // Causes triées de la plus fréquente à la plus rare : « connexion refusée × 9, délai dépassé × 3 ».
  private String decrireCauses() {
    if (causes.isEmpty()) {
      return "";
    }
    return " : "
        + causes.entrySet().stream()
            .sorted(
                Map.Entry.<String, Integer>comparingByValue()
                    .reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
            .map(e -> e.getKey() + " × " + e.getValue())
            .collect(java.util.stream.Collectors.joining(", "));
  }
}

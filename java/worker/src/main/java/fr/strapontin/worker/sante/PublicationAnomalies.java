/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Manière unique dont un contrôle d'invariants publie ses anomalies dans {@code
 * signalements_matching} : chaque anomalie est signalée, puis les signalements du type dont
 * l'entité n'est plus en anomalie sont clos.
 * @since 3.1.4
 * @author Kwaite
 */
final class PublicationAnomalies {

  private static final String CONFIANCE_HAUTE = "haute";

  private final SignalementMatchingService signalements;

  /* package-private : instancié par les contrôles d'invariants (même package). */
  PublicationAnomalies(final SignalementMatchingService signalements) {
    this.signalements = signalements;
  }

  /**
   * @param type type de signalement
   * @param entiteType nature de l'entité signalée
   * @param anomalies entités en anomalie et leurs détails
   * @return nombre d'anomalies publiées
   */
  /* package-private : appelé par les contrôles d'invariants (même package). */
  int publier(
      final String type, final String entiteType, final Map<UUID, Map<String, Object>> anomalies) {
    anomalies.forEach(
        (entite, details) ->
            signalements.signaler(type, entiteType, entite, CONFIANCE_HAUTE, details));
    signalements.clore(type, anomalies.keySet());
    return anomalies.size();
  }

  /**
   * @param valeur texte dont l'identifiant est tiré
   * @return identifiant déterministe, pour une anomalie qui n'a pas d'entité propre
   */
  /* package-private : appelé par les contrôles d'invariants (même package). */
  static UUID uuidDe(final String valeur) {
    return UUID.nameUUIDFromBytes(valeur.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * @param type type de signalement
   * @param details détails de l'anomalie, vides s'il n'y en a pas
   * @return l'unique anomalie du type, ou aucune quand les détails sont vides
   */
  /* package-private : appelé par les contrôles d'invariants (même package). */
  static Map<UUID, Map<String, Object>> globale(
      final String type, final Map<String, Object> details) {
    return details.isEmpty() ? Map.of() : Map.of(uuidDe("invariant:" + type), details);
  }
}

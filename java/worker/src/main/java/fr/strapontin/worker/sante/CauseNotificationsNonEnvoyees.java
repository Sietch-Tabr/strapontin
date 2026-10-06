/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cause lisible d'un signalement « notifications non envoyées » : un compte
 * sans aucun canal joignable ne relève pas de la même action qu'un envoi en
 * échec.
 * @since 3.0.3
 * @author Kwaite
 */
final class CauseNotificationsNonEnvoyees {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private CauseNotificationsNonEnvoyees() {}

  /**
   * @param emailActif email des artistes ou des salles coché
   * @param pushCoche push des artistes ou des salles coché
   * @param appareilAbonne au moins un appareil push abonné
   * @param appareilRetireLe date du dernier retrait automatique d'appareil, ou null
   * @param averti date de l'avertissement envoyé à l'utilisateur, ou null
   * @return détails {@code cause} et {@code action} du signalement
   */
  static Map<String, Object> decrire(
      final boolean emailActif,
      final boolean pushCoche,
      final boolean appareilAbonne,
      final LocalDate appareilRetireLe,
      final LocalDate averti) {
    final Map<String, Object> details = new LinkedHashMap<>();
    if (emailActif || pushCoche && appareilAbonne) {
      details.put("cause", "Envoi en échec alors qu'un canal est joignable.");
      details.put("action", "Vérifier dans les journaux l'envoi des emails et des push.");
      return details;
    }
    final StringBuilder cause = new StringBuilder("Aucun canal joignable : email désactivé");
    if (pushCoche) {
      cause.append(", aucun appareil abonné au push");
      if (appareilRetireLe != null) {
        cause
            .append(" (dernier appareil retiré automatiquement le ")
            .append(DATE.format(appareilRetireLe))
            .append(')');
      }
    }
    details.put("cause", cause.append('.').toString());
    details.put(
        "action",
        averti != null
            ? "Utilisateur prévenu par email le "
                + DATE.format(averti)
                + " ; seul lui peut réactiver ses notifications."
            : "Seul l'utilisateur peut réactiver ses notifications (Paramètres → Notifications).");
    return details;
  }
}

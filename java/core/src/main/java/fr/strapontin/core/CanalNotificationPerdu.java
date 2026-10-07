/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/**
 * Compte dont les notifications actives ne peuvent plus parvenir, après le
 * retrait automatique de son dernier appareil push : partagé par
 * l'avertissement par email (worker) et le bandeau de l'application (web).
 * @since 3.0.3
 * @author Kwaite
 */
public final class CanalNotificationPerdu {

  /** Condition SQL sur {@code user_config} aliasée {@code uc}. */
  public static final String CONDITION_SQL =
      """
      uc.frequence_notifications <> 'jamais'
        AND (uc.notif_artistes_push OR uc.notif_salles_push)
        AND NOT (uc.notif_artistes OR uc.notif_salles)
        AND uc.dernier_appareil_retire_le IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM push_abonnements pa WHERE pa.user_id = uc.user_id)
      """;

  private CanalNotificationPerdu() {}
}

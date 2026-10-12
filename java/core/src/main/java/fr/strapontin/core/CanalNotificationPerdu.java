/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/**
 * Compte dont les notifications actives ne peuvent pas parvenir : push seul
 * coché sans appareil abonné, après un retrait ou faute d'abonnement jamais
 * enregistré. Partagé par l'avertissement email et le bandeau web.
 * @since 3.0.3
 * @author Kwaite
 */
public final class CanalNotificationPerdu {

  private static final String SANS_CANAL_SQL =
      """
      uc.frequence_notifications <> 'jamais'
        AND (uc.notif_artistes_push OR uc.notif_salles_push)
        AND NOT (uc.notif_artistes OR uc.notif_salles)
        AND NOT EXISTS (SELECT 1 FROM push_abonnements pa WHERE pa.user_id = uc.user_id)
      """;

  /** Dernier appareil retiré automatiquement ; condition sur {@code user_config uc}. */
  public static final String CONDITION_SQL =
      SANS_CANAL_SQL + " AND uc.dernier_appareil_retire_le IS NOT NULL";

  /** Aucun appareil jamais enregistré ; condition sur {@code user_config uc}. */
  public static final String CONDITION_JAMAIS_ABONNE_SQL =
      SANS_CANAL_SQL + " AND uc.dernier_appareil_retire_le IS NULL";

  /** Aucun canal joignable, quelle qu'en soit la raison ; condition sur {@code uc}. */
  public static final String CONDITION_SANS_CANAL_SQL = SANS_CANAL_SQL;

  private CanalNotificationPerdu() {}
}

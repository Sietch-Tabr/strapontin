/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/**
 * Compte dont le push ne peut plus parvenir : son dernier appareil a été retiré (révocation
 * automatique, retrait manuel suivi d'une rotation des clés) ou son push n'a jamais eu d'appareil
 * alors qu'il était seul coché. Partagé par l'avertissement email et le bandeau web. Le retrait
 * décoche les cases push, donc la condition du retrait ne dépend pas de l'état des cases ni des
 * autres canaux.
 * @since 3.0.3
 * @author Kwaite
 */
public final class CanalNotificationPerdu {

  private static final String SANS_APPAREIL_SQL =
      "NOT EXISTS (SELECT 1 FROM push_abonnements pa WHERE pa.user_id = uc.user_id)";

  /** Dernier appareil retiré automatiquement ; condition sur {@code user_config uc}. */
  public static final String CONDITION_SQL =
      "uc.dernier_appareil_retire_le IS NOT NULL AND " + SANS_APPAREIL_SQL;

  /** Push seul coché et aucun appareil jamais enregistré ; condition sur {@code uc}. */
  public static final String CONDITION_JAMAIS_ABONNE_SQL =
      "uc.frequence_notifications <> 'jamais'"
          + " AND (uc.notif_artistes_push OR uc.notif_salles_push)"
          + " AND NOT (uc.notif_artistes OR uc.notif_salles)"
          + " AND uc.dernier_appareil_retire_le IS NULL AND "
          + SANS_APPAREIL_SQL;

  private CanalNotificationPerdu() {}
}

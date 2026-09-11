/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Détecte un repli de volume significatif d'un cycle de fetch réussi par rapport à son historique
 * récent — {@code fetch_jobs}/{@code fetch_jobs_historique} n'exposent jusqu'ici que le dernier
 * résultat et un historique brut, sans aucune comparaison automatique capable de distinguer une
 * baisse normale (creux saisonnier) d'une dégradation silencieuse (ex. filtre amont trop
 * restrictif) qui ne produit ni exception ni bilan de panne.
 *
 * @since 2.9.0
 * @author Kwaite
 */
@Service
public class DeriveVolumeFetchService {

  private static final Logger log = LoggerFactory.getLogger(DeriveVolumeFetchService.class);

  /** Nombre d'exécutions réussies récentes sur lesquelles porte la moyenne glissante. */
  static final int NB_EXECUTIONS_MOYENNE = 5;

  /**
   * Écart relatif à partir duquel un cycle est considéré en repli significatif — 30% absorbe la
   * variance normale (concerts qui expirent, creux saisonniers) sans masquer une dégradation
   * partielle silencieuse.
   */
  static final double SEUIL_DERIVE = 0.30;

  /**
   * Sous cette moyenne, l'écart relatif n'est plus discriminant (ex. 1 -> 0 concert est un repli de
   * 100% sans rien d'anormal) — aucune comparaison n'est faite en dessous de ce volume moyen.
   */
  static final int VOLUME_MOYEN_MIN_SIGNIFICATIF = 5;

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;

  /**
   * @param jdbc accès JDBC
   * @param logsAdminWorker journalisation d'un repli de volume détecté
   */
  public DeriveVolumeFetchService(
      final NamedParameterJdbcTemplate jdbc, final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Compare le volume (insertions + mises à jour) du cycle qui vient de réussir à la moyenne de ses
   * {@value #NB_EXECUTIONS_MOYENNE} dernières exécutions réussies antérieures à {@code avant}.
   *
   * @param typeJob type de job (ex. {@code "fetch:region"})
   * @param volumeCourant nombre total d'insertions + mises à jour du cycle qui vient de réussir
   * @param avant horodatage du cycle courant — exclut structurellement sa propre ligne d'historique
   *     de la moyenne, que celle-ci ait déjà été insérée ou non au moment de l'appel
   */
  public void verifier(final String typeJob, final int volumeCourant, final Timestamp avant) {
    final List<Integer> historique = lireVolumesRecents(typeJob, avant);
    if (historique.size() < NB_EXECUTIONS_MOYENNE) {
      return;
    }

    final double moyenne = historique.stream().mapToInt(Integer::intValue).average().orElse(0);
    if (moyenne < VOLUME_MOYEN_MIN_SIGNIFICATIF) {
      return;
    }
    if (volumeCourant >= moyenne * (1 - SEUIL_DERIVE)) {
      return;
    }

    log.warn(
        "derive:volume typeJob={} volume={} moyenne{}={}",
        typeJob,
        volumeCourant,
        NB_EXECUTIONS_MOYENNE,
        moyenne);
    logsAdminWorker.inserer(
        "warn",
        "derive-volume",
        "volume du cycle significativement inférieur à la moyenne récente typeJob="
            + typeJob
            + " volume="
            + volumeCourant
            + " moyenne"
            + NB_EXECUTIONS_MOYENNE
            + "="
            + moyenne,
        Map.of("typeJob", typeJob, "volume", volumeCourant, "moyenneRecente", moyenne));
  }

  private List<Integer> lireVolumesRecents(final String typeJob, final Timestamp avant) {
    return jdbc.query(
        """
        SELECT nb_inseres + nb_mis_a_jour AS volume
        FROM fetch_jobs_historique
        WHERE type_job = :typeJob AND erreur IS NULL AND exec_le < :avant
        ORDER BY exec_le DESC
        LIMIT :limite
        """,
        Map.of("typeJob", typeJob, "avant", avant, "limite", NB_EXECUTIONS_MOYENNE),
        (rs, _) -> rs.getInt("volume"));
  }
}

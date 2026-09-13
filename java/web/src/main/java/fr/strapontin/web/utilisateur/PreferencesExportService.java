/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.core.Normalisation;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Export des préférences utilisateur (artistes, salles, config) — extrait de {@link
 * PreferencesService} pour rester sous le seuil de complexité de classe (GodClass).
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class PreferencesExportService {

  private static final String CLE_USER_ID = "userId";

  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : instancié uniquement par PreferencesService (même package). */
  PreferencesExportService(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Exporte les préférences de l'utilisateur (artistes, salles, config).
   *
   * @param userId identifiant de l'utilisateur
   * @return l'export complet
   */
  /* package-private : appelé par PreferencesService (même package). */
  ExportComplet exporter(final String userId) {
    final List<ArtisteExport> artistes = exporterArtistes(userId);
    final List<SalleExport> salles = exporterSalles(userId);
    final List<ConfigExport> configRows = exporterConfig(userId);

    return new ExportComplet(
        1,
        Instant.now().toString(),
        artistes,
        salles,
        configRows.isEmpty() ? null : configRows.get(0));
  }

  private List<ArtisteExport> exporterArtistes(final String userId) {
    return jdbc.query(
        """
        SELECT artiste_affiche FROM user_artistes
        WHERE user_id = :userId ORDER BY artiste_affiche
        """,
        Map.of(CLE_USER_ID, userId),
        (rs, _) -> {
          final String affiche = rs.getString("artiste_affiche");
          return new ArtisteExport(affiche, Normalisation.normaliserTexte(affiche));
        });
  }

  private List<SalleExport> exporterSalles(final String userId) {
    return jdbc.query(
        """
        SELECT s.salle_cle AS salle_id, s.nom
        FROM user_salles us
        JOIN salles s ON s.id = us.salle_id
        WHERE us.user_id = :userId ORDER BY s.salle_cle
        """,
        Map.of(CLE_USER_ID, userId),
        (rs, _) -> new SalleExport(rs.getString("salle_id"), null, rs.getString("nom")));
  }

  private List<ConfigExport> exporterConfig(final String userId) {
    return jdbc.query(
        """
        SELECT departements, filtre_disponibles, frequence_notifications,
               notif_artistes, notif_salles, notif_artistes_push, notif_salles_push, theme
        FROM user_config WHERE user_id = :userId
        """,
        Map.of(CLE_USER_ID, userId),
        (rs, _) -> {
          final String[] depts =
              rs.getArray("departements") != null
                  ? (String[]) rs.getArray("departements").getArray()
                  : new String[0];
          return new ConfigExport(
              Arrays.asList(depts),
              rs.getBoolean("filtre_disponibles"),
              rs.getString("frequence_notifications"),
              rs.getBoolean("notif_artistes"),
              rs.getBoolean("notif_salles"),
              rs.getBoolean("notif_artistes_push"),
              rs.getBoolean("notif_salles_push"),
              rs.getString("theme"));
        });
  }
}

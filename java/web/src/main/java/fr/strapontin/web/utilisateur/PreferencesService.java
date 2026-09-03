/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.FiltreArtiste;
import fr.strapontin.core.Normalisation;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.artiste.ArtisteService;
import fr.strapontin.web.salle.SalleService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/// Service d'import/export des préférences utilisateur.
///
/// <p>L'import est atomique ({@code @Transactional}) — un échec partiel annule toute l'opération.
///
/// @since 2.1.0
/// @author Kwaite
@Service
public class PreferencesService {

  private static final Logger log = LoggerFactory.getLogger(PreferencesService.class);
  private static final String CLE_USER_ID = "userId";
  private static final String WORKER_URL = "http://localhost:8081/trigger";

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;
  private final LogAdminService logAdminService;
  private final ArtisteService artisteService;
  private final SalleService salleService;

  /// @param jdbc accès JDBC
  /// @param objectMapper désérialiseur JSON
  /// @param restClient client HTTP partagé (module web), pour déclencher le worker
  /// @param logAdminService journalisation des actions admin
  /// @param artisteService résolution d'UUID artiste avec dédup floue
  /// @param salleService résolution d'UUID salle avec dédup floue
  public PreferencesService(
      NamedParameterJdbcTemplate jdbc,
      ObjectMapper objectMapper,
      RestClient restClient,
      LogAdminService logAdminService,
      ArtisteService artisteService,
      SalleService salleService) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.restClient = restClient;
    this.logAdminService = logAdminService;
    this.artisteService = artisteService;
    this.salleService = salleService;
  }

  /// Désérialise et valide un fichier d'export.
  ///
  /// @param contenu octets du fichier JSON
  /// @return l'export désérialisé, ou {@code null} si invalide
  public ExportComplet deserialiserExport(byte[] contenu) {
    try {
      return objectMapper.readValue(contenu, ExportComplet.class);
    } catch (Exception e) {
      log.warn("Import préférences : désérialisation échouée", e);
      return null;
    }
  }

  /// Importe les préférences dans une transaction atomique.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param export export désérialisé
  /// @param ecraser {@code true} pour remplacer artistes et salles existants
  /// @param importerConfig {@code true} pour remplacer toute la config
  /// @return résultat de l'import (artistes et salles traités/rejetés)
  @Transactional
  public ResultatImportComplet importer(
      String userId, ExportComplet export, boolean ecraser, boolean importerConfig) {
    int nbSupprimesArtistes = 0;
    int nbSupprimesSalles = 0;
    if (ecraser) {
      nbSupprimesArtistes =
          jdbc.update(
              "DELETE FROM user_artistes WHERE user_id = :userId", Map.of(CLE_USER_ID, userId));
      nbSupprimesSalles =
          jdbc.update(
              "DELETE FROM user_salles WHERE user_id = :userId", Map.of(CLE_USER_ID, userId));
      log.warn(
          "import préférences mode écraser : {} artiste(s) et {} salle(s) supprimés userId={}",
          nbSupprimesArtistes,
          nbSupprimesSalles,
          userId);
    }

    int[] artistes = {0, 0};
    int[] salles = {0, 0};

    if (export.artistes() != null) {
      int[] res = importerArtistes(userId, export.artistes());
      artistes = res;
    }
    if (export.salles() != null) {
      int[] res = importerSalles(userId, export.salles());
      salles = res;
    }

    if (importerConfig && export.config() != null) {
      importerConfig(userId, export.config());
    } else if (!ecraser && export.config() != null && export.config().departements() != null) {
      fusionnerDepartements(userId, export.config().departements().toArray(String[]::new));
    }

    declencherWorkerApresImport(export.salles());
    ResultatImportComplet resultat =
        new ResultatImportComplet(artistes[0], salles[0], artistes[1] + salles[1]);
    log.info(
        "import préférences userId={} artistes={} salles={} rejetes={} mode={}",
        userId,
        resultat.artistes(),
        resultat.salles(),
        resultat.rejetes(),
        ecraser ? "ecraser" : "fusionner");
    String niveauLog = ecraser ? "warn" : "info";
    Map<String, Object> metaImport = new java.util.LinkedHashMap<>();
    metaImport.put("userId", userId);
    metaImport.put("artistes", resultat.artistes());
    metaImport.put("salles", resultat.salles());
    metaImport.put("rejetes", resultat.rejetes());
    metaImport.put("mode", ecraser ? "ecraser" : "fusionner");
    if (ecraser) {
      metaImport.put("supprimesArtistes", nbSupprimesArtistes);
      metaImport.put("supprimesSalles", nbSupprimesSalles);
    }
    logAdminService.logAdmin(niveauLog, "web", "import préférences", metaImport);
    return resultat;
  }

  /// Exporte les préférences de l'utilisateur (artistes, salles, config).
  ///
  /// @param userId identifiant de l'utilisateur
  /// @return l'export complet
  public ExportComplet exporter(String userId) {
    List<ArtisteExport> artistes =
        jdbc.query(
            """
            SELECT artiste_affiche FROM user_artistes
            WHERE user_id = :userId ORDER BY artiste_affiche
            """,
            Map.of(CLE_USER_ID, userId),
            (rs, _) -> {
              String affiche = rs.getString("artiste_affiche");
              return new ArtisteExport(affiche, Normalisation.normaliserTexte(affiche));
            });

    List<SalleExport> salles =
        jdbc.query(
            """
            SELECT s.salle_cle AS salle_id, s.nom
            FROM user_salles us
            JOIN salles s ON s.id = us.salle_id
            WHERE us.user_id = :userId ORDER BY s.salle_cle
            """,
            Map.of(CLE_USER_ID, userId),
            (rs, _) -> new SalleExport(rs.getString("salle_id"), null, rs.getString("nom")));

    List<ConfigExport> configRows =
        jdbc.query(
            """
            SELECT departements, filtre_disponibles, frequence_notifications,
                   notif_artistes, notif_salles, notif_artistes_push, notif_salles_push, theme
            FROM user_config WHERE user_id = :userId
            """,
            Map.of(CLE_USER_ID, userId),
            (rs, _) -> {
              String[] depts =
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

    return new ExportComplet(
        1,
        Instant.now().toString(),
        artistes,
        salles,
        configRows.isEmpty() ? null : configRows.get(0));
  }

  // résolution de l'UUID artiste via ArtisteService.resoudreArtisteId — même dédup floue
  // (CorrespondanceArtiste.estDoublonArtiste) que l'ajout interactif, plutôt qu'un ON CONFLICT
  // exact sur nom_norm qui créait un doublon de référentiel pour une variante orthographique
  // mineure (ex. "The Rolling Stones" importé alors que "Rolling Stones" est déjà suivi).
  private int[] importerArtistes(String userId, List<ArtisteExport> artistes) {
    Integer nbExistants =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_artistes WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Integer.class);
    int restant = Math.max(0, 500 - (nbExistants != null ? nbExistants : 0));
    int traites = 0, rejetes = 0;
    for (ArtisteExport a : artistes) {
      if (a.nom() == null || a.nom().isBlank()) {
        rejetes++;
        continue;
      }
      if (FiltreArtiste.estTribute(a.nom())) {
        rejetes++;
        continue;
      }
      if (traites >= restant) {
        rejetes++;
        continue;
      }
      String nomSaisi = a.nom().strip();
      String normalise = Normalisation.normaliserTexte(nomSaisi);
      UUID artisteId = artisteService.resoudreArtisteId(nomSaisi, normalise);
      jdbc.update(
          """
          INSERT INTO user_artistes (user_id, artiste_affiche, artiste_id)
          VALUES (:userId, :nom, CAST(:artisteId AS uuid))
          ON CONFLICT (user_id, artiste_id) DO NOTHING
          """,
          Map.of(CLE_USER_ID, userId, "nom", nomSaisi, "artisteId", artisteId.toString()));
      traites++;
    }
    return new int[] {traites, rejetes};
  }

  // résolution de l'UUID salle via SalleService.resoudreSalleId — même dédup floue
  // (CorrespondanceSalle.estDoublonSalle) que l'ajout interactif, plutôt qu'un ON CONFLICT exact
  // sur salle_cle. Le salleId de l'export JSON n'est volontairement pas utilisé pour retrouver
  // une salle existante (comportement inchangé) — le nom est toujours re-normalisé.
  private int[] importerSalles(String userId, List<SalleExport> salles) {
    Integer nbExistants =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_salles WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Integer.class);
    int restant = Math.max(0, 200 - (nbExistants != null ? nbExistants : 0));
    int traites = 0, rejetes = 0;
    for (SalleExport s : salles) {
      String nomSalle = s.nom() != null && !s.nom().isBlank() ? s.nom().strip() : s.salleId();
      if (nomSalle == null || nomSalle.isBlank()) {
        rejetes++;
        continue;
      }
      String salleCle = SalleService.normaliserSalleCle(nomSalle);
      if (salleCle.isEmpty()) {
        rejetes++;
        continue;
      }
      if (traites >= restant) {
        rejetes++;
        continue;
      }
      UUID salleId = salleService.resoudreSalleId(nomSalle, salleCle);
      jdbc.update(
          """
          INSERT INTO user_salles (user_id, salle_id)
          VALUES (:userId, CAST(:salleId AS uuid))
          ON CONFLICT (user_id, salle_id) DO NOTHING
          """,
          Map.of(CLE_USER_ID, userId, "salleId", salleId.toString()));
      traites++;
    }
    return new int[] {traites, rejetes};
  }

  private void importerConfig(String userId, ConfigExport c) {
    String[] depts =
        c.departements() != null ? c.departements().toArray(String[]::new) : new String[0];
    jdbc.update(
        """
        INSERT INTO user_config
            (user_id, departements, filtre_disponibles, frequence_notifications,
             notif_artistes, notif_salles, notif_artistes_push, notif_salles_push,
             theme, updated_at)
        VALUES (:userId, :depts, :filtreDisponibles, CAST(:frequence AS frequence_notif),
                :notifArtistes, :notifSalles, :notifArtistesPush, :notifSallesPush,
                CAST(:theme AS theme_interface), now())
        ON CONFLICT (user_id) DO UPDATE SET
            departements            = EXCLUDED.departements,
            filtre_disponibles      = EXCLUDED.filtre_disponibles,
            frequence_notifications = EXCLUDED.frequence_notifications,
            notif_artistes          = EXCLUDED.notif_artistes,
            notif_salles            = EXCLUDED.notif_salles,
            notif_artistes_push     = EXCLUDED.notif_artistes_push,
            notif_salles_push       = EXCLUDED.notif_salles_push,
            theme                   = EXCLUDED.theme,
            updated_at              = now()
        """,
        Map.of(
            CLE_USER_ID,
            userId,
            "depts",
            depts,
            "filtreDisponibles",
            c.filtreDisponibles(),
            "frequence",
            c.frequenceNotifications() != null ? c.frequenceNotifications() : "jamais",
            "notifArtistes",
            c.notifArtistes(),
            "notifSalles",
            c.notifSalles(),
            "notifArtistesPush",
            c.notifArtistesPush(),
            "notifSallesPush",
            c.notifSallesPush(),
            "theme",
            c.theme() != null ? c.theme() : "auto"));
  }

  private void fusionnerDepartements(String userId, String[] nouveauxDepts) {
    jdbc.update(
        """
        UPDATE user_config
        SET departements = (
            SELECT ARRAY(
                SELECT DISTINCT unnest(departements || CAST(:depts AS text[]))
                ORDER BY 1
            )
        ),
        updated_at = now()
        WHERE user_id = :userId
        """,
        Map.of(CLE_USER_ID, userId, "depts", nouveauxDepts));
  }

  private static final java.util.regex.Pattern VENUE_ID_PATTERN =
      java.util.regex.Pattern.compile("[a-zA-Z0-9\\-]{1,64}");

  /// Diffère le déclenchement du worker jusqu'après le commit effectif de la transaction d'import
  /// (même pattern que {@code ArtisteService.executerApresCommit}).
  ///
  /// <p>Sans cela, le worker pouvait être sollicité avant que les artistes/salles importés ne
  /// soient visibles en base (isolation {@code READ COMMITTED}), faisant manquer l'import tout
  /// juste effectué au cycle de fetch qu'il vient lui-même de déclencher.
  ///
  /// @param salles salles de l'export, utilisées pour cibler les déclenchements par salle
  private void declencherWorkerApresImport(List<SalleExport> salles) {
    Runnable action =
        () -> {
          envoyerPostWorker(WORKER_URL + "/artiste");
          if (salles == null) return;
          salles.stream()
              .filter(s -> s.tmVenueId() != null && !s.tmVenueId().isBlank())
              .map(SalleExport::tmVenueId)
              .filter(venueId -> VENUE_ID_PATTERN.matcher(venueId).matches())
              .distinct()
              .forEach(venueId -> envoyerPostWorker(WORKER_URL + "/salle/" + venueId));
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }

  private void envoyerPostWorker(String url) {
    Thread.startVirtualThread(
        () -> {
          try {
            restClient.post().uri(url).retrieve().toBodilessEntity();
          } catch (Exception e) {
            log.warn("Déclenchement worker impossible : {}", url, e);
          }
        });
  }

  /// Résultat d'un import de préférences.
  public record ResultatImportComplet(int artistes, int salles, int rejetes) {}

  /// Nom du fichier d'export pour le téléchargement.
  ///
  /// @return nom avec la date du jour
  public static String nomFichierExport() {
    return "strapontin-preferences-" + LocalDate.now() + ".json";
  }
}

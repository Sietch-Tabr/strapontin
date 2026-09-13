/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.FiltreArtiste;
import fr.strapontin.core.Normalisation;
import fr.strapontin.web.WorkerTriggerUtils;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.artiste.ArtisteService;
import fr.strapontin.web.salle.SalleService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/**
 * Service d'import/export des préférences utilisateur.
 *
 * <p>L'import est atomique ({@code @Transactional}) — un échec partiel annule toute l'opération.
 *
 * @since 2.1.0
 * @author Kwaite
 */
@Service
public class PreferencesService {

  private static final Logger LOG = LoggerFactory.getLogger(PreferencesService.class);
  private static final String CLE_USER_ID = "userId";
  private static final String WORKER_URL = "http://localhost:8081/trigger";
  private static final Pattern VENUE_ID_PATTERN = Pattern.compile("[a-zA-Z0-9\\-]{1,64}");

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;
  private final LogAdminService logAdminService;
  private final ArtisteService artisteService;
  private final SalleService salleService;
  private final PreferencesExportService preferencesExportService;

  /**
   * @param jdbc accès JDBC
   * @param objectMapper désérialiseur JSON
   * @param restClient client HTTP partagé (module web), pour déclencher le worker
   * @param logAdminService journalisation des actions admin
   * @param artisteService résolution d'UUID artiste avec dédup floue
   * @param salleService résolution d'UUID salle avec dédup floue
   */
  public PreferencesService(
      final NamedParameterJdbcTemplate jdbc,
      final ObjectMapper objectMapper,
      final RestClient restClient,
      final LogAdminService logAdminService,
      final ArtisteService artisteService,
      final SalleService salleService) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.restClient = restClient;
    this.logAdminService = logAdminService;
    this.artisteService = artisteService;
    this.salleService = salleService;
    this.preferencesExportService = new PreferencesExportService(jdbc);
  }

  /**
   * Désérialise et valide un fichier d'export.
   *
   * @param contenu octets du fichier JSON
   * @return l'export désérialisé, ou {@code null} si invalide
   */
  public ExportComplet deserialiserExport(final byte[] contenu) {
    try {
      return objectMapper.readValue(contenu, ExportComplet.class);
    } catch (Exception e) {
      LOG.warn("Import préférences : désérialisation échouée", e);
      return null;
    }
  }

  /**
   * Importe les préférences dans une transaction atomique.
   *
   * @param userId identifiant de l'utilisateur
   * @param export export désérialisé
   * @param ecraser {@code true} pour remplacer artistes et salles existants
   * @param importerConfig {@code true} pour remplacer toute la config
   * @return résultat de l'import (artistes et salles traités/rejetés)
   */
  @Transactional
  public ResultatImportComplet importer(
      final String userId,
      final ExportComplet export,
      final boolean ecraser,
      final boolean importerConfig) {
    final SuppressionEcraser suppression = supprimerExistantSiEcraser(userId, ecraser);

    int[] artistes = {0, 0};
    int[] salles = {0, 0};

    if (export.artistes() != null) {
      artistes = importerArtistes(userId, export.artistes());
    }
    if (export.salles() != null) {
      salles = importerSalles(userId, export.salles());
    }

    if (importerConfig && export.config() != null) {
      importerConfig(userId, export.config());
    } else if (!ecraser && export.config() != null && export.config().departements() != null) {
      fusionnerDepartements(userId, export.config().departements().toArray(String[]::new));
    }

    declencherWorkerApresImport(export.salles());
    final ResultatImportComplet resultat =
        new ResultatImportComplet(artistes[0], salles[0], artistes[1] + salles[1]);
    journaliserImport(userId, resultat, ecraser, suppression);
    return resultat;
  }

  private record SuppressionEcraser(int nbArtistes, int nbSalles) {}

  private SuppressionEcraser supprimerExistantSiEcraser(
      final String userId, final boolean ecraser) {
    if (!ecraser) {
      return new SuppressionEcraser(0, 0);
    }
    final int nbSupprimesArtistes =
        jdbc.update(
            "DELETE FROM user_artistes WHERE user_id = :userId", Map.of(CLE_USER_ID, userId));
    final int nbSupprimesSalles =
        jdbc.update("DELETE FROM user_salles WHERE user_id = :userId", Map.of(CLE_USER_ID, userId));
    LOG.warn(
        "import préférences mode écraser : {} artiste(s) et {} salle(s) supprimés userId={}",
        nbSupprimesArtistes,
        nbSupprimesSalles,
        userId);
    return new SuppressionEcraser(nbSupprimesArtistes, nbSupprimesSalles);
  }

  private void journaliserImport(
      final String userId,
      final ResultatImportComplet resultat,
      final boolean ecraser,
      final SuppressionEcraser suppression) {
    LOG.info(
        "import préférences userId={} artistes={} salles={} rejetes={} mode={}",
        userId,
        resultat.artistes(),
        resultat.salles(),
        resultat.rejetes(),
        ecraser ? "ecraser" : "fusionner");
    final String niveauLog = ecraser ? "warn" : "info";
    final Map<String, Object> metaImport = new LinkedHashMap<>();
    metaImport.put("userId", userId);
    metaImport.put("artistes", resultat.artistes());
    metaImport.put("salles", resultat.salles());
    metaImport.put("rejetes", resultat.rejetes());
    metaImport.put("mode", ecraser ? "ecraser" : "fusionner");
    if (ecraser) {
      metaImport.put("supprimesArtistes", suppression.nbArtistes());
      metaImport.put("supprimesSalles", suppression.nbSalles());
    }
    logAdminService.logAdmin(niveauLog, "web", "import préférences", metaImport);
  }

  /**
   * Exporte les préférences de l'utilisateur (artistes, salles, config).
   *
   * @param userId identifiant de l'utilisateur
   * @return l'export complet
   */
  public ExportComplet exporter(final String userId) {
    return preferencesExportService.exporter(userId);
  }

  // résolution de l'UUID artiste via ArtisteService.resoudreArtisteId — même
  // dédup floue (CorrespondanceArtiste.estDoublonArtiste) que l'ajout
  // interactif, plutôt qu'un ON CONFLICT exact sur nom_norm qui créait un
  // doublon de référentiel pour une variante orthographique mineure (ex. "The
  // Rolling Stones" importé alors que "Rolling Stones" est déjà suivi).
  private int[] importerArtistes(final String userId, final List<ArtisteExport> artistes) {
    final Integer nbExistants =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_artistes WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Integer.class);
    final int restant = Math.max(0, 500 - (nbExistants != null ? nbExistants : 0));
    int traites = 0;
    int rejetes = 0;
    for (final ArtisteExport a : artistes) {
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
      final String nomSaisi = a.nom().strip();
      final String normalise = Normalisation.normaliserTexte(nomSaisi);
      final UUID artisteId = artisteService.resoudreArtisteId(nomSaisi, normalise);
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

  // résolution de l'UUID salle via SalleService.resoudreSalleId — même dédup
  // floue (CorrespondanceSalle.estDoublonSalle) que l'ajout interactif, plutôt
  // qu'un ON CONFLICT exact sur salle_cle. Le salleId de l'export JSON n'est
  // volontairement pas utilisé pour retrouver une salle existante (comportement
  // inchangé) — le nom est toujours re-normalisé.
  private int[] importerSalles(final String userId, final List<SalleExport> salles) {
    final Integer nbExistants =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_salles WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Integer.class);
    final int restant = Math.max(0, 200 - (nbExistants != null ? nbExistants : 0));
    int traites = 0;
    int rejetes = 0;
    for (final SalleExport s : salles) {
      final String nomSalle = s.nom() != null && !s.nom().isBlank() ? s.nom().strip() : s.salleId();
      if (nomSalle == null || nomSalle.isBlank()) {
        rejetes++;
        continue;
      }
      final String salleCle = SalleService.normaliserSalleCle(nomSalle);
      if (salleCle.isEmpty()) {
        rejetes++;
        continue;
      }
      if (traites >= restant) {
        rejetes++;
        continue;
      }
      final UUID salleId = salleService.resoudreSalleId(nomSalle, salleCle);
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

  private void importerConfig(final String userId, final ConfigExport config) {
    final String[] depts =
        config.departements() != null
            ? config.departements().toArray(String[]::new)
            : new String[0];
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
            config.filtreDisponibles(),
            "frequence",
            config.frequenceNotifications() != null ? config.frequenceNotifications() : "jamais",
            "notifArtistes",
            config.notifArtistes(),
            "notifSalles",
            config.notifSalles(),
            "notifArtistesPush",
            config.notifArtistesPush(),
            "notifSallesPush",
            config.notifSallesPush(),
            "theme",
            config.theme() != null ? config.theme() : "auto"));
  }

  private void fusionnerDepartements(final String userId, final String... nouveauxDepts) {
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

  /**
   * Diffère le déclenchement du worker jusqu'après le commit effectif de la transaction d'import
   * (même pattern que {@code ArtisteService.executerApresCommit}).
   *
   * <p>Sans cela, le worker pouvait être sollicité avant que les artistes/salles importés ne soient
   * visibles en base (isolation {@code READ COMMITTED}), faisant manquer l'import tout juste
   * effectué au cycle de fetch qu'il vient lui-même de déclencher.
   *
   * @param salles salles de l'export, utilisées pour cibler les déclenchements par salle
   */
  private void declencherWorkerApresImport(final List<SalleExport> salles) {
    final Runnable action =
        () -> {
          envoyerPostWorker(WORKER_URL + "/artiste");
          if (salles == null) {
            return;
          }
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

  private void envoyerPostWorker(final String url) {
    WorkerTriggerUtils.declencherWorkerAsync(
        restClient, LOG, url, "Déclenchement worker impossible : " + url);
  }

  /** Résultat d'un import de préférences. */
  public record ResultatImportComplet(int artistes, int salles, int rejetes) {}

  /**
   * Nom du fichier d'export pour le téléchargement.
   *
   * @return nom avec la date du jour
   */
  public static String nomFichierExport() {
    return "strapontin-preferences-" + LocalDate.now() + ".json";
  }
}

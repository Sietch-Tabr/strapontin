/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.DistanceGeo;
import fr.strapontin.core.FusionEvenementsSql;
import fr.strapontin.core.NomsDeLieu;
import fr.strapontin.core.QualificatifSalle;
import fr.strapontin.core.ReglesCommunes;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Détecte et fusionne en cycle nocturne des paires de salles désignant le
 * même bâtiment (clé forte + {@link CorrespondanceSalle#estDoublonSalle}),
 * complétant {@link ReconciliateurSalles} (détails : wiki Sources-de-données).
 *
 * @since 2.8.4
 * @author Kwaite
 */
@Service
public class FusionSallesClePorteeService {

  private static final Logger LOG = LoggerFactory.getLogger(FusionSallesClePorteeService.class);

  // Distance au-delà de laquelle deux salles ne peuvent structurellement pas
  // être le même bâtiment, même en cas de coïncidence artiste+date+heure+dept
  // et de nom similaire — un complexe multi-salles (La Seine Musicale, #1180)
  // peut partager un nom quasi identique entre deux halls physiquement
  // distincts de plusieurs centaines de mètres.
  private static final double DISTANCE_MAX_FUSION_METRES = 500;
  // Une position de billetterie peut être fausse de plusieurs kilomètres
  // (centre-ville au lieu de la salle) : deux concerts identiques et des noms
  // qui désignent le même lieu suffisent alors.
  private static final double DISTANCE_MAX_FUSION_CONCERTS_PARTAGES_METRES = 5_000;
  private static final int NB_CONCERTS_PARTAGES_DISTANCE_ELARGIE = 2;
  // Deux billetteries publient la même séance à 30 ou 60 minutes d'écart
  // (ouverture des portes et début) ; au-delà, ce sont deux séances.
  private static final String CLE_CIBLE = "cible";
  private static final int NB_SALLES_PAIRE = 2;
  private static final String CLE_SOURCE = "source";

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;

  private record PaireSalles(UUID idA, UUID idB) {}

  /**
   * @param jdbc accès JDBC
   * @param logsAdminWorker journalisation de chaque fusion effectuée
   */
  public FusionSallesClePorteeService(
      final NamedParameterJdbcTemplate jdbc, final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Détecte puis fusionne les paires de salles partageant un concert à
   * venir identique — une paire déjà disparue (fusionnée plus tôt dans le
   * même cycle) est ignorée silencieusement (redondante).
   * @return nombre de fusions effectuées
   */
  public int fusionner() {
    final List<PaireSalles> paires = detecterPaires();
    final Set<UUID> dejaAbsorbees = new HashSet<>();
    int fusions = 0;
    for (final PaireSalles paire : paires) {
      if (dejaAbsorbees.contains(paire.idA()) || dejaAbsorbees.contains(paire.idB())) {
        continue;
      }
      final UUID[] cibleSource = choisirCibleEtSource(paire.idA(), paire.idB());
      if (cibleSource == null) {
        continue;
      }
      fusionnerUnePaire(cibleSource[0], cibleSource[1]);
      dejaAbsorbees.add(cibleSource[1]);
      fusions++;
    }
    return fusions;
  }

  /**
   * Paires de salles partageant un concert (artiste, date, dept, heures à
   * moins d'une heure), aux noms doublons ou désignant fortement le même
   * lieu, de même département, à moins de {@value #DISTANCE_MAX_FUSION_METRES}
   * m (coordonnées connues des deux côtés) ou à moins de cinq kilomètres si
   * elles partagent au moins deux concerts, hors complexe/sous-salle, paires
   * déclarées distinctes et salles signalées contaminées.
   */
  private List<PaireSalles> detecterPaires() {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT c1.salle_id AS salle_a, c2.salle_id AS salle_b,
                   sa.nom_norm AS nom_a, sb.nom_norm AS nom_b,
                   sa.nom AS brut_a, sb.nom AS brut_b,
                   concat_ws(' ', sa.ville, sb.ville) AS villes,
                   sa.latitude AS lat_a, sa.longitude AS lon_a,
                   sb.latitude AS lat_b, sb.longitude AS lon_b,
                   count(*) AS nb_partages
            FROM concerts c1
            JOIN concerts c2
              ON c1.artiste_norm = c2.artiste_norm
             AND c1.date = c2.date
             AND abs(extract(epoch FROM (c1.heure - c2.heure))) <= :ecartHeure
             AND c1.dept = c2.dept
             AND c1.salle_id < c2.salle_id
            JOIN salles sa ON sa.id = c1.salle_id
            JOIN salles sb ON sb.id = c2.salle_id
            WHERE c1.salle_id IS NOT NULL AND c2.salle_id IS NOT NULL
              AND c1.heure IS NOT NULL AND c1.dept IS NOT NULL
              AND c1.date >= CURRENT_DATE
              AND sa.dept IS NOT DISTINCT FROM sb.dept
              AND sa.salle_parent_id IS DISTINCT FROM sb.id
              AND sb.salle_parent_id IS DISTINCT FROM sa.id
              AND NOT EXISTS (
                SELECT 1 FROM salles_paires_distinctes d
                WHERE d.salle_a = LEAST(sa.id, sb.id)
                  AND d.salle_b = GREATEST(sa.id, sb.id))
              AND\
            """
                + SalleFiable.condition("sa")
                + " AND "
                + SalleFiable.condition("sb")
                + """

            GROUP BY c1.salle_id, c2.salle_id, sa.nom_norm, sb.nom_norm, sa.nom, sb.nom,
                     sa.ville, sb.ville, sa.latitude, sa.longitude, sb.latitude, sb.longitude
            """,
            Map.of("ecartHeure", ReglesCommunes.ECART_MAX_HEURE_SECONDES));
    final List<PaireSalles> paires = new ArrayList<>();
    for (final Map<String, Object> ligne : lignes) {
      if (memeLieu(ligne)) {
        paires.add(new PaireSalles((UUID) ligne.get("salle_a"), (UUID) ligne.get("salle_b")));
      }
    }
    return paires;
  }

  private static boolean memeLieu(final Map<String, Object> ligne) {
    final String nomA = (String) ligne.get("nom_a");
    final String nomB = (String) ligne.get("nom_b");
    final boolean nomsDuMemeLieu =
        CorrespondanceSalle.estDoublonSalle(nomA, nomB)
            || NomsDeLieu.designentFortementLeMemeLieu(nomA, nomB, (String) ligne.get("villes"));
    return nomsDuMemeLieu
        && !QualificatifSalle.qualificatifsDivergents(
            (String) ligne.get("brut_a"), (String) ligne.get("brut_b"))
        && positionsCompatibles(ligne);
  }

  private static boolean positionsCompatibles(final Map<String, Object> ligne) {
    final Double latA = (Double) ligne.get("lat_a");
    final Double lonA = (Double) ligne.get("lon_a");
    final Double latB = (Double) ligne.get("lat_b");
    final Double lonB = (Double) ligne.get("lon_b");
    return coordonneesConnuesEtProches(latA, lonA, latB, lonB)
        || (((Number) ligne.get("nb_partages")).intValue() >= NB_CONCERTS_PARTAGES_DISTANCE_ELARGIE
            && DistanceGeo.connuesEtProches(
                latA, lonA, latB, lonB, DISTANCE_MAX_FUSION_CONCERTS_PARTAGES_METRES));
  }

  /**
   * Vérifie que deux salles ont des coordonnées connues et proches, seul
   * signal positif suffisant pour une fusion automatique.
   * @return {@code true} si coordonnées connues et à moins de
   *     {@value #DISTANCE_MAX_FUSION_METRES} m l'une de l'autre
   */
  public static boolean coordonneesConnuesEtProches(
      final Double latA, final Double lonA, final Double latB, final Double lonB) {
    return DistanceGeo.connuesEtProches(latA, lonA, latB, lonB, DISTANCE_MAX_FUSION_METRES);
  }

  /**
   * Choisit la salle à conserver (cible) entre les deux membres de la paire
   * : priorité au wikidata_id déjà connu (donnée coûteuse à reconstituer),
   * puis au nombre de concerts déjà rattachés, puis à l'UUID le plus petit
   * pour un résultat déterministe entre deux cycles.
   * @return [cible, source], ou {@code null} si l'une des deux n'existe plus
   */
  // Le tableau représente une paire cible/source, pas une collection à agréger
  // — l'appelant teste explicitement null pour distinguer "salle disparue" d'un
  // tableau vide, sémantiques différentes.
  @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
  private UUID[] choisirCibleEtSource(final UUID idA, final UUID idB) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT s.id AS id, s.wikidata_id AS wikidata_id,
                   (SELECT COUNT(*) FROM concerts c WHERE c.salle_id = s.id) AS nb_concerts
            FROM salles s WHERE s.id IN (CAST(:a AS uuid), CAST(:b AS uuid))
            """,
            Map.of("a", idA.toString(), "b", idB.toString()));
    if (lignes.size() < NB_SALLES_PAIRE) {
      return null;
    }

    final Map<String, Object> la =
        lignes.get(0).get("id").equals(idA) ? lignes.get(0) : lignes.get(1);
    final Map<String, Object> lb =
        lignes.get(0).get("id").equals(idA) ? lignes.get(1) : lignes.get(0);

    final boolean aWikidata = la.get("wikidata_id") != null;
    final boolean bWikidata = lb.get("wikidata_id") != null;
    if (aWikidata != bWikidata) {
      return aWikidata ? new UUID[] {idA, idB} : new UUID[] {idB, idA};
    }

    final long nbA = ((Number) la.get("nb_concerts")).longValue();
    final long nbB = ((Number) lb.get("nb_concerts")).longValue();
    if (nbA != nbB) {
      return nbA > nbB ? new UUID[] {idA, idB} : new UUID[] {idB, idA};
    }

    return idA.compareTo(idB) <= 0 ? new UUID[] {idA, idB} : new UUID[] {idB, idA};
  }

  /**
   * Fusionne deux salles établies (réassigne concerts/abonnements/sources/
   * sous-salles, récupère les métadonnées manquantes, supprime la source).
   * Public : réutilisé par EnrichissementSallesService (collision wikidata_id).
   * @param cibleId salle à conserver
   * @param sourceId salle à absorber puis supprimer
   */
  @Transactional
  public void fusionnerUnePaire(final UUID cibleId, final UUID sourceId) {
    fusionnerUnePaire(cibleId, sourceId, "clé forte (artiste+date+heure+dept)");
  }

  /**
   * Fusionne les paires de salles d'un même lieu (positions et noms
   * concordants) qui sont fiables, de même département et hors complexe et
   * sous-salle ; une paire déjà absorbée dans le cycle est ignorée.
   * @param paires paires {@code [salleA, salleB]} détectées au même lieu
   * @return nombre de fusions effectuées
   */
  public int fusionnerMemesLieux(final List<UUID[]> paires) {
    final Set<UUID> dejaAbsorbees = new HashSet<>();
    int fusions = 0;
    for (final UUID[] paire : paires) {
      if (dejaAbsorbees.contains(paire[0])
          || dejaAbsorbees.contains(paire[1])
          || !fusionnableAuMemeLieu(paire[0], paire[1])) {
        continue;
      }
      final UUID[] cibleSource = choisirCibleEtSource(paire[0], paire[1]);
      if (cibleSource != null) {
        fusionnerUnePaire(cibleSource[0], cibleSource[1], "même lieu (position et nom)");
        dejaAbsorbees.add(cibleSource[1]);
        fusions++;
      }
    }
    return fusions;
  }

  private boolean fusionnableAuMemeLieu(final UUID idA, final UUID idB) {
    final Integer nb =
        jdbc.queryForObject(
            "SELECT count(*) FROM salles sa JOIN salles sb ON sb.id = CAST(:b AS uuid)"
                + " WHERE sa.id = CAST(:a AS uuid) AND sa.dept IS NOT NULL AND sa.dept = sb.dept"
                + " AND sa.salle_parent_id IS DISTINCT FROM sb.id"
                + " AND sb.salle_parent_id IS DISTINCT FROM sa.id AND"
                + SalleFiable.condition("sa")
                + " AND"
                + SalleFiable.condition("sb"),
            Map.of("a", idA.toString(), "b", idB.toString()),
            Integer.class);
    return nb != null && nb > 0;
  }

  /**
   * @param cibleId salle à conserver
   * @param sourceId salle à absorber puis supprimer
   * @param motif raison de la fusion, journalisée
   */
  private void fusionnerUnePaire(final UUID cibleId, final UUID sourceId, final String motif) {
    final Map<String, Object> ids =
        Map.of(CLE_CIBLE, cibleId.toString(), CLE_SOURCE, sourceId.toString());
    // Lus avant la suppression : la salle absorbée n'est sinon plus identifiable.
    final String nomCible = decrireSalle(cibleId);
    final String nomSource = decrireSalle(sourceId);

    FusionEvenementsSql.fusionner(jdbc, FusionEvenementsSql.SALLE, ids);
    fusionnerAbonnementsEtSources(ids, cibleId);
    fusionnerMetadonneesEtSousSalles(ids, cibleId, sourceId);

    // 7. Suppression de la salle source
    jdbc.update("DELETE FROM salles WHERE id = CAST(:source AS uuid)", ids);

    LOG.info(
        "reconciliation:nocturne — salles fusionnées ({}) cible={} ({}) source={} ({})",
        motif,
        cibleId,
        nomCible,
        sourceId,
        nomSource);
    // Fusion automatique et destructive (suppression d'une salle établie) :
    // jamais silencieuse, contrairement au reste du cycle nocturne qui ne fait
    // que journaliser un constat.
    logsAdminWorker.inserer(
        "info",
        "reconciliation-nocturne",
        "salles fusionnées automatiquement par "
            + motif
            + " cible="
            + nomCible
            + " source="
            + nomSource,
        Map.of(
            "salleCible",
            cibleId.toString(),
            "salleSource",
            sourceId.toString(),
            "nomCible",
            nomCible,
            "nomSource",
            nomSource));
  }

  private String decrireSalle(final UUID id) {
    return jdbc
        .query(
            "SELECT nom || ' (' || coalesce(nullif(dept, ''), '?') || ')' FROM salles"
                + " WHERE id = CAST(:id AS uuid)",
            Map.of("id", id.toString()),
            (rs, _) -> rs.getString(1))
        .stream()
        .findFirst()
        .orElse(id.toString());
  }

  // 3. Abonnements en double supprimés, le reste réassigné. 4. Sources externes
  // réassignées, toutes gardées.
  private void fusionnerAbonnementsEtSources(final Map<String, Object> ids, final UUID cibleId) {
    jdbc.update(
        """
        DELETE FROM user_salles
        WHERE salle_id = CAST(:source AS uuid)
          AND EXISTS (
            SELECT 1 FROM user_salles u2
            WHERE u2.user_id = user_salles.user_id AND u2.salle_id = CAST(:cible AS uuid)
          )
        """,
        ids);
    jdbc.update(
        "UPDATE user_salles SET salle_id = CAST(:cible AS uuid) WHERE salle_id = CAST(:source"
            + " AS uuid)",
        ids);

    // Tous les lieux sont gardés : une salle a souvent plusieurs lieux d'une même
    // source pour un même bâtiment, et la clé (source, identifiant) n'en dépend pas.
    final List<String> lieuxReportes =
        jdbc.queryForList(
            "SELECT source || ':' || identifiant_externe FROM salle_sources"
                + " WHERE salle_id = CAST(:source AS uuid) ORDER BY 1",
            ids,
            String.class);
    jdbc.update(
        "UPDATE salle_sources SET salle_id = CAST(:cible AS uuid) WHERE salle_id = CAST(:source"
            + " AS uuid)",
        ids);
    if (!lieuxReportes.isEmpty()) {
      logsAdminWorker.inserer(
          "info",
          "reconciliation-nocturne",
          "lieux reportés sur la salle conservée après fusion cible=" + cibleId,
          Map.of("salleCible", cibleId.toString(), "lieux", lieuxReportes));
    }
  }

  // 5. Métadonnées récupérées si la cible ne les a pas déjà. 6. Sous-salles de
  // la source réassignées à la cible — sinon la suppression de la source
  // orpheline leurs salle_parent_id (violation de contrainte FK). Si la cible
  // était elle-même une sous-salle de la source (cas limite : un enfant
  // l'emporte sur son propre complexe par nombre de concerts), elle hérite du
  // parent de la source plutôt que de rester rattachée à une salle sur le point
  // d'être supprimée.
  private void fusionnerMetadonneesEtSousSalles(
      final Map<String, Object> ids, final UUID cibleId, final UUID sourceId) {
    jdbc.update(
        """
        UPDATE salles SET
          wikidata_id = COALESCE(wikidata_id, (SELECT wikidata_id FROM salles WHERE id = CAST(:source AS uuid))),
          nom_affiche = COALESCE(nom_affiche, (SELECT nom_affiche FROM salles WHERE id = CAST(:source AS uuid)))
        WHERE id = CAST(:cible AS uuid)
        """,
        ids);

    jdbc.update(
        "UPDATE salles SET salle_parent_id ="
            + " (SELECT salle_parent_id FROM salles WHERE id = CAST(:source AS uuid))"
            + " WHERE id = CAST(:cible AS uuid) AND salle_parent_id = CAST(:source AS uuid)",
        ids);
    new ReassignationSousSalles(jdbc, logsAdminWorker).reassignerSansCycle(cibleId, sourceId);
  }
}

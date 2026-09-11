/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.SalleHierarchie;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Détecte et fusionne automatiquement, en cycle nocturne, des paires de salles déjà établies
 * (chacune avec ses propres concerts déjà résolus) qui désignent en réalité le même bâtiment
 * physique — repérées par un concert partageant exactement artiste+date+heure+dept entre les deux,
 * et validées par {@link CorrespondanceSalle#estDoublonSalle} sur leurs noms. Le seul critère
 * artiste+date+heure+dept, sans comparaison de nom, s'est révélé insuffisant en production : un
 * concert coïncidant par erreur upstream (ex. un mapping {@code salle_sources} corrompu) suffisait
 * à fusionner deux salles réellement distinctes.
 *
 * Complète {@link ReconciliateurSalles#chercherSalleParCleForte}, qui n'agit qu'au moment de la
 * résolution d'un nouveau concert entrant : deux salles déjà établies sous des identités
 * distinctes (chacune avec ses propres {@code salle_sources}, éventuellement un {@code
 * wikidata_id}) n'étaient jamais reconsidérées après coup, laissant la scission s'accumuler à
 * chaque nouveau concert de la même série.
 *
 * Porte le même gabarit de fusion que {@code ReferentielSallesService.fusionner} (module web, non
 * accessible depuis le worker) : dédoublonnage des {@code salle_sources} en conflit avant
 * réassignation, puis concerts, puis abonnements utilisateurs.
 *
 * @since 2.8.4
 * @author Kwaite
 */
@Service
public class FusionSallesClePorteeService {

  private static final Logger log = LoggerFactory.getLogger(FusionSallesClePorteeService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;

  public FusionSallesClePorteeService(
      NamedParameterJdbcTemplate jdbc, LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
  }

  private record PaireSalles(UUID a, UUID b) {}

  /**
   * Détecte puis fusionne les paires de salles distinctes partageant un concert à venir identique
   * (artiste+date+heure+dept). Une paire dont l'une des deux salles a déjà disparu (fusionnée plus
   * tôt dans le même cycle) est ignorée silencieusement — elle est nécessairement redondante avec
   * une fusion déjà effectuée.
   *
   * @return nombre de fusions effectuées
   */
  public int fusionner() {
    List<PaireSalles> paires = detecterPaires();
    Set<UUID> dejaAbsorbees = new HashSet<>();
    int fusions = 0;
    for (PaireSalles paire : paires) {
      if (dejaAbsorbees.contains(paire.a()) || dejaAbsorbees.contains(paire.b())) continue;
      UUID[] cibleSource = choisirCibleEtSource(paire.a(), paire.b());
      if (cibleSource == null) continue;
      fusionnerUnePaire(cibleSource[0], cibleSource[1]);
      dejaAbsorbees.add(cibleSource[1]);
      fusions++;
    }
    return fusions;
  }

  /**
   * Repère, parmi les concerts à venir, les paires de {@code salle_id} distincts partageant
   * exactement le même artiste, la même date, la même heure et le même département — même clé que
   * {@link ReconciliateurSalles#chercherSalleParCleForte}, appliquée ici rétroactivement à des
   * salles déjà résolues chacune de leur côté plutôt qu'au moment de l'insertion — puis filtrées
   * par {@link CorrespondanceSalle#estDoublonSalle} sur les noms des deux salles : un seul concert
   * coïncidant par erreur upstream ne doit jamais suffire à fusionner deux salles sans rapport
   * réel (voir le rationale de classe).
   */
  private List<PaireSalles> detecterPaires() {
    List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT DISTINCT c1.salle_id AS salle_a, c2.salle_id AS salle_b,
                   sa.nom_norm AS nom_a, sb.nom_norm AS nom_b,
                   sa.latitude AS lat_a, sa.longitude AS lon_a,
                   sb.latitude AS lat_b, sb.longitude AS lon_b
            FROM concerts c1
            JOIN concerts c2
              ON c1.artiste_norm = c2.artiste_norm
             AND c1.date = c2.date
             AND c1.heure = c2.heure
             AND c1.dept = c2.dept
             AND c1.salle_id < c2.salle_id
            JOIN salles sa ON sa.id = c1.salle_id
            JOIN salles sb ON sb.id = c2.salle_id
            WHERE c1.salle_id IS NOT NULL AND c2.salle_id IS NOT NULL
              AND c1.heure IS NOT NULL AND c1.dept IS NOT NULL
              AND c1.date >= CURRENT_DATE
            """,
            Map.of());
    List<PaireSalles> paires = new ArrayList<>();
    for (var ligne : lignes) {
      String nomA = (String) ligne.get("nom_a");
      String nomB = (String) ligne.get("nom_b");
      if (!CorrespondanceSalle.estDoublonSalle(nomA, nomB)) continue;
      if (distanceExcessive(ligne)) continue;
      paires.add(new PaireSalles((UUID) ligne.get("salle_a"), (UUID) ligne.get("salle_b")));
    }
    return paires;
  }

  // Distance au-delà de laquelle deux salles ne peuvent structurellement pas être le même
  // bâtiment, même en cas de coïncidence artiste+date+heure+dept et de nom similaire — un
  // complexe multi-salles (La Seine Musicale, #1180) peut partager un nom quasi identique entre
  // deux halls physiquement distincts de plusieurs centaines de mètres.
  private static final double DISTANCE_MAX_FUSION_METRES = 500;

  /**
   * @return {@code true} si les deux salles ont toutes deux des coordonnées connues et que leur
   *   distance dépasse {@value #DISTANCE_MAX_FUSION_METRES} m — {@code false} si l'une des deux
   *   coordonnées est inconnue (comportement actuel nom+dept inchangé dans ce cas).
   */
  private static boolean distanceExcessive(Map<String, Object> ligne) {
    Double latA = (Double) ligne.get("lat_a");
    Double lonA = (Double) ligne.get("lon_a");
    Double latB = (Double) ligne.get("lat_b");
    Double lonB = (Double) ligne.get("lon_b");
    if (latA == null || lonA == null || latB == null || lonB == null) return false;
    return distanceHaversineMetres(latA, lonA, latB, lonB) > DISTANCE_MAX_FUSION_METRES;
  }

  /**
   * Vérifie que deux salles ont chacune des coordonnées connues et suffisamment proches pour
   * justifier une fusion automatique sans aucun autre signal positif — contrairement à {@link
   * #distanceExcessive}, qui reste permissif quand une coordonnée manque (nom+dept déjà validés
   * par ailleurs), une collision {@code wikidata_id} seule n'a aucun autre garde-fou géographique
   * : l'absence de preuve positive doit alors empêcher la fusion plutôt que l'autoriser par
   * défaut.
   *
   * @return {@code true} si les deux salles ont des coordonnées connues et sont à moins de {@value
   *   #DISTANCE_MAX_FUSION_METRES} m l'une de l'autre
   */
  public static boolean coordonneesConnuesEtProches(
      Double latA, Double lonA, Double latB, Double lonB) {
    if (latA == null || lonA == null || latB == null || lonB == null) return false;
    return distanceHaversineMetres(latA, lonA, latB, lonB) <= DISTANCE_MAX_FUSION_METRES;
  }

  private static final double RAYON_TERRE_METRES = 6_371_000;

  private static double distanceHaversineMetres(
      double lat1, double lon1, double lat2, double lon2) {
    double phi1 = Math.toRadians(lat1);
    double phi2 = Math.toRadians(lat2);
    double deltaPhi = Math.toRadians(lat2 - lat1);
    double deltaLambda = Math.toRadians(lon2 - lon1);
    double a =
        Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
            + Math.cos(phi1)
                * Math.cos(phi2)
                * Math.sin(deltaLambda / 2)
                * Math.sin(deltaLambda / 2);
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return RAYON_TERRE_METRES * c;
  }

  /**
   * Choisit la salle à conserver (cible) entre les deux membres de la paire : priorité au {@code
   * wikidata_id} déjà connu (donnée la plus coûteuse à reconstituer), puis au nombre de concerts
   * déjà rattachés (établissement le plus riche), puis à l'UUID le plus petit pour un résultat
   * déterministe entre deux cycles.
   *
   * @return {@code [cible, source]}, ou {@code null} si l'une des deux salles n'existe plus
   */
  private UUID[] choisirCibleEtSource(UUID a, UUID b) {
    List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT s.id AS id, s.wikidata_id AS wikidata_id,
                   (SELECT COUNT(*) FROM concerts c WHERE c.salle_id = s.id) AS nb_concerts
            FROM salles s WHERE s.id IN (CAST(:a AS uuid), CAST(:b AS uuid))
            """,
            Map.of("a", a.toString(), "b", b.toString()));
    if (lignes.size() < 2) return null;

    Map<String, Object> la = lignes.get(0).get("id").equals(a) ? lignes.get(0) : lignes.get(1);
    Map<String, Object> lb = lignes.get(0).get("id").equals(a) ? lignes.get(1) : lignes.get(0);

    boolean aWikidata = la.get("wikidata_id") != null;
    boolean bWikidata = lb.get("wikidata_id") != null;
    if (aWikidata != bWikidata) return aWikidata ? new UUID[] {a, b} : new UUID[] {b, a};

    long nbA = ((Number) la.get("nb_concerts")).longValue();
    long nbB = ((Number) lb.get("nb_concerts")).longValue();
    if (nbA != nbB) return nbA > nbB ? new UUID[] {a, b} : new UUID[] {b, a};

    return a.compareTo(b) <= 0 ? new UUID[] {a, b} : new UUID[] {b, a};
  }

  /**
   * Fusionne deux salles déjà établies : réassigne concerts/abonnements/sources externes/
   * sous-salles de la source vers la cible, récupère les métadonnées manquantes, puis supprime la
   * source. Rendu public pour être réutilisé par {@code EnrichissementSallesService} (collision
   * {@code wikidata_id}, #1189) — appelant distinct du cycle nocturne de cette classe.
   *
   * @param cibleId salle à conserver
   * @param sourceId salle à absorber puis supprimer
   */
  @Transactional
  public void fusionnerUnePaire(UUID cibleId, UUID sourceId) {
    Map<String, Object> ids = Map.of("cible", cibleId.toString(), "source", sourceId.toString());

    // 1. Événements en conflit (même artiste/date/heure déjà présent côté cible) : leurs
    // observations sont reportées sur l'événement cible plutôt que perdues (#1192) — celles qui
    // entreraient en conflit sur (evenement_id, source, url) sont supprimées, déjà représentées
    // côté cible. L'événement source, vidé de ses observations, est ensuite supprimé.
    jdbc.update(
        """
        DELETE FROM observations o1
        USING observations o2, evenements e1, evenements e2
        WHERE o1.evenement_id = e1.id AND o2.evenement_id = e2.id
          AND e1.salle_id = CAST(:source AS uuid) AND e2.salle_id = CAST(:cible AS uuid)
          AND e1.artiste_norm = e2.artiste_norm AND e1.date = e2.date
          AND e1.heure IS NOT DISTINCT FROM e2.heure
          AND o1.source = o2.source AND o1.url IS NOT DISTINCT FROM o2.url
        """,
        ids);
    jdbc.update(
        """
        UPDATE observations SET evenement_id = e2.id
        FROM evenements e1, evenements e2
        WHERE observations.evenement_id = e1.id
          AND e1.salle_id = CAST(:source AS uuid) AND e2.salle_id = CAST(:cible AS uuid)
          AND e1.artiste_norm = e2.artiste_norm AND e1.date = e2.date
          AND e1.heure IS NOT DISTINCT FROM e2.heure
        """,
        ids);
    jdbc.update(
        """
        DELETE FROM evenements
        WHERE salle_id = CAST(:source AS uuid)
          AND NOT EXISTS (SELECT 1 FROM observations WHERE observations.evenement_id = evenements.id)
        """,
        ids);

    // 2. Réassigner les événements restants (ceux sans doublon côté cible)
    jdbc.update(
        "UPDATE evenements SET salle_id = CAST(:cible AS uuid) WHERE salle_id = CAST(:source AS"
            + " uuid)",
        ids);

    // 3. Abonnements en double supprimés, le reste réassigné
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

    // 4. Sources externes réassignées, dédoublonnage préalable des conflits par type de source
    dedupliquerSallesSourcesAvantFusion(cibleId, sourceId);
    jdbc.update(
        "UPDATE salle_sources SET salle_id = CAST(:cible AS uuid) WHERE salle_id = CAST(:source"
            + " AS uuid)",
        ids);

    // 5. Métadonnées récupérées si la cible ne les a pas déjà
    jdbc.update(
        """
        UPDATE salles SET
          wikidata_id = COALESCE(wikidata_id, (SELECT wikidata_id FROM salles WHERE id = CAST(:source AS uuid))),
          nom_affiche = COALESCE(nom_affiche, (SELECT nom_affiche FROM salles WHERE id = CAST(:source AS uuid)))
        WHERE id = CAST(:cible AS uuid)
        """,
        ids);

    // 6. Sous-salles de la source réassignées à la cible — sinon la suppression de la source
    // orpheline leurs salle_parent_id (violation de contrainte FK). Si la cible était elle-même
    // une sous-salle de la source (cas limite : un enfant l'emporte sur son propre complexe par
    // nombre de concerts), elle hérite du parent de la source plutôt que de rester rattachée à une
    // salle sur le point d'être supprimée.
    jdbc.update(
        "UPDATE salles SET salle_parent_id ="
            + " (SELECT salle_parent_id FROM salles WHERE id = CAST(:source AS uuid))"
            + " WHERE id = CAST(:cible AS uuid) AND salle_parent_id = CAST(:source AS uuid)",
        ids);
    reassignerSousSallesSansCycle(cibleId, sourceId);

    // 7. Suppression de la salle source
    jdbc.update("DELETE FROM salles WHERE id = CAST(:source AS uuid)", ids);

    log.info(
        "reconciliation:nocturne — salles fusionnées par clé forte cible={} source={}",
        cibleId,
        sourceId);
    // Fusion automatique et destructive (suppression d'une salle établie) : jamais silencieuse,
    // contrairement au reste du cycle nocturne qui ne fait que journaliser un constat.
    logsAdminWorker.inserer(
        "info",
        "reconciliation-nocturne",
        "salles fusionnées automatiquement par clé forte (artiste+date+heure+dept) cible="
            + cibleId
            + " source="
            + sourceId,
        Map.of("salleCible", cibleId.toString(), "salleSource", sourceId.toString()));
  }

  /**
   * Réassigne à la cible chaque sous-salle de la source (hors cible elle-même, déjà traitée), sauf
   * celle dont la cible est déjà un descendant — la réassigner créerait un cycle {@code enfant →
   * cible → ... → enfant} (#1201). Une telle sous-salle est détachée (parent à {@code NULL})
   * plutôt que laissée pointer vers la source sur le point d'être supprimée, et signalée pour
   * revue manuelle — cas limite jamais rencontré en pratique mais non exclu par le seul cas direct
   * déjà géré à l'étape 6.
   */
  private void reassignerSousSallesSansCycle(UUID cibleId, UUID sourceId) {
    List<UUID> enfants =
        jdbc.query(
            "SELECT id FROM salles WHERE salle_parent_id = CAST(:source AS uuid) AND id !="
                + " CAST(:cible AS uuid)",
            Map.of("source", sourceId.toString(), "cible", cibleId.toString()),
            (rs, _) -> rs.getObject("id", UUID.class));

    for (UUID enfant : enfants) {
      if (SalleHierarchie.creeraitCycle(cibleId, enfant, this::lireSalleParentId)) {
        jdbc.update(
            "UPDATE salles SET salle_parent_id = NULL WHERE id = CAST(:id AS uuid)",
            Map.of("id", enfant.toString()));
        logsAdminWorker.inserer(
            "warn",
            "reconciliation-nocturne",
            "cycle salle_parent_id évité lors d'une fusion — sous-salle détachée enfant="
                + enfant
                + " cible="
                + cibleId,
            Map.of("enfant", enfant.toString(), "cible", cibleId.toString()));
      } else {
        jdbc.update(
            "UPDATE salles SET salle_parent_id = CAST(:cible AS uuid) WHERE id = CAST(:id AS"
                + " uuid)",
            Map.of("cible", cibleId.toString(), "id", enfant.toString()));
      }
    }
  }

  // findFirst() sur un flux contenant un unique élément null lève une NullPointerException
  // (Optional.of appelé en interne) — un accès direct à la liste évite ce piège pour une salle
  // racine sans parent.
  private UUID lireSalleParentId(UUID salleId) {
    List<UUID> resultat =
        jdbc.query(
            "SELECT salle_parent_id FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salleId.toString()),
            (rs, _) -> (UUID) rs.getObject("salle_parent_id"));
    return resultat.isEmpty() ? null : resultat.get(0);
  }

  private void dedupliquerSallesSourcesAvantFusion(UUID cibleId, UUID sourceId) {
    List<Map<String, Object>> conflits =
        jdbc.queryForList(
            """
            SELECT src.source AS source,
                   src.identifiant_externe AS id_source,
                   dst.identifiant_externe AS id_cible,
                   src.nb_zero_consecutifs AS sante_source,
                   dst.nb_zero_consecutifs AS sante_cible,
                   (src.suspect_depuis IS NOT NULL) AS suspect_source,
                   (dst.suspect_depuis IS NOT NULL) AS suspect_cible
            FROM salle_sources src
            JOIN salle_sources dst ON dst.source = src.source AND dst.salle_id = CAST(:cible AS uuid)
            WHERE src.salle_id = CAST(:source AS uuid)
            """,
            Map.of("cible", cibleId.toString(), "source", sourceId.toString()));

    for (Map<String, Object> conflit : conflits) {
      boolean garderSource = laSourceEstPlusSaine(conflit);
      Map<String, Object> paramsSuppression = new HashMap<>();
      paramsSuppression.put("source", conflit.get("source"));
      paramsSuppression.put("salleId", garderSource ? cibleId.toString() : sourceId.toString());
      paramsSuppression.put(
          "identifiantExterne", conflit.get(garderSource ? "id_cible" : "id_source"));
      jdbc.update(
          "DELETE FROM salle_sources WHERE salle_id = CAST(:salleId AS uuid) AND source ="
              + " :source AND identifiant_externe = :identifiantExterne",
          paramsSuppression);
    }
  }

  /**
   * Même comparateur que {@code ReferentielSallesService.laSourceEstPlusSaine} (module web) : une
   * ligne non suspecte l'emporte toujours sur une ligne suspecte, à égalité de statut la ligne
   * avec le moins d'échecs consécutifs l'emporte.
   *
   * @return {@code true} si la ligne côté source doit être conservée
   */
  private static boolean laSourceEstPlusSaine(Map<String, Object> conflit) {
    boolean suspectSource = Boolean.TRUE.equals(conflit.get("suspect_source"));
    boolean suspectCible = Boolean.TRUE.equals(conflit.get("suspect_cible"));
    if (suspectSource != suspectCible) return !suspectSource;
    long santeSource = ((Number) conflit.get("sante_source")).longValue();
    long santeCible = ((Number) conflit.get("sante_cible")).longValue();
    return santeSource <= santeCible;
  }
}

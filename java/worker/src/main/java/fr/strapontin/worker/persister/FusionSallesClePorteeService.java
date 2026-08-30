/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

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

/// Détecte et fusionne automatiquement, en cycle nocturne, des paires de salles déjà établies
/// (chacune avec ses propres concerts déjà résolus) qui désignent en réalité le même bâtiment
/// physique — repérées par un concert partageant exactement artiste+date+heure+dept entre les deux,
/// sans aucun rapport textuel nécessaire entre leurs noms.
///
/// Complète {@link ReconciliateurSalles#chercherSalleParCleForte}, qui n'agit qu'au moment de la
/// résolution d'un nouveau concert entrant : deux salles déjà établies sous des identités
/// distinctes (chacune avec ses propres {@code salle_sources}, éventuellement un {@code
/// wikidata_id}) n'étaient jamais reconsidérées après coup, laissant la scission s'accumuler à
/// chaque nouveau concert de la même série.
///
/// Porte le même gabarit de fusion que {@code ReferentielSallesService.fusionner} (module web, non
/// accessible depuis le worker) : dédoublonnage des {@code salle_sources} en conflit avant
/// réassignation, puis concerts, puis abonnements utilisateurs.
///
/// @since 2.8.4
/// @author Kwaite
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

  /// Détecte puis fusionne les paires de salles distinctes partageant un concert à venir identique
  /// (artiste+date+heure+dept). Une paire dont l'une des deux salles a déjà disparu (fusionnée plus
  /// tôt dans le même cycle) est ignorée silencieusement — elle est nécessairement redondante avec
  /// une fusion déjà effectuée.
  ///
  /// @return nombre de fusions effectuées
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

  /// Repère, parmi les concerts à venir, les paires de {@code salle_id} distincts partageant
  /// exactement le même artiste, la même date, la même heure et le même département — même clé que
  /// {@link ReconciliateurSalles#chercherSalleParCleForte}, appliquée ici rétroactivement à des
  /// salles déjà résolues chacune de leur côté plutôt qu'au moment de l'insertion.
  private List<PaireSalles> detecterPaires() {
    List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT DISTINCT c1.salle_id AS salle_a, c2.salle_id AS salle_b
            FROM concerts c1
            JOIN concerts c2
              ON c1.artiste_norm = c2.artiste_norm
             AND c1.date = c2.date
             AND c1.heure = c2.heure
             AND c1.dept = c2.dept
             AND c1.salle_id < c2.salle_id
            WHERE c1.salle_id IS NOT NULL AND c2.salle_id IS NOT NULL
              AND c1.heure IS NOT NULL AND c1.dept IS NOT NULL
              AND c1.date >= CURRENT_DATE
            """,
            Map.of());
    List<PaireSalles> paires = new ArrayList<>();
    for (var ligne : lignes) {
      paires.add(new PaireSalles((UUID) ligne.get("salle_a"), (UUID) ligne.get("salle_b")));
    }
    return paires;
  }

  /// Choisit la salle à conserver (cible) entre les deux membres de la paire : priorité au {@code
  /// wikidata_id} déjà connu (donnée la plus coûteuse à reconstituer), puis au nombre de concerts
  /// déjà rattachés (établissement le plus riche), puis à l'UUID le plus petit pour un résultat
  /// déterministe entre deux cycles.
  ///
  /// @return {@code [cible, source]}, ou {@code null} si l'une des deux salles n'existe plus
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

  @Transactional
  void fusionnerUnePaire(UUID cibleId, UUID sourceId) {
    Map<String, Object> ids = Map.of("cible", cibleId.toString(), "source", sourceId.toString());

    // 1. Dédoublonner les concerts en conflit (même artiste/date/heure déjà présent côté cible)
    jdbc.update(
        """
        DELETE FROM concerts c1
        USING concerts c2
        WHERE c1.salle_id = CAST(:source AS uuid)
          AND c2.salle_id = CAST(:cible AS uuid)
          AND c1.artiste_norm = c2.artiste_norm
          AND c1.date = c2.date
          AND c1.heure IS NOT DISTINCT FROM c2.heure
        """,
        ids);

    // 2. Réassigner les concerts restants
    jdbc.update(
        "UPDATE concerts SET salle_id = CAST(:cible AS uuid) WHERE salle_id = CAST(:source AS"
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

    // 6. Suppression de la salle source
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

  /// Même comparateur que {@code ReferentielSallesService.laSourceEstPlusSaine} (module web) : une
  /// ligne non suspecte l'emporte toujours sur une ligne suspecte, à égalité de statut la ligne
  /// avec le moins d'échecs consécutifs l'emporte.
  ///
  /// @return {@code true} si la ligne côté source doit être conservée
  private static boolean laSourceEstPlusSaine(Map<String, Object> conflit) {
    boolean suspectSource = Boolean.TRUE.equals(conflit.get("suspect_source"));
    boolean suspectCible = Boolean.TRUE.equals(conflit.get("suspect_cible"));
    if (suspectSource != suspectCible) return !suspectSource;
    long santeSource = ((Number) conflit.get("sante_source")).longValue();
    long santeCible = ((Number) conflit.get("sante_cible")).longValue();
    return santeSource <= santeCible;
  }
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Deux contrôles nocturnes *a posteriori* sur le référentiel des salles, complémentaires à {@link
/// CoherenceWikidataSalleService} et à {@link
/// fr.strapontin.worker.persister.FusionSallesClePorteeService} : ni l'un ni l'autre ne couvre la
/// contamination déjà consommée (résidu de fusion antérieur à un correctif) ou le doublon sans
/// wikidata_id ni concert partagé. Journalisation seule, jamais de fusion/scission automatique —
/// laissée à l'admin ({@code ReferentielSallesService}).
///
/// @since 2.8.8
/// @author Kwaite
@Service
public class CoherenceMultiSalleService {

  private static final Logger log = LoggerFactory.getLogger(CoherenceMultiSalleService.class);

  private static final String PREFIXE_INTERNAL = "internal:";

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;

  public CoherenceMultiSalleService(
      NamedParameterJdbcTemplate jdbc, LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
  }

  /// Compare, pour chaque salle portant au moins une source Eventim/Digitick synthétique ({@code
  /// internal:<nom>}), le nom extrait de cette source à {@code salles.nom_norm} via {@link
  /// CorrespondanceSalle#estDoublonSalle} — une mésentente signe un {@code salle_id} hébergeant
  /// probablement plusieurs lieux réels distincts (résidu d'une fusion antérieure à un correctif de
  /// dédoublonnage).
  ///
  /// @return nombre de salles nouvellement signalées
  public int detecterContamination() {
    List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT ss.salle_id AS salle_id, ss.identifiant_externe AS identifiant,
                   s.nom_norm AS nom_norm, s.contamination_signalee AS signalee
            FROM salle_sources ss
            JOIN salles s ON s.id = ss.salle_id
            WHERE ss.identifiant_externe LIKE :prefixe
            """,
            Map.of("prefixe", PREFIXE_INTERNAL + "%"));
    if (lignes.isEmpty()) return 0;

    Map<UUID, List<String>> nomsExtraitsParSalle = new HashMap<>();
    Map<UUID, String> nomNormParSalle = new HashMap<>();
    Map<UUID, String> signaleeParSalle = new HashMap<>();
    for (var ligne : lignes) {
      UUID salleId = (UUID) ligne.get("salle_id");
      String identifiant = (String) ligne.get("identifiant");
      nomsExtraitsParSalle
          .computeIfAbsent(salleId, _ -> new ArrayList<>())
          .add(identifiant.substring(PREFIXE_INTERNAL.length()));
      nomNormParSalle.put(salleId, (String) ligne.get("nom_norm"));
      signaleeParSalle.put(salleId, (String) ligne.get("signalee"));
    }

    int signalees = 0;
    for (var entree : nomsExtraitsParSalle.entrySet()) {
      UUID salleId = entree.getKey();
      String nomNormSalle = nomNormParSalle.get(salleId);
      List<String> mesententes =
          entree.getValue().stream()
              .filter(nomExtrait -> !CorrespondanceSalle.estDoublonSalle(nomExtrait, nomNormSalle))
              .distinct()
              .sorted()
              .toList();
      String signatureActuelle = mesententes.isEmpty() ? null : String.join("|", mesententes);
      if (Objects.equals(signatureActuelle, signaleeParSalle.get(salleId))) continue;

      memoriserContaminationSignalee(salleId, signatureActuelle);
      if (signatureActuelle == null) continue;
      signalees++;
      logsAdminWorker.inserer(
          "warn",
          "coherence-salle",
          "salle potentiellement multi-lieux — source(s) interne(s) divergente(s) du nom salleId="
              + salleId
              + " mesententes="
              + signatureActuelle,
          Map.of("salleId", salleId.toString(), "mesententes", signatureActuelle));
    }
    log.info(
        "coherence:multi-salle:contamination candidats={} signalees={}",
        nomsExtraitsParSalle.size(),
        signalees);
    return signalees;
  }

  private void memoriserContaminationSignalee(UUID salleId, String signature) {
    Map<String, Object> params = new HashMap<>();
    params.put("signature", signature);
    params.put("id", salleId.toString());
    jdbc.update(
        "UPDATE salles SET contamination_signalee = :signature WHERE id = CAST(:id AS uuid)",
        params);
  }

  /// Balayage pairwise de toutes les salles par containment de tokens de {@code nom_norm} et
  /// département compatible, sans exiger de {@code wikidata_id} ni de concert partagé — {@link
  /// CorrespondanceSalle#estDoublonSalle} est trop strict pour ce cas précis (son propre critère de
  /// containment exige un ratio de longueur minimal de 0.5), une source abrégeant fortement un nom
  /// déjà long ne le satisfait pas.
  ///
  /// @return nombre de paires nouvellement signalées
  public int detecterDoublons() {
    List<Map<String, Object>> salles =
        jdbc.queryForList(
            "SELECT id, nom_norm, dept, doublon_signale FROM salles ORDER BY id", Map.of());

    int signalees = 0;
    for (int i = 0; i < salles.size(); i++) {
      for (int j = i + 1; j < salles.size(); j++) {
        var a = salles.get(i);
        var b = salles.get(j);
        String deptA = (String) a.get("dept");
        String deptB = (String) b.get("dept");
        if (deptA != null && deptB != null && !deptA.equals(deptB)) continue;
        if (!nomContenuDansLAutre((String) a.get("nom_norm"), (String) b.get("nom_norm"))) {
          continue;
        }

        UUID idA = (UUID) a.get("id");
        UUID idB = (UUID) b.get("id");
        String dejaSignale = (String) a.get("doublon_signale");
        if (idB.toString().equals(dejaSignale)) continue;

        memoriserDoublonSignale(idA, idB);
        signalees++;
        logsAdminWorker.inserer(
            "warn",
            "coherence-salle",
            "doublon de salle potentiel sans wikidata_id ni concert partagé salleA="
                + idA
                + " salleB="
                + idB,
            Map.of("salleA", idA.toString(), "salleB", idB.toString()));
      }
    }
    log.info("coherence:multi-salle:doublon salles={} signalees={}", salles.size(), signalees);
    return signalees;
  }

  // Longueur minimale pour éviter un containment trivial sur un nom déjà très court.
  private static final int LONGUEUR_MIN_CONTAINMENT_DOUBLON = 4;

  // Containment par token complet plutôt que sous-chaîne brute (#1203) : un containment de
  // caractères validait à tort un article court ("les") trivialement présent dans un token du nom
  // long ("lessaion") sans aucun rapport sémantique réel — même classe de faux positif que
  // CorrespondanceSalle#estContainmentTokensPartiel.
  private static boolean nomContenuDansLAutre(String nomNormA, String nomNormB) {
    String court = nomNormA.length() <= nomNormB.length() ? nomNormA : nomNormB;
    String plusLong = nomNormA.length() <= nomNormB.length() ? nomNormB : nomNormA;
    if (court.length() < LONGUEUR_MIN_CONTAINMENT_DOUBLON) return false;
    Set<String> tokensCourt = CorrespondanceSalle.tokensSignificatifs(court);
    Set<String> tokensLong = CorrespondanceSalle.tokensSignificatifs(plusLong);
    return !tokensCourt.isEmpty() && tokensLong.containsAll(tokensCourt);
  }

  private void memoriserDoublonSignale(UUID idA, UUID idB) {
    jdbc.update(
        "UPDATE salles SET doublon_signale = :idB WHERE id = CAST(:idA AS uuid)",
        new MapSqlParameterSource()
            .addValue("idA", idA.toString())
            .addValue("idB", idB.toString()));
  }
}

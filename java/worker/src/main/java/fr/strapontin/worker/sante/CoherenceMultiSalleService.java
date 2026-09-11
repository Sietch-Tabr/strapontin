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

/**
 * Deux contrôles nocturnes *a posteriori* sur le référentiel des salles, complémentaires à {@link
 * CoherenceWikidataSalleService} et à {@link
 * fr.strapontin.worker.persister.FusionSallesClePorteeService} : ni l'un ni l'autre ne couvre la
 * contamination déjà consommée (résidu de fusion antérieur à un correctif) ou le doublon sans
 * wikidata_id ni concert partagé. Journalisation seule, jamais de fusion/scission automatique —
 * laissée à l'admin ({@code ReferentielSallesService}).
 *
 * @since 2.8.8
 * @author Kwaite
 */
@Service
public class CoherenceMultiSalleService {

  private static final Logger LOG = LoggerFactory.getLogger(CoherenceMultiSalleService.class);

  private static final String PREFIXE_INTERNAL = "internal:";

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;

  /**
   * @param jdbc accès JDBC
   * @param logsAdminWorker journalisation des incohérences détectées
   */
  public CoherenceMultiSalleService(
      final NamedParameterJdbcTemplate jdbc, final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Compare, pour chaque salle portant au moins une source Eventim/Digitick synthétique ({@code
   * internal:<nom>}), le nom extrait de cette source à {@code salles.nom_norm} via {@link
   * #estContaminationReelle} — une mésentente signe un {@code salle_id} hébergeant probablement
   * plusieurs lieux réels distincts (résidu d'une fusion antérieure à un correctif de
   * dédoublonnage), à l'exclusion des identifiants source qui ne sont que le nom de la salle
   * complété d'une ville ou d'un sigle.
   *
   * @return nombre de salles nouvellement signalées
   */
  public int detecterContamination() {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT ss.salle_id AS salle_id, ss.identifiant_externe AS identifiant,
                   s.nom_norm AS nom_norm, s.contamination_signalee AS signalee
            FROM salle_sources ss
            JOIN salles s ON s.id = ss.salle_id
            WHERE ss.identifiant_externe LIKE :prefixe
            """,
            Map.of("prefixe", PREFIXE_INTERNAL + "%"));
    if (lignes.isEmpty()) {
      return 0;
    }

    final Map<UUID, List<String>> nomsExtraitsParSalle = new HashMap<>();
    final Map<UUID, String> nomNormParSalle = new HashMap<>();
    final Map<UUID, String> signaleeParSalle = new HashMap<>();
    for (final Map<String, Object> ligne : lignes) {
      final UUID salleId = (UUID) ligne.get("salle_id");
      final String identifiant = (String) ligne.get("identifiant");
      nomsExtraitsParSalle
          .computeIfAbsent(salleId, _ -> new ArrayList<>())
          .add(identifiant.substring(PREFIXE_INTERNAL.length()));
      nomNormParSalle.put(salleId, (String) ligne.get("nom_norm"));
      signaleeParSalle.put(salleId, (String) ligne.get("signalee"));
    }

    int signalees = 0;
    for (final Map.Entry<UUID, List<String>> entree : nomsExtraitsParSalle.entrySet()) {
      final UUID salleId = entree.getKey();
      final String nomNormSalle = nomNormParSalle.get(salleId);
      final List<String> mesententes =
          entree.getValue().stream()
              .filter(nomExtrait -> estContaminationReelle(nomExtrait, nomNormSalle))
              .distinct()
              .sorted()
              .toList();
      final String signatureActuelle = mesententes.isEmpty() ? null : String.join("|", mesententes);
      if (Objects.equals(signatureActuelle, signaleeParSalle.get(salleId))) {
        continue;
      }

      memoriserContaminationSignalee(salleId, signatureActuelle);
      if (signatureActuelle == null) {
        continue;
      }
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
    LOG.info(
        "coherence:multi-salle:contamination candidats={} signalees={}",
        nomsExtraitsParSalle.size(),
        signalees);
    return signalees;
  }

  private void memoriserContaminationSignalee(final UUID salleId, final String signature) {
    final Map<String, Object> params = new HashMap<>();
    params.put("signature", signature);
    params.put("id", salleId.toString());
    jdbc.update(
        "UPDATE salles SET contamination_signalee = :signature WHERE id = CAST(:id AS uuid)",
        params);
  }

  // Longueur minimale du sigle (sans espace) pour éviter un match trivial sur une seule lettre.
  private static final int LONGUEUR_MIN_SIGLE = 2;

  /**
   * Un identifiant source synthétique n'est pas une contamination réelle quand il n'est que le nom
   * de la salle complété d'une ville ({@link #nomContenuDansLAutre}, ex. "6mic" / "6mic aix en
   * provence") ou un sigle formé de ses initiales ({@link #estSigleDe}, ex. "ed n" / "espace
   * dollfus noack") — {@link CorrespondanceSalle#estDoublonSalle} seul rejette mécaniquement ces
   * deux cas dès qu'un côté est bien plus long ou bien plus court que l'autre.
   */
  private static boolean estContaminationReelle(
      final String nomExtrait, final String nomNormSalle) {
    if (CorrespondanceSalle.estDoublonSalle(nomExtrait, nomNormSalle)) {
      return false;
    }
    if (nomContenuDansLAutre(nomExtrait, nomNormSalle)) {
      return false;
    }
    return !estSigleDe(nomExtrait, nomNormSalle);
  }

  /**
   * Vérifie si la plus courte des deux chaînes est le sigle de la plus longue — les initiales de
   * chaque mot de la plus longue, concaténées sans espace, égalent exactement la plus courte sans
   * espace (ex. "ed n" pour "espace dollfus noack" → initiales e, d, n).
   */
  private static boolean estSigleDe(final String a, final String b) {
    final String court = a.length() <= b.length() ? a : b;
    final String plusLong = a.length() <= b.length() ? b : a;
    final String sigleObserve = court.replace(" ", "");
    if (sigleObserve.length() < LONGUEUR_MIN_SIGLE) {
      return false;
    }

    final String[] mots = plusLong.split("\\s+");
    if (mots.length != sigleObserve.length()) {
      return false;
    }

    final StringBuilder initiales = new StringBuilder();
    for (final String mot : mots) {
      if (mot.isEmpty()) {
        return false;
      }
      initiales.append(mot.charAt(0));
    }
    return initiales.toString().equals(sigleObserve);
  }

  /**
   * Balayage pairwise de toutes les salles par containment de tokens de {@code nom_norm} et
   * département compatible, sans exiger de {@code wikidata_id} ni de concert partagé — {@link
   * CorrespondanceSalle#estDoublonSalle} est trop strict pour ce cas précis (son propre critère de
   * containment exige un ratio de longueur minimal de 0.5), une source abrégeant fortement un nom
   * déjà long ne le satisfait pas. Exclut les paires déjà reliées par {@code salle_parent_id}
   * (complexe/sous-salle) — leur nom qualifié ("X - Y") satisfait presque toujours ce containment
   * par construction, sans rapport avec un résidu de fusion antérieur.
   *
   * @return nombre de paires nouvellement signalées
   */
  public int detecterDoublons() {
    final List<Map<String, Object>> salles =
        jdbc.queryForList(
            "SELECT id, nom_norm, dept, doublon_signale, salle_parent_id FROM salles ORDER BY id",
            Map.of());

    int signalees = 0;
    for (int i = 0; i < salles.size(); i++) {
      for (int j = i + 1; j < salles.size(); j++) {
        final Map<String, Object> a = salles.get(i);
        final Map<String, Object> b = salles.get(j);
        final UUID idA = (UUID) a.get("id");
        final UUID idB = (UUID) b.get("id");
        final UUID parentA = (UUID) a.get("salle_parent_id");
        final UUID parentB = (UUID) b.get("salle_parent_id");
        // Un complexe et sa sous-salle déjà reliée ne sont jamais des doublons — leur nom qualifié
        // ("X - Y") satisferait sinon le containment de tokens ci-dessous par construction.
        if (idA.equals(parentB) || idB.equals(parentA)) {
          continue;
        }
        final String deptA = (String) a.get("dept");
        final String deptB = (String) b.get("dept");
        if (deptA != null && deptB != null && !deptA.equals(deptB)) {
          continue;
        }
        if (!nomContenuDansLAutre((String) a.get("nom_norm"), (String) b.get("nom_norm"))) {
          continue;
        }

        final String dejaSignale = (String) a.get("doublon_signale");
        if (idB.toString().equals(dejaSignale)) {
          continue;
        }

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
    LOG.info("coherence:multi-salle:doublon salles={} signalees={}", salles.size(), signalees);
    return signalees;
  }

  // Longueur minimale pour éviter un containment trivial sur un nom déjà très court.
  private static final int LONGUEUR_MIN_CONTAINMENT_DOUBLON = 4;

  // Containment par token complet plutôt que sous-chaîne brute (#1203) : un containment de
  // caractères validait à tort un article court ("les") trivialement présent dans un token du nom
  // long ("lessaion") sans aucun rapport sémantique réel — même classe de faux positif que
  // CorrespondanceSalle#estContainmentTokensPartiel.
  private static boolean nomContenuDansLAutre(final String nomNormA, final String nomNormB) {
    final String court = nomNormA.length() <= nomNormB.length() ? nomNormA : nomNormB;
    final String plusLong = nomNormA.length() <= nomNormB.length() ? nomNormB : nomNormA;
    if (court.length() < LONGUEUR_MIN_CONTAINMENT_DOUBLON) {
      return false;
    }
    final Set<String> tokensCourt = CorrespondanceSalle.tokensSignificatifs(court);
    final Set<String> tokensLong = CorrespondanceSalle.tokensSignificatifs(plusLong);
    return !tokensCourt.isEmpty() && tokensLong.containsAll(tokensCourt);
  }

  private void memoriserDoublonSignale(final UUID idA, final UUID idB) {
    jdbc.update(
        "UPDATE salles SET doublon_signale = :idB WHERE id = CAST(:idA AS uuid)",
        new MapSqlParameterSource()
            .addValue("idA", idA.toString())
            .addValue("idB", idB.toString()));
  }
}

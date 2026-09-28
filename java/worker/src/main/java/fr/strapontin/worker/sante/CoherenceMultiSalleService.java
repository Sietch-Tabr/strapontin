/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.DistanceGeo;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Deux contrôles nocturnes a posteriori sur le référentiel des salles,
 * complémentaires à {@link CoherenceWikidataSalleService} et {@link
 * fr.strapontin.worker.persister.FusionSallesClePorteeService} —
 * journalisation seule (détail : CLAUDE.md, Hiérarchie des salles).
 * @since 2.8.8
 * @author Kwaite
 */
@Service
public class CoherenceMultiSalleService {

  private static final Logger LOG = LoggerFactory.getLogger(CoherenceMultiSalleService.class);

  private static final String PREFIXE_INTERNAL = "internal:";

  // Longueur minimale du sigle (sans espace) pour éviter un match trivial sur
  // une seule lettre.
  private static final int LONGUEUR_MIN_SIGLE = 2;

  // Longueur minimale pour éviter un containment trivial sur un nom déjà très
  // court.
  private static final int LONGUEUR_MIN_CONTAINMENT_DOUBLON = 4;

  // Même seuil que la fusion par clé forte : au-delà, deux lieux distincts.
  private static final double DISTANCE_MEME_LIEU_METRES = 500;

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;
  private final DetectionDoublonsSalles detectionDoublons;

  /**
   * @param jdbc accès JDBC
   * @param logsAdminWorker journalisation des incohérences détectées
   */
  public CoherenceMultiSalleService(
      final NamedParameterJdbcTemplate jdbc, final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
    this.detectionDoublons = new DetectionDoublonsSalles(jdbc, logsAdminWorker);
  }

  /**
   * Compare, pour chaque salle avec source Eventim synthétique,
   * le nom extrait à {@code salles.nom_norm} (détail : CLAUDE.md,
   * Hiérarchie des salles).
   * @return nombre de salles nouvellement signalées
   */
  public int detecterContamination() {
    final List<Map<String, Object>> lignes = chargerSourcesInternes();
    if (lignes.isEmpty()) {
      leverContaminationsSansSource();
      return 0;
    }

    final DonneesSourcesInternes donnees = regrouperParSalle(lignes);
    int signalees = 0;
    for (final Map.Entry<UUID, List<String>> entree : donnees.nomsExtraitsParSalle().entrySet()) {
      final UUID salleId = entree.getKey();
      if (evaluerContaminationSalle(
          salleId,
          entree.getValue(),
          donnees.nomNormParSalle().get(salleId),
          donnees.signaleeParSalle().get(salleId))) {
        signalees++;
      }
    }
    leverContaminationsSansSource();
    LOG.info(
        "coherence:multi-salle:contamination candidats={} signalees={}",
        donnees.nomsExtraitsParSalle().size(),
        signalees);
    return signalees;
  }

  // Salle qui n'a plus aucune source interne à examiner (mapping supprimé ou
  // accepté par un admin) : sa signature ne serait sinon jamais levée.
  private void leverContaminationsSansSource() {
    jdbc.update(
        """
        UPDATE salles SET contamination_signalee = NULL
        WHERE contamination_signalee IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM salle_sources ss
              WHERE ss.salle_id = salles.id AND ss.identifiant_externe LIKE :prefixe
                AND ss.contamination_acceptee_le IS NULL)
        """,
        Map.of("prefixe", PREFIXE_INTERNAL + "%"));
  }

  private List<Map<String, Object>> chargerSourcesInternes() {
    return jdbc.queryForList(
        """
        SELECT ss.salle_id AS salle_id, ss.identifiant_externe AS identifiant,
               s.nom_norm AS nom_norm, s.contamination_signalee AS signalee,
               ss.latitude AS lat_source, ss.longitude AS lon_source,
               s.latitude AS lat_salle, s.longitude AS lon_salle
        FROM salle_sources ss
        JOIN salles s ON s.id = ss.salle_id
        WHERE ss.identifiant_externe LIKE :prefixe AND ss.contamination_acceptee_le IS NULL
        """,
        Map.of("prefixe", PREFIXE_INTERNAL + "%"));
  }

  private record DonneesSourcesInternes(
      Map<UUID, List<String>> nomsExtraitsParSalle,
      Map<UUID, String> nomNormParSalle,
      Map<UUID, String> signaleeParSalle) {}

  private DonneesSourcesInternes regrouperParSalle(final List<Map<String, Object>> lignes) {
    final Map<UUID, List<String>> nomsExtraitsParSalle = new HashMap<>();
    final Map<UUID, String> nomNormParSalle = new HashMap<>();
    final Map<UUID, String> signaleeParSalle = new HashMap<>();
    for (final Map<String, Object> ligne : lignes) {
      final UUID salleId = (UUID) ligne.get("salle_id");
      final String identifiant = (String) ligne.get("identifiant");
      final List<String> noms =
          nomsExtraitsParSalle.computeIfAbsent(salleId, _ -> new ArrayList<>());
      // La source situe elle-même le lieu à la position de la salle : même lieu,
      // quel que soit le nom d'usage ou l'abréviation employés.
      if (!estMemePosition(ligne)) {
        noms.add(identifiant.substring(PREFIXE_INTERNAL.length()));
      }
      nomNormParSalle.put(salleId, (String) ligne.get("nom_norm"));
      signaleeParSalle.put(salleId, (String) ligne.get("signalee"));
    }
    return new DonneesSourcesInternes(nomsExtraitsParSalle, nomNormParSalle, signaleeParSalle);
  }

  private static boolean estMemePosition(final Map<String, Object> ligne) {
    return DistanceGeo.connuesEtProches(
        (Double) ligne.get("lat_source"),
        (Double) ligne.get("lon_source"),
        (Double) ligne.get("lat_salle"),
        (Double) ligne.get("lon_salle"),
        DISTANCE_MEME_LIEU_METRES);
  }

  /**
   * Évalue les mésententes nom/source d'une salle et met à jour la signature de
   * contamination déjà signalée si elle a changé.
   *
   * @return {@code true} si une nouvelle contamination a été signalée
   */
  private boolean evaluerContaminationSalle(
      final UUID salleId,
      final List<String> nomsExtraits,
      final String nomNormSalle,
      final String signaleePrecedente) {
    final List<String> mesententes =
        nomsExtraits.stream()
            .filter(nomExtrait -> estContaminationReelle(nomExtrait, nomNormSalle))
            .distinct()
            .sorted()
            .toList();
    final String signatureActuelle = mesententes.isEmpty() ? null : String.join("|", mesententes);
    if (Objects.equals(signatureActuelle, signaleePrecedente)) {
      return false;
    }

    memoriserContaminationSignalee(salleId, signatureActuelle);
    if (signatureActuelle == null) {
      return false;
    }
    logsAdminWorker.inserer(
        "warn",
        "coherence-salle",
        "salle potentiellement multi-lieux — source(s) interne(s) divergente(s) du nom salleId="
            + salleId
            + " mesententes="
            + signatureActuelle,
        Map.of("salleId", salleId.toString(), "mesententes", signatureActuelle));
    return true;
  }

  private void memoriserContaminationSignalee(final UUID salleId, final String signature) {
    final Map<String, Object> params = new HashMap<>();
    params.put("signature", signature);
    params.put("id", salleId.toString());
    jdbc.update(
        "UPDATE salles SET contamination_signalee = :signature WHERE id = CAST(:id AS uuid)",
        params);
  }

  /**
   * Un identifiant source synthétique n'est pas une contamination réelle
   * quand il n'est que le nom complété d'une ville ou un sigle de ses
   * initiales (détail : CLAUDE.md, Hiérarchie des salles).
   */
  private static boolean estContaminationReelle(
      final String nomExtrait, final String nomNormSalle) {
    if (CorrespondanceSalle.estDoublonSalle(nomExtrait, nomNormSalle)) {
      return false;
    }
    return !nomContenuDansLAutre(nomExtrait, nomNormSalle)
        && !estSigleDe(nomExtrait, nomNormSalle)
        && !estApostropheElideeContenue(nomExtrait, nomNormSalle);
  }

  /**
   * Généralise {@link #nomContenuDansLAutre} à un nom commençant par une
   * apostrophe élidée (ex. "L'Astrada" → {@code nom_norm} "l astrada") face
   * à un identifiant source collant le mot sans apostrophe (ex. "lastrada
   * marciac") — sans fusion, le token d'une seule lettre "l" est filtré,
   * aucun rapport n'est détecté malgré l'évidence.
   */
  private static boolean estApostropheElideeContenue(final String nomNormA, final String nomNormB) {
    return contientToken(fusionnerApostropheElidee(nomNormA), nomNormB)
        || contientToken(fusionnerApostropheElidee(nomNormB), nomNormA);
  }

  // article élidé fusionné avec le mot suivant ("l astrada" -> "lastrada") —
  // le token d'une seule lettre est systématiquement filtré en amont.
  private static String fusionnerApostropheElidee(final String nomNorm) {
    return nomNorm.replaceFirst("^([a-z]) ", "$1");
  }

  private static boolean contientToken(final String tokenFusionne, final String nomNormAutre) {
    return !tokenFusionne.isEmpty()
        && !tokenFusionne.contains(" ")
        && tokenFusionne.length() >= LONGUEUR_MIN_CONTAINMENT_DOUBLON
        && nomNormAutre.contains(tokenFusionne);
  }

  /**
   * Vérifie si la plus courte des deux chaînes est le sigle de la plus longue —
   * les initiales de chaque mot de la plus longue, concaténées sans espace,
   * égalent exactement la plus courte sans espace (ex. "ed n" pour "espace
   * dollfus noack" → initiales e, d, n).
   */
  private static boolean estSigleDe(final String nomA, final String nomB) {
    final String court = nomA.length() <= nomB.length() ? nomA : nomB;
    final String sigleObserve = court.replace(" ", "");
    if (sigleObserve.length() < LONGUEUR_MIN_SIGLE) {
      return false;
    }

    final String plusLong = nomA.length() <= nomB.length() ? nomB : nomA;
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
   * Balayage pairwise des doublons potentiels (détail : {@link
   * DetectionDoublonsSalles}).
   * @return nombre de paires nouvellement signalées
   */
  public int detecterDoublons() {
    return detectionDoublons.detecter();
  }

  // Containment par token complet plutôt que sous-chaîne brute (#1203) : un
  // containment de caractères validait à tort un article court ("les")
  // trivialement présent dans un token du nom long ("lessaion") sans aucun
  // rapport sémantique réel — même classe de faux positif que
  // CorrespondanceSalle#estContainmentTokensPartiel.
  /* package-private : partagé avec DetectionDoublonsSalles (même package). */
  static boolean nomContenuDansLAutre(final String nomNormA, final String nomNormB) {
    final String court = nomNormA.length() <= nomNormB.length() ? nomNormA : nomNormB;
    if (court.length() < LONGUEUR_MIN_CONTAINMENT_DOUBLON) {
      return false;
    }
    final String plusLong = nomNormA.length() <= nomNormB.length() ? nomNormB : nomNormA;
    final Set<String> tokensCourt = CorrespondanceSalle.tokensSignificatifs(court);
    final Set<String> tokensLong = CorrespondanceSalle.tokensSignificatifs(plusLong);
    return !tokensCourt.isEmpty() && tokensLong.containsAll(tokensCourt);
  }
}

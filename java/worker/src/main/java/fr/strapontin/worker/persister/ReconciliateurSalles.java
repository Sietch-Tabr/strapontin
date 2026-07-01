/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.Normalisation;
import java.sql.Types;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service de réconciliation des noms de salle entre fetches successifs.
///
/// <p>Deux passes de réconciliation : intra-lot (variantes dans le même fetch) puis
/// contre la base (variantes entre deux fetches). Utilise {@link #estDoublonSalle}
/// pour déterminer si deux noms désignent la même salle.
///
/// <p>Gère également l'upsert de nouvelles salles et le rattachement des salles
/// par {@code nom_norm} exact pour les concerts sans {@code salleSourceId}.
///
/// @since 2.5.7
/// @author Kwaite
@Service
public class ReconciliateurSalles {

  private static final Logger log = LoggerFactory.getLogger(ReconciliateurSalles.class);

  // Seuils Levenshtein ≥ 0.75 + containment ratio court/long ≥ 0.5.
  // 0.75 évite les faux positifs entre villes ("zenith bordeaux"/"zenith caen" sim=0.61 → false)
  // tout en couvrant les variantes légitimes ("l olympia"/"olympia" sim=0.78 → true).
  private static final double SEUIL_SIMILARITE_SALLE = 0.75;
  private static final int LONGUEUR_MIN_CONTAINMENT_SALLE = 4;
  private static final double RATIO_MIN_CONTAINMENT_SALLE = 0.5;
  // Longueur minimale pour inclure un token dans le calcul Jaccard (filtre "le", "de", "la"…).
  static final int LONGUEUR_MIN_TOKEN_JACCARD = 3;
  private static final double SEUIL_JACCARD_SALLE = 0.5;
  // Critère pivot : token commun ≥ LONGUEUR_MIN_PIVOT + Jaccard > SEUIL_JACCARD_PIVOT.
  // Couvre les variantes Eventim "grand kursaal"/"kursaal besancon" (Jaccard=0.33, pivot≥7).
  // Requiert le filtre dept dans chercherSalleParTokens pour éviter les faux positifs homonymes.
  private static final int LONGUEUR_MIN_PIVOT = 7;
  private static final double SEUIL_JACCARD_PIVOT = 0.3;

  private final NamedParameterJdbcTemplate jdbc;

  /// @param jdbc template JDBC injecté par Spring
  public ReconciliateurSalles(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Vérifie si deux chaînes normalisées désignent la même entité via Levenshtein ou containment.
  ///
  /// @param a                      première chaîne normalisée
  /// @param b                      deuxième chaîne normalisée
  /// @param seuilSimilarite        seuil de similarité Levenshtein (0 à 1)
  /// @param longueurMinContainment longueur minimale de la chaîne courte pour containment
  /// @param ratioMinContainment    ratio minimal court/long pour containment
  /// @return {@code true} si les deux chaînes sont considérées identiques
  static boolean estDoublonTexte(
      String a,
      String b,
      double seuilSimilarite,
      int longueurMinContainment,
      double ratioMinContainment) {
    if (a.equals(b)) return true;
    if (Normalisation.similarite(a, b) >= seuilSimilarite) return true;
    String court = a.length() <= b.length() ? a : b;
    String plusLong = a.length() <= b.length() ? b : a;
    double ratio = (double) court.length() / plusLong.length();
    return court.length() >= longueurMinContainment
        && ratio >= ratioMinContainment
        && plusLong.contains(court);
  }

  static Set<String> tokensSignificatifs(String s) {
    Set<String> tokens = new HashSet<>(Arrays.asList(s.split("\\s+")));
    tokens.removeIf(t -> t.length() < LONGUEUR_MIN_TOKEN_JACCARD);
    return tokens;
  }

  /// Calcule le coefficient de Jaccard sur les tokens des deux chaînes.
  ///
  /// <p>Seuls les tokens d'au moins {@value #LONGUEUR_MIN_TOKEN_JACCARD} caractères sont pris en
  /// compte (filtre les articles "le", "de", "la"…). Retourne 1.0 si les deux unions sont vides.
  ///
  /// @param a première chaîne normalisée
  /// @param b deuxième chaîne normalisée
  /// @return coefficient de Jaccard entre 0.0 et 1.0
  public static double jaccardTokens(String a, String b) {
    Set<String> tokensA = tokensSignificatifs(a);
    Set<String> tokensB = tokensSignificatifs(b);
    if (tokensA.isEmpty() && tokensB.isEmpty()) return 1.0;
    Set<String> union = new HashSet<>(tokensA);
    union.addAll(tokensB);
    if (union.isEmpty()) return 0.0;
    Set<String> intersection = new HashSet<>(tokensA);
    intersection.retainAll(tokensB);
    return (double) intersection.size() / union.size();
  }

  /// Vérifie si deux noms de salle normalisés désignent la même salle.
  ///
  /// <p>Trois critères en union :
  /// <ol>
  ///   <li>Levenshtein ≥ 0.75 ou containment (via {@link #estDoublonTexte})</li>
  ///   <li>Jaccard tokens strict {@code > 0.5} — insensible à l'ordre des mots</li>
  ///   <li>Token pivot — token commun ≥ {@value #LONGUEUR_MIN_PIVOT} chars et Jaccard
  ///       {@code > }{@value #SEUIL_JACCARD_PIVOT}. Requiert filtre {@code dept} dans
  ///       {@link #chercherSalleParTokens} pour éviter les faux positifs inter-villes.</li>
  /// </ol>
  ///
  /// @param a premier nom normalisé
  /// @param b deuxième nom normalisé
  /// @return {@code true} si les salles sont considérées identiques
  public static boolean estDoublonSalle(String a, String b) {
    if (estDoublonTexte(
        a, b, SEUIL_SIMILARITE_SALLE, LONGUEUR_MIN_CONTAINMENT_SALLE, RATIO_MIN_CONTAINMENT_SALLE))
      return true;
    Set<String> tokA = tokensSignificatifs(a);
    Set<String> tokB = tokensSignificatifs(b);
    Set<String> intersection = new HashSet<>(tokA);
    intersection.retainAll(tokB);
    Set<String> union = new HashSet<>(tokA);
    union.addAll(tokB);
    double jaccard =
        union.isEmpty()
            ? (tokA.isEmpty() && tokB.isEmpty() ? 1.0 : 0.0)
            : (double) intersection.size() / union.size();
    if (jaccard > SEUIL_JACCARD_SALLE) return true;
    // Critère pivot : requiert ≥ 2 tokens dans chaque nom pour éviter les faux positifs
    // de type "galaxie" (1 token) vs "le galaxie amneville".
    return tokA.size() >= 2
        && tokB.size() >= 2
        && jaccard > SEUIL_JACCARD_PIVOT
        && intersection.stream().anyMatch(t -> t.length() >= LONGUEUR_MIN_PIVOT);
  }

  /// Calcule un score de qualité pour choisir le meilleur nom de salle.
  ///
  /// @param salle nom brut de la salle
  /// @return score (diacritiques favorisés, casse mixte favorisée)
  static int scoreSalle(String salle) {
    int diacr = Normalizer.normalize(salle, Normalizer.Form.NFD).length() - salle.length();
    int casseMixte = !salle.equals(salle.toUpperCase()) ? 1 : 0;
    return diacr + casseMixte;
  }

  /// Résout les chaînes de remplacement transitives : si A→B et B→C, met à jour A→C.
  ///
  /// @param remplacements table de remplacement à résoudre sur place
  private static void resoudreChaines(Map<String, String> remplacements) {
    for (String cle : new ArrayList<>(remplacements.keySet())) {
      String valeur = cle;
      while (remplacements.containsKey(valeur) && !remplacements.get(valeur).equals(valeur)) {
        valeur = remplacements.get(valeur);
      }
      remplacements.put(cle, valeur);
    }
  }

  /// Réconcilie les variations mineures de nommage de salle dans un lot et contre la base.
  ///
  /// <p>Filtre temporel : seuls les concerts à partir de 7 jours avant aujourd'hui sont
  /// chargés depuis la base, évitant de ramener des milliers de concerts passés en mémoire.
  ///
  /// @param lot valeurs de concerts à réconcilier
  /// @return lot avec {@code salleNorm} canoniques
  List<ValeurConcert> reconcilierSalles(List<ValeurConcert> lot) {
    Map<String, String> remplacementsIntraLot = construireRemplacementsIntraLot(lot);
    List<ValeurConcert> lotApresIntraLot =
        remplacementsIntraLot.isEmpty()
            ? lot
            : lot.stream().map(c -> appliquerRemplacementSalle(c, remplacementsIntraLot)).toList();

    Set<String> artistesNorms =
        new HashSet<>(lotApresIntraLot.stream().map(ValeurConcert::artisteNorm).toList());
    if (artistesNorms.isEmpty()) return lotApresIntraLot;

    List<Map<String, Object>> existants =
        jdbc.queryForList(
            "SELECT artiste_norm, date::text AS date, salle_norm FROM concerts"
                + " WHERE artiste_norm IN (:artistesNorms)"
                + " AND date >= (CURRENT_DATE - INTERVAL '7 days')",
            Map.of("artistesNorms", artistesNorms));

    Map<String, List<String>> indexExistants = new HashMap<>();
    for (Map<String, Object> e : existants) {
      String cle = e.get("artiste_norm") + "|" + e.get("date");
      indexExistants.computeIfAbsent(cle, _ -> new ArrayList<>()).add((String) e.get("salle_norm"));
    }

    return reconcilierContreBase(lotApresIntraLot, indexExistants);
  }

  private Map<String, String> construireRemplacementsIntraLot(List<ValeurConcert> lot) {
    Map<String, String> remplacements = new HashMap<>();
    Map<String, List<String>> parGroupe = new LinkedHashMap<>();
    for (ValeurConcert concert : lot) {
      String cle = concert.artisteNorm() + "|" + concert.date();
      parGroupe.computeIfAbsent(cle, _ -> new ArrayList<>());
      List<String> groupe = parGroupe.get(cle);
      if (!groupe.contains(concert.salleNorm())) groupe.add(concert.salleNorm());
    }
    for (List<String> sallesNorm : parGroupe.values()) {
      if (sallesNorm.size() > 1) ajouterDoublonsSalle(sallesNorm, remplacements);
    }
    resoudreChaines(remplacements);
    return remplacements;
  }

  private void ajouterDoublonsSalle(List<String> sallesNorm, Map<String, String> remplacements) {
    for (int i = 0; i < sallesNorm.size(); i++) {
      for (int j = i + 1; j < sallesNorm.size(); j++) {
        String a = sallesNorm.get(i);
        String b = sallesNorm.get(j);
        if (estDoublonSalle(a, b)) {
          String canonique = a.length() >= b.length() ? a : b;
          remplacements.put(a, canonique);
          remplacements.put(b, canonique);
        }
      }
    }
  }

  private ValeurConcert appliquerRemplacementSalle(
      ValeurConcert c, Map<String, String> remplacements) {
    String canonique = remplacements.get(c.salleNorm());
    if (canonique == null || canonique.equals(c.salleNorm())) return c;
    log.debug(
        "doublon salle intra-lot réconcilié entrant={} canonique={}", c.salleNorm(), canonique);
    return c.avecSalleNorm(canonique);
  }

  private List<ValeurConcert> reconcilierContreBase(
      List<ValeurConcert> lot, Map<String, List<String>> indexExistants) {
    return lot.stream().map(concert -> reconcilierUnConcert(concert, indexExistants)).toList();
  }

  private ValeurConcert reconcilierUnConcert(
      ValeurConcert concert, Map<String, List<String>> indexExistants) {
    String cle = concert.artisteNorm() + "|" + concert.date();
    List<String> sallesExistantes = indexExistants.get(cle);
    if (sallesExistantes == null) return concert;
    for (String salleNormExistante : sallesExistantes) {
      if (salleNormExistante.equals(concert.salleNorm())) return concert;
      if (estDoublonSalle(concert.salleNorm(), salleNormExistante)) {
        log.debug(
            "doublon salle inter-jobs réconcilié entrant={} existant={}",
            concert.salleNorm(),
            salleNormExistante);
        return concert.avecSalleNorm(salleNormExistante);
      }
    }
    return concert;
  }

  /// Absorbe les renommages de salle en mettant à jour {@code salles.nom}.
  ///
  /// @param concerts  concerts avec {@code salleSourceId} résolus
  /// @param cachesSalles map sourceId → UUID
  void absorberRenommagesSalles(List<ValeurConcert> concerts, Map<String, UUID> cachesSalles) {
    Map<UUID, String> meilleurNom = new HashMap<>();
    Map<UUID, Integer> meilleurScore = new HashMap<>();
    for (ValeurConcert c : concerts) {
      if (c.salleSourceId() == null) continue;
      UUID salleUuid = cachesSalles.get(c.salleSourceId());
      if (salleUuid == null) continue;
      int score = scoreSalle(c.salle());
      if (!meilleurNom.containsKey(salleUuid) || score > meilleurScore.get(salleUuid)) {
        meilleurNom.put(salleUuid, c.salle());
        meilleurScore.put(salleUuid, score);
      }
    }
    for (var entree : meilleurNom.entrySet()) {
      int modifies =
          jdbc.update(
              "UPDATE salles SET nom = :nom WHERE id = CAST(:uuid AS uuid) AND nom <> :nom",
              Map.of("nom", entree.getValue(), "uuid", entree.getKey().toString()));
      if (modifies > 0) {
        log.info(
            "renommage salle absorbé salleUuid={} nouveauNom={}",
            entree.getKey(),
            entree.getValue());
      }
    }
  }

  /// Résout ou crée une salle et insère son mapping source dans {@code salle_sources}.
  ///
  /// <p>Cherche d'abord par {@code unaccent(lower(nom))} (égalité exacte), puis par tokens
  /// significatifs via une requête unique {@code LIKE ANY} (corrige le pattern N+1).
  /// Si aucune correspondance, crée une nouvelle salle.
  ///
  /// @param c concert portant le {@code salleSourceId} à résoudre
  /// @return UUID de la salle résolue ou créée, ou {@code null} si le {@code salleSourceId}
  ///     est mal formé
  UUID upsertSalleSource(ValeurConcert c) {
    int sep = c.salleSourceId().indexOf(':');
    if (sep <= 0) return null;
    String source = c.salleSourceId().substring(0, sep);
    String identifiant = c.salleSourceId().substring(sep + 1);

    List<UUID> existants =
        jdbc.query(
            "SELECT id FROM salles WHERE unaccent(lower(nom)) = unaccent(lower(:nom)) LIMIT 1",
            Map.of("nom", c.salle()),
            (rs, _) -> rs.getObject("id", UUID.class));
    UUID salleUuid = existants.isEmpty() ? null : existants.get(0);

    if (salleUuid == null) {
      salleUuid = chercherSalleParTokens(c.salle(), c.dept());
    }

    if (salleUuid == null) {
      String salleCle = Normalisation.normaliserTexte(c.salle());
      jdbc.update(
          "INSERT INTO salles (salle_cle, nom, nom_norm, dept)"
              + " VALUES (:salleCle, :nom, :nomNorm, :dept)"
              + " ON CONFLICT (salle_cle) DO NOTHING",
          Map.of("salleCle", salleCle, "nom", c.salle(), "nomNorm", salleCle, "dept", c.dept()));
      salleUuid =
          jdbc.queryForObject(
              "SELECT id FROM salles WHERE salle_cle = :salleCle",
              Map.of("salleCle", salleCle),
              UUID.class);
    }

    if (salleUuid == null) return null;

    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:uuid AS uuid), :source, :identifiant)"
            + " ON CONFLICT DO NOTHING",
        Map.of("uuid", salleUuid.toString(), "source", source, "identifiant", identifiant));

    log.info(
        "salle upsert source salle={} source={} identifiant={} uuid={}",
        c.salle(),
        source,
        identifiant,
        salleUuid);
    return salleUuid;
  }

  /// Cherche une salle existante dont le {@code nom_norm} est un doublon du nom fourni.
  ///
  /// <p>Émet une seule requête SQL pour tous les tokens significatifs du nom (corrige
  /// le pattern N+1 antérieur où une requête était émise par token). Le filtre {@code dept}
  /// élimine les salles homonymes de départements distincts, réduisant le risque de faux positif
  /// du critère pivot dans {@link #estDoublonSalle}.
  ///
  /// @param nom  nom brut de la salle à créer
  /// @param dept département connu du concert (nullable — désactive le filtre si absent)
  /// @return UUID de la salle existante correspondante, ou {@code null} si aucune trouvée
  UUID chercherSalleParTokens(String nom, String dept) {
    String nomNorm = Normalisation.normaliserTexte(nom);
    Set<String> tokens = tokensSignificatifs(nomNorm);
    if (tokens.isEmpty()) return null;

    List<String> tokensList = new ArrayList<>(tokens);
    StringBuilder sbWhere = new StringBuilder("(");
    for (int i = 0; i < tokensList.size(); i++) {
      if (i > 0) sbWhere.append(" OR ");
      sbWhere.append("nom_norm LIKE :t").append(i);
    }
    sbWhere.append(")");
    String whereTokens = sbWhere.toString();

    MapSqlParameterSource params = new MapSqlParameterSource();
    for (int i = 0; i < tokensList.size(); i++) {
      params.addValue("t" + i, "%" + tokensList.get(i) + "%");
    }
    params.addValue("dept", dept, Types.VARCHAR);

    List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT id, nom_norm FROM salles"
                + " WHERE "
                + whereTokens
                + " AND (:dept IS NULL OR dept IS NULL OR dept = :dept)"
                + " LIMIT 50",
            params);

    for (var ligne : lignes) {
      UUID uuid = (UUID) ligne.get("id");
      String nomNormCandidat = (String) ligne.get("nom_norm");
      if (estDoublonSalle(nomNorm, nomNormCandidat)) {
        log.info(
            "salle doublon détecté par tokens : '{}' ≈ '{}' uuid={}", nom, nomNormCandidat, uuid);
        return uuid;
      }
    }
    return null;
  }

  /// Rattache les concerts sans {@code salleUuid} à leur salle via correspondance exacte
  /// sur {@code salle_norm} / {@code nom_norm}.
  ///
  /// @param concerts concerts après déduplication
  /// @return liste enrichie avec les {@code salleUuid} résolus par nom_norm
  List<ValeurConcert> raccrocherSallesParNomNorm(List<ValeurConcert> concerts) {
    List<String> sallesNormsSansUuid =
        concerts.stream()
            .filter(c -> c.salleUuid() == null)
            .map(ValeurConcert::salleNorm)
            .distinct()
            .toList();
    if (sallesNormsSansUuid.isEmpty()) return concerts;

    Map<String, UUID> cache = new HashMap<>();
    jdbc.queryForList(
            "SELECT nom_norm, id FROM salles WHERE nom_norm IN (:normsNoms)",
            Map.of("normsNoms", sallesNormsSansUuid))
        .forEach(r -> cache.put((String) r.get("nom_norm"), (UUID) r.get("id")));

    return concerts.stream()
        .map(
            c -> {
              if (c.salleUuid() != null) return c;
              UUID uuid = cache.get(c.salleNorm());
              return uuid != null ? c.avecSalleUuid(uuid) : c;
            })
        .toList();
  }
}

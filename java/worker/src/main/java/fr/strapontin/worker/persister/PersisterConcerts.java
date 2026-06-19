/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import java.math.BigDecimal;
import java.sql.Types;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.transaction.annotation.Transactional;

/// Service de persistance des concerts dédoublonnés.
///
/// Effectue l'upsert par lot en base avec la stratégie ON CONFLICT sur
/// `(artiste_norm, date, salle_norm)`. Préfère les noms avec diacritiques
/// et la casse mixte. Supprime les doublons par `(source, url)` introduits
/// lors d'une résolution de nom canonique entre deux fetches.
///
/// Réconcilie les variations mineures de nommage de salle et d'artiste entre deux fetches
/// (intra-lot puis contre la base) avant l'upsert, pour éviter qu'un même événement renommé
/// légèrement par une source ne crée un doublon plutôt qu'une mise à jour.
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class PersisterConcerts {

  private static final Logger log = LoggerFactory.getLogger(PersisterConcerts.class);

  private static final int TAILLE_LOT = 500;
  private static final double SEUIL_SIMILARITE_SALLE = 0.5;
  private static final int LONGUEUR_MIN_CONTAINMENT_SALLE = 4;

  // Seuils artiste : seuil Levenshtein strict + containment avec ratio court/long ≥ 0.6.
  // Le ratio évite les faux positifs ("pink"/"pink floyd" = 0.4 → false) tout en couvrant
  // les troncatures TM ("aliz"/"alizee" = 0.67 → true, "fawn"/"fawnn" = 0.8 → true).
  private static final double SEUIL_SIMILARITE_ARTISTE = 0.85;
  private static final int LONGUEUR_MIN_CONTAINMENT_ARTISTE = 4;
  private static final double RATIO_MIN_CONTAINMENT_ARTISTE = 0.6;

  private static final String SQL_UPSERT_ENTETE =
      """
      INSERT INTO concerts
          (artiste, artiste_norm, date, heure, salle, salle_norm, ville, dept,
           prix_min, prix_max, url, source, classification, genre, statut,
           presale_debut, presale_nom, salle_uuid, artiste_id)
      VALUES
      """;

  private static final String SQL_UPSERT_CONFLIT =
      """
      ON CONFLICT (artiste_norm, date, salle_norm) DO UPDATE SET
          artiste        = CASE WHEN unaccent(EXCLUDED.artiste) != EXCLUDED.artiste
                                THEN EXCLUDED.artiste ELSE concerts.artiste END,
          salle          = CASE WHEN upper(EXCLUDED.salle) != EXCLUDED.salle
                                THEN EXCLUDED.salle ELSE concerts.salle END,
          heure          = EXCLUDED.heure,
          ville          = EXCLUDED.ville,
          dept           = EXCLUDED.dept,
          prix_min       = EXCLUDED.prix_min,
          prix_max       = EXCLUDED.prix_max,
          source         = EXCLUDED.source,
          url            = COALESCE(EXCLUDED.url, concerts.url),
          classification = COALESCE(EXCLUDED.classification, concerts.classification),
          genre          = COALESCE(EXCLUDED.genre, concerts.genre),
          statut         = CASE
                             WHEN concerts.statut IN ('cancelled', 'rescheduled', 'postponed')
                                  THEN concerts.statut
                             WHEN concerts.source = 'ticketmaster' AND EXCLUDED.source = 'eventim'
                                  THEN concerts.statut
                             ELSE EXCLUDED.statut END,
          presale_debut  = COALESCE(EXCLUDED.presale_debut, concerts.presale_debut),
          presale_nom    = COALESCE(EXCLUDED.presale_nom, concerts.presale_nom),
          salle_uuid     = COALESCE(EXCLUDED.salle_uuid, concerts.salle_uuid),
          artiste_id     = COALESCE(EXCLUDED.artiste_id, concerts.artiste_id),
          updated_at     = now()
      RETURNING (xmax = 0) AS est_insertion
      """;

  private static final String SQL_DOUBLONS_URL =
      """
      SELECT DISTINCT c1.id::text
      FROM concerts c1
      JOIN concerts c2
        ON c1.source = c2.source
       AND c1.url    = c2.url
       AND c1.date   = c2.date
       AND c1.heure IS NOT DISTINCT FROM c2.heure
       AND c1.id    != c2.id
       AND c1.updated_at < c2.updated_at
      WHERE c1.url IS NOT NULL
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public PersisterConcerts(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Vérifie si deux chaînes normalisées désignent la même entité, selon trois critères (union) :
  ///
  /// 1. égalité exacte
  /// 2. similarité Levenshtein ≥ `seuilSimilarite`
  /// 3. containment : la chaîne la plus courte (≥ `longueurMinContainment`) est contenue dans
  ///    la plus longue
  ///
  /// @param a                      première chaîne normalisée
  /// @param b                      deuxième chaîne normalisée
  /// @param seuilSimilarite        seuil de similarité Levenshtein (0 à 1)
  /// @param longueurMinContainment longueur minimale de la chaîne courte pour containment
  /// @param ratioMinContainment    ratio minimal court/long pour containment (0.0 = désactivé)
  /// @return `true` si les deux chaînes sont considérées identiques
  private static boolean estDoublonTexte(
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

  /// Vérifie si deux noms de salle normalisés désignent la même salle.
  ///
  /// @param a premier nom normalisé
  /// @param b deuxième nom normalisé
  /// @return `true` si les salles sont considérées identiques
  public static boolean estDoublonSalle(String a, String b) {
    return estDoublonTexte(a, b, SEUIL_SIMILARITE_SALLE, LONGUEUR_MIN_CONTAINMENT_SALLE, 0.0);
  }

  /// Vérifie si deux noms d'artiste normalisés désignent le même artiste.
  ///
  /// Seuil Levenshtein strict (0.85) complété par un containment avec ratio court/long ≥ 0.6 :
  /// couvre les troncatures TM ("aliz"/"alizee", "fawn"/"fawnn") et les variations mineures
  /// (suffixe numérique, article) sans fusionner des artistes distincts ("pink"/"pink floyd").
  ///
  /// @param a premier nom normalisé
  /// @param b deuxième nom normalisé
  /// @return `true` si les artistes sont considérés identiques
  public static boolean estDoublonArtiste(String a, String b) {
    return estDoublonTexte(
        a,
        b,
        SEUIL_SIMILARITE_ARTISTE,
        LONGUEUR_MIN_CONTAINMENT_ARTISTE,
        RATIO_MIN_CONTAINMENT_ARTISTE);
  }

  private static int scoreArtisteSalle(String artiste, String salle) {
    int artisteDiacr =
        Normalizer.normalize(artiste, Normalizer.Form.NFD).length() - artiste.length();
    int salleDiacr = Normalizer.normalize(salle, Normalizer.Form.NFD).length() - salle.length();
    int artisteCasseMixte = !artiste.equals(artiste.toUpperCase()) ? 5 : 0;
    int salleCasseMixte = !salle.equals(salle.toUpperCase()) ? 1 : 0;
    return artisteDiacr * 10 + artisteCasseMixte + salleDiacr + salleCasseMixte;
  }

  /// Dédoublonne par clé de conflit `(artiste_norm, date, salle_norm)`.
  ///
  /// En cas de doublon, conserve la version avec le meilleur score
  /// (diacritiques et casse mixte préférés).
  ///
  /// @param liste concerts bruts à dédoublonner
  /// @return liste sans doublons
  public static List<ConcertBrut> deduplicerParCleConflit(List<ConcertBrut> liste) {
    Map<String, ConcertBrut> parCle = new LinkedHashMap<>();
    for (ConcertBrut c : liste) {
      String cle =
          Normalisation.normaliserTexte(c.artiste())
              + "|"
              + c.date()
              + "|"
              + Normalisation.normaliserTexte(c.salle());
      ConcertBrut existant = parCle.get(cle);
      if (existant == null
          || scoreArtisteSalle(c.artiste(), c.salle())
              > scoreArtisteSalle(existant.artiste(), existant.salle())) {
        parCle.put(cle, c);
      }
    }
    return new ArrayList<>(parCle.values());
  }

  /// Dédoublonne un lot réconcilié par `(artisteNorm, date, salleNorm)`.
  ///
  /// Les réécritures de `salleNorm` par `reconcilierSalles` peuvent créer
  /// de nouveaux doublons intra-lot. Cette passe les élimine.
  ///
  /// @param lot valeurs de concerts après réconciliation des salles
  /// @return lot sans doublons
  public static List<ValeurConcert> deduplicerLotReconcilie(List<ValeurConcert> lot) {
    Map<String, ValeurConcert> parCle = new LinkedHashMap<>();
    for (ValeurConcert v : lot) {
      String cle = v.artisteNorm() + "|" + v.date() + "|" + v.salleNorm();
      ValeurConcert existant = parCle.get(cle);
      if (existant == null
          || scoreArtisteSalle(v.artiste(), v.salle())
              > scoreArtisteSalle(existant.artiste(), existant.salle())) {
        parCle.put(cle, v);
      }
    }
    return new ArrayList<>(parCle.values());
  }

  private List<ValeurConcert> reconcilierSalles(List<ValeurConcert> lot) {
    Map<String, String> remplacementsIntraLot = construireRemplacementsIntraLot(lot);
    List<ValeurConcert> lotApresIntraLot =
        remplacementsIntraLot.isEmpty()
            ? lot
            : lot.stream().map(c -> appliquerRemplacementSalle(c, remplacementsIntraLot)).toList();

    Set<String> artistesNorms =
        new java.util.HashSet<>(lotApresIntraLot.stream().map(ValeurConcert::artisteNorm).toList());
    if (artistesNorms.isEmpty()) return lotApresIntraLot;

    List<Map<String, Object>> existants =
        jdbc.queryForList(
            "SELECT artiste_norm, date::text AS date, salle_norm FROM concerts"
                + " WHERE artiste_norm IN (:artistesNorms)",
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
    return new ValeurConcert(
        c.artiste(),
        c.artisteNorm(),
        c.date(),
        c.heure(),
        c.salle(),
        canonique,
        c.ville(),
        c.dept(),
        c.prixMin(),
        c.prixMax(),
        c.url(),
        c.source(),
        c.classification(),
        c.genre(),
        c.statut(),
        c.presaleDebut(),
        c.presaleNom(),
        c.salleSourceId(),
        c.artisteSourceId(),
        c.salleUuid(),
        c.artisteId());
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
        return new ValeurConcert(
            concert.artiste(),
            concert.artisteNorm(),
            concert.date(),
            concert.heure(),
            concert.salle(),
            salleNormExistante,
            concert.ville(),
            concert.dept(),
            concert.prixMin(),
            concert.prixMax(),
            concert.url(),
            concert.source(),
            concert.classification(),
            concert.genre(),
            concert.statut(),
            concert.presaleDebut(),
            concert.presaleNom(),
            concert.salleSourceId(),
            concert.artisteSourceId(),
            concert.salleUuid(),
            concert.artisteId());
      }
    }
    return concert;
  }

  /// Réconcilie les variations de nommage d'artiste entre deux fetches (ex. ajout d'un
  /// suffixe numérique ou d'un article), symétrique à {@link #reconcilierSalles}.
  ///
  /// Le regroupement se fait par `(date, salleNorm)` — déjà canonique à ce stade puisque
  /// cette méthode s'exécute après {@link #reconcilierSalles} — plutôt que par `artisteNorm`,
  /// qui est précisément la valeur à corriger.
  ///
  /// @param lot valeurs de concerts après réconciliation des salles
  /// @return lot avec `artisteNorm` réconcilié
  private List<ValeurConcert> reconcilierArtistes(List<ValeurConcert> lot) {
    Map<String, String> remplacementsIntraLot = construireRemplacementsArtisteIntraLot(lot);
    List<ValeurConcert> lotApresIntraLot =
        remplacementsIntraLot.isEmpty()
            ? lot
            : lot.stream()
                .map(c -> appliquerRemplacementArtiste(c, remplacementsIntraLot))
                .toList();

    Set<String> sallesNorms =
        new java.util.HashSet<>(lotApresIntraLot.stream().map(ValeurConcert::salleNorm).toList());
    if (sallesNorms.isEmpty()) return lotApresIntraLot;

    List<Map<String, Object>> existants =
        jdbc.queryForList(
            "SELECT artiste_norm, date::text AS date, salle_norm FROM concerts"
                + " WHERE salle_norm IN (:sallesNorms)",
            Map.of("sallesNorms", sallesNorms));

    Map<String, List<String>> indexExistants = new HashMap<>();
    for (Map<String, Object> e : existants) {
      String cle = e.get("date") + "|" + e.get("salle_norm");
      indexExistants
          .computeIfAbsent(cle, _ -> new ArrayList<>())
          .add((String) e.get("artiste_norm"));
    }

    return lotApresIntraLot.stream()
        .map(concert -> reconcilierUnArtiste(concert, indexExistants))
        .toList();
  }

  private Map<String, String> construireRemplacementsArtisteIntraLot(List<ValeurConcert> lot) {
    Map<String, String> remplacements = new HashMap<>();
    Map<String, List<String>> parGroupe = new LinkedHashMap<>();
    for (ValeurConcert concert : lot) {
      String cle = concert.date() + "|" + concert.salleNorm();
      parGroupe.computeIfAbsent(cle, _ -> new ArrayList<>());
      List<String> groupe = parGroupe.get(cle);
      if (!groupe.contains(concert.artisteNorm())) groupe.add(concert.artisteNorm());
    }
    for (List<String> artistesNorm : parGroupe.values()) {
      if (artistesNorm.size() > 1) ajouterDoublonsArtiste(artistesNorm, remplacements);
    }
    return remplacements;
  }

  private void ajouterDoublonsArtiste(
      List<String> artistesNorm, Map<String, String> remplacements) {
    for (int i = 0; i < artistesNorm.size(); i++) {
      for (int j = i + 1; j < artistesNorm.size(); j++) {
        String a = artistesNorm.get(i);
        String b = artistesNorm.get(j);
        if (estDoublonArtiste(a, b)) {
          String canonique = a.length() >= b.length() ? a : b;
          remplacements.put(a, canonique);
          remplacements.put(b, canonique);
        }
      }
    }
  }

  private ValeurConcert appliquerRemplacementArtiste(
      ValeurConcert c, Map<String, String> remplacements) {
    String canonique = remplacements.get(c.artisteNorm());
    if (canonique == null || canonique.equals(c.artisteNorm())) return c;
    log.debug(
        "doublon artiste intra-lot réconcilié entrant={} canonique={}", c.artisteNorm(), canonique);
    return new ValeurConcert(
        c.artiste(),
        canonique,
        c.date(),
        c.heure(),
        c.salle(),
        c.salleNorm(),
        c.ville(),
        c.dept(),
        c.prixMin(),
        c.prixMax(),
        c.url(),
        c.source(),
        c.classification(),
        c.genre(),
        c.statut(),
        c.presaleDebut(),
        c.presaleNom(),
        c.salleSourceId(),
        c.artisteSourceId(),
        c.salleUuid(),
        c.artisteId());
  }

  private ValeurConcert reconcilierUnArtiste(
      ValeurConcert concert, Map<String, List<String>> indexExistants) {
    String cle = concert.date() + "|" + concert.salleNorm();
    List<String> artistesExistants = indexExistants.get(cle);
    if (artistesExistants == null) return concert;
    for (String artisteNormExistant : artistesExistants) {
      if (artisteNormExistant.equals(concert.artisteNorm())) return concert;
      if (estDoublonArtiste(concert.artisteNorm(), artisteNormExistant)) {
        log.debug(
            "doublon artiste inter-jobs réconcilié entrant={} existant={}",
            concert.artisteNorm(),
            artisteNormExistant);
        return new ValeurConcert(
            concert.artiste(),
            artisteNormExistant,
            concert.date(),
            concert.heure(),
            concert.salle(),
            concert.salleNorm(),
            concert.ville(),
            concert.dept(),
            concert.prixMin(),
            concert.prixMax(),
            concert.url(),
            concert.source(),
            concert.classification(),
            concert.genre(),
            concert.statut(),
            concert.presaleDebut(),
            concert.presaleNom(),
            concert.salleSourceId(),
            concert.artisteSourceId(),
            concert.salleUuid(),
            concert.artisteId());
      }
    }
    return concert;
  }

  /// Construit le tuple de valeurs paramétrées (placeholders nommés indexés) d'une ligne d'upsert.
  ///
  /// @param i indice de la ligne dans le lot (suffixe des paramètres nommés)
  /// @return le tuple {@code (:artiste_i, ...)} pour la clause VALUES
  private static String placeholdersLigne(int i) {
    return "(:artiste_"
        + i
        + ", :artisteNorm_"
        + i
        + ", :date_"
        + i
        + ", :heure_"
        + i
        + ", :salle_"
        + i
        + ", :salleNorm_"
        + i
        + ", :ville_"
        + i
        + ", :dept_"
        + i
        + ", :prixMin_"
        + i
        + ", :prixMax_"
        + i
        + ", :url_"
        + i
        + ", CAST(:source_"
        + i
        + " AS source_concert)"
        + ", :classification_"
        + i
        + ", :genre_"
        + i
        + ", :statut_"
        + i
        + ", CAST(:presaleDebut_"
        + i
        + " AS timestamptz)"
        + ", :presaleNom_"
        + i
        + ", CAST(:salleUuid_"
        + i
        + " AS uuid)"
        + ", CAST(:artisteId_"
        + i
        + " AS uuid))";
  }

  /// Ajoute les paramètres nommés indexés d'une ligne d'upsert.
  ///
  /// @param params paramètres nommés du batch
  /// @param v      valeurs du concert
  /// @param i      indice de la ligne (suffixe des paramètres)
  private static void ajouterParametresUpsert(
      MapSqlParameterSource params, ValeurConcert v, int i) {
    params.addValue("artiste_" + i, v.artiste());
    params.addValue("artisteNorm_" + i, v.artisteNorm());
    params.addValue("date_" + i, v.date(), Types.DATE);
    params.addValue("heure_" + i, v.heure(), Types.TIME);
    params.addValue("salle_" + i, v.salle());
    params.addValue("salleNorm_" + i, v.salleNorm());
    params.addValue("ville_" + i, v.ville());
    params.addValue("dept_" + i, v.dept());
    params.addValue("prixMin_" + i, v.prixMin(), Types.NUMERIC);
    params.addValue("prixMax_" + i, v.prixMax(), Types.NUMERIC);
    params.addValue("url_" + i, v.url());
    params.addValue("source_" + i, v.source());
    params.addValue("classification_" + i, v.classification());
    params.addValue("genre_" + i, v.genre());
    params.addValue("statut_" + i, v.statut());
    params.addValue("presaleDebut_" + i, v.presaleDebut());
    params.addValue("presaleNom_" + i, v.presaleNom());
    params.addValue("salleUuid_" + i, v.salleUuid() != null ? v.salleUuid().toString() : null);
    params.addValue("artisteId_" + i, v.artisteId() != null ? v.artisteId().toString() : null);
  }

  /// Résout les UUIDs internes depuis les identifiants sources préfixés.
  ///
  /// <p>Pour chaque concert portant un {@code salleSourceId} ou {@code artisteSourceId}, consulte
  /// {@code salle_sources} et {@code artiste_sources} pour trouver l'UUID stable correspondant.
  /// En cas de renommage (même ID source, nom différent), met à jour {@code salles.nom}.
  ///
  /// <p>Pour les salles TM dont le {@code salleSourceId} est absent de {@code salle_sources},
  /// effectue un upsert dans {@code salles} et {@code salle_sources} afin d'attribuer un UUID
  /// stable dès le premier fetch, indépendamment du suivi utilisateur.
  ///
  /// <p>Les concerts sans {@code salleSourceId} conservent {@code salleUuid=null} ;
  /// la réconciliation textuelle prend ensuite le relais.
  ///
  /// @param concerts valeurs normalisées avant upsert
  /// @return liste enrichie avec les UUIDs résolus
  private List<ValeurConcert> resoudreIdentitesSources(List<ValeurConcert> concerts) {
    Map<String, UUID> cachesSalles = resoudreUuidsParSource(concerts, "salle");
    Map<String, UUID> cachesArtistes = resoudreUuidsParSource(concerts, "artiste");
    absorberRenommagesSalles(concerts, cachesSalles);

    for (ValeurConcert c : concerts) {
      if (c.salleSourceId() != null && cachesSalles.get(c.salleSourceId()) == null) {
        UUID uuid = upsertSalleSource(c);
        if (uuid != null) cachesSalles.put(c.salleSourceId(), uuid);
      }
    }

    return concerts.stream()
        .map(
            c -> {
              UUID salleUuid =
                  c.salleSourceId() != null ? cachesSalles.get(c.salleSourceId()) : null;
              UUID artisteId =
                  c.artisteSourceId() != null ? cachesArtistes.get(c.artisteSourceId()) : null;
              if (salleUuid == null && artisteId == null) return c;
              return new ValeurConcert(
                  c.artiste(),
                  c.artisteNorm(),
                  c.date(),
                  c.heure(),
                  c.salle(),
                  c.salleNorm(),
                  c.ville(),
                  c.dept(),
                  c.prixMin(),
                  c.prixMax(),
                  c.url(),
                  c.source(),
                  c.classification(),
                  c.genre(),
                  c.statut(),
                  c.presaleDebut(),
                  c.presaleNom(),
                  c.salleSourceId(),
                  c.artisteSourceId(),
                  salleUuid,
                  artisteId);
            })
        .toList();
  }

  private Map<String, UUID> resoudreUuidsParSource(List<ValeurConcert> concerts, String type) {
    Map<String, List<String>> parSource = new HashMap<>();
    for (ValeurConcert c : concerts) {
      String sourceId = "salle".equals(type) ? c.salleSourceId() : c.artisteSourceId();
      if (sourceId != null) {
        int sep = sourceId.indexOf(':');
        if (sep > 0) {
          String source = sourceId.substring(0, sep);
          String identifiant = sourceId.substring(sep + 1);
          parSource.computeIfAbsent(source, _ -> new ArrayList<>()).add(identifiant);
        }
      }
    }

    Map<String, UUID> cache = new HashMap<>();
    String table = "salle".equals(type) ? "salle_sources" : "artiste_sources";
    String colUuid = "salle".equals(type) ? "salle_id" : "artiste_id";

    for (var entry : parSource.entrySet()) {
      String source = entry.getKey();
      List<String> ids = entry.getValue().stream().distinct().toList();
      List<Map<String, Object>> lignes =
          jdbc.queryForList(
              "SELECT "
                  + colUuid
                  + ", identifiant_externe FROM "
                  + table
                  + " WHERE source = :source AND identifiant_externe IN (:ids)",
              Map.of("source", source, "ids", ids));
      for (var ligne : lignes) {
        String cle = source + ":" + ligne.get("identifiant_externe");
        cache.put(cle, (UUID) ligne.get(colUuid));
      }
    }
    return cache;
  }

  /// Résout ou crée une salle et insère son mapping source dans {@code salle_sources}.
  ///
  /// <p>Appelée quand un {@code salleSourceId} n'est pas trouvé dans {@code salle_sources}.
  /// Cherche d'abord une salle existante par {@code unaccent(lower(nom))} pour absorber les
  /// variantes d'identifiant historiques ({@code id} généré différemment selon l'origine de la
  /// salle). Si aucune salle n'est trouvée, en crée une nouvelle. Dans les deux cas, insère le
  /// mapping {@code salle_sources} (ON CONFLICT DO NOTHING).
  ///
  /// @param c le concert portant le {@code salleSourceId} à résoudre
  /// @return l'UUID de la salle résolue ou créée, ou {@code null} si le {@code salleSourceId}
  ///     est mal formé
  private UUID upsertSalleSource(ValeurConcert c) {
    int sep = c.salleSourceId().indexOf(':');
    if (sep <= 0) return null;
    String source = c.salleSourceId().substring(0, sep);
    String identifiant = c.salleSourceId().substring(sep + 1);

    List<UUID> existants =
        jdbc.query(
            "SELECT salle_uuid FROM salles"
                + " WHERE unaccent(lower(nom)) = unaccent(lower(:nom)) LIMIT 1",
            Map.of("nom", c.salle()),
            (rs, _) -> rs.getObject("salle_uuid", UUID.class));
    UUID salleUuid = existants.isEmpty() ? null : existants.get(0);

    if (salleUuid == null) {
      String id = Normalisation.normaliserTexte(c.salle());
      jdbc.update(
          "INSERT INTO salles (id, nom, nom_norm) VALUES (:id, :nom, :nomNorm)"
              + " ON CONFLICT (id) DO NOTHING",
          Map.of("id", id, "nom", c.salle(), "nomNorm", id));
      salleUuid =
          jdbc.queryForObject(
              "SELECT salle_uuid FROM salles WHERE id = :id", Map.of("id", id), UUID.class);
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

  private void absorberRenommagesSalles(
      List<ValeurConcert> concerts, Map<String, UUID> cachesSalles) {
    for (ValeurConcert c : concerts) {
      if (c.salleSourceId() == null) continue;
      UUID salleUuid = cachesSalles.get(c.salleSourceId());
      if (salleUuid == null) continue;
      int modifies =
          jdbc.update(
              "UPDATE salles SET nom = :nom WHERE salle_uuid = CAST(:uuid AS uuid) AND nom <> :nom",
              Map.of("nom", c.salle(), "uuid", salleUuid.toString()));
      if (modifies > 0) {
        log.info("renommage salle absorbé salleUuid={} nouveauNom={}", salleUuid, c.salle());
      }
    }
  }

  /// Insère ou met à jour un lot de concerts en une seule requête multi-lignes.
  ///
  /// <p>L'unicité de la clé de conflit {@code (artiste_norm, date, salle_norm)} dans le lot est
  /// garantie en amont par {@link #deduplicerLotReconcilie}, ce qui rend l'{@code ON CONFLICT}
  /// multi-VALUES sûr (pas de double affectation d'une même ligne).
  ///
  /// @param valeurs concerts réconciliés et dédupliqués du lot
  /// @return décompte des insertions et mises à jour du lot
  private ResultatPersistance upsertLot(List<ValeurConcert> valeurs) {
    if (valeurs.isEmpty()) return new ResultatPersistance(0, 0);

    StringBuilder sql = new StringBuilder(SQL_UPSERT_ENTETE);
    MapSqlParameterSource params = new MapSqlParameterSource();
    for (int i = 0; i < valeurs.size(); i++) {
      if (i > 0) sql.append(",\n");
      sql.append(placeholdersLigne(i));
      ajouterParametresUpsert(params, valeurs.get(i), i);
    }
    sql.append("\n").append(SQL_UPSERT_CONFLIT);

    List<Map<String, Object>> lignes = jdbc.queryForList(sql.toString(), params);
    int inserts = 0;
    int updates = 0;
    for (Map<String, Object> ligne : lignes) {
      if (Boolean.TRUE.equals(ligne.get("est_insertion"))) inserts++;
      else updates++;
    }
    return new ResultatPersistance(inserts, updates);
  }

  /// Persiste les concerts dédoublonnés en base via upsert.
  ///
  /// Traitement par lots de 500. Pour chaque lot :
  ///
  /// 1. Réconciliation des noms de salle (intra-lot puis contre la base)
  /// 2. Réconciliation des noms d'artiste (intra-lot puis contre la base), groupée par
  ///    `(date, salleNorm)` désormais canonique
  /// 3. Déduplication sur la clé de conflit finale
  /// 4. Upsert ON CONFLICT avec préférence pour les diacritiques et la casse mixte
  /// 5. Suppression des doublons par `(source, url)`
  ///
  /// @param liste concerts à persister
  /// @return résultat avec nombre d'insertions et de mises à jour
  /// Résultat de la persistance d'un lot de concerts.
  ///
  /// @param nbInseres nombre de concerts insérés
  /// @param nbMisAJour nombre de concerts mis à jour
  public record ResultatPersistance(int nbInseres, int nbMisAJour) {}

  @Transactional
  public ResultatPersistance persisterConcerts(List<ConcertBrut> liste) {
    if (liste.isEmpty()) return new ResultatPersistance(0, 0);

    List<ConcertBrut> apresDedup = deduplicerParCleConflit(liste);
    List<ConcertBrut> listeFiltree =
        apresDedup.stream()
            .filter(
                c -> {
                  if (Normalisation.normaliserTexte(c.artiste()).isBlank()) {
                    log.warn(
                        "concert rejeté — artiste_norm vide artiste={} date={} salle={}",
                        c.artiste(),
                        c.date(),
                        c.salle());
                    return false;
                  }
                  return true;
                })
            .toList();

    int totalInserts = 0;
    int totalUpdates = 0;

    for (int debut = 0; debut < listeFiltree.size(); debut += TAILLE_LOT) {
      List<ConcertBrut> lot =
          listeFiltree.subList(debut, Math.min(debut + TAILLE_LOT, listeFiltree.size()));

      List<ValeurConcert> valeursBrutes =
          lot.stream()
              .map(
                  c ->
                      new ValeurConcert(
                          c.artiste(),
                          Normalisation.normaliserTexte(c.artiste()),
                          LocalDate.parse(c.date()),
                          c.heure() != null ? LocalTime.parse(c.heure()) : null,
                          c.salle(),
                          Normalisation.normaliserTexte(c.salle()),
                          c.ville(),
                          c.dept(),
                          c.prixMin() != null ? BigDecimal.valueOf(c.prixMin()) : null,
                          c.prixMax() != null ? BigDecimal.valueOf(c.prixMax()) : null,
                          c.url(),
                          c.source(),
                          c.classification(),
                          c.genre(),
                          c.statut(),
                          c.presaleDebut(),
                          c.presaleNom(),
                          c.salleSourceId(),
                          c.artisteSourceId(),
                          null,
                          null))
              .toList();

      List<ValeurConcert> valeurs =
          deduplicerLotReconcilie(
              reconcilierArtistes(reconcilierSalles(resoudreIdentitesSources(valeursBrutes))));

      ResultatPersistance resultatLot = upsertLot(valeurs);
      totalInserts += resultatLot.nbInseres();
      totalUpdates += resultatLot.nbMisAJour();
    }

    supprimerDoublonsParUrl();

    log.info(
        "concerts persistés total={} inserts={} updates={}",
        totalInserts + totalUpdates,
        totalInserts,
        totalUpdates);
    return new ResultatPersistance(totalInserts, totalUpdates);
  }

  private void supprimerDoublonsParUrl() {
    List<String> idsDoublons = jdbc.queryForList(SQL_DOUBLONS_URL, Map.of(), String.class);
    if (!idsDoublons.isEmpty()) {
      jdbc.update("DELETE FROM concerts WHERE id::text IN (:ids)", Map.of("ids", idsDoublons));
      log.info("doublons url supprimés count={}", idsDoublons.size());
    }
  }
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
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
/// @since 2.0.0
/// @author Kwaite
@Service
public class PersisterConcerts {

  private static final Logger log = LoggerFactory.getLogger(PersisterConcerts.class);

  private static final int TAILLE_LOT = 500;
  private static final double SEUIL_SIMILARITE_SALLE = 0.5;
  private static final int LONGUEUR_MIN_CONTAINMENT = 4;

  private static final String SQL_UPSERT =
      """
      INSERT INTO concerts
          (artiste, artiste_norm, date, heure, salle, salle_norm, ville, dept,
           prix_min, prix_max, url, source, classification, genre, statut)
      VALUES
          (:artiste, :artisteNorm, :date, :heure, :salle, :salleNorm, :ville, :dept,
           :prixMin, :prixMax, :url, CAST(:source AS source_concert),
           :classification, :genre, :statut)
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
          statut         = COALESCE(EXCLUDED.statut, concerts.statut),
          updated_at     = now()
      RETURNING id, (xmax = 0) AS est_insertion
      """;

  private static final String SQL_DOUBLONS_URL =
      """
      SELECT DISTINCT c1.id::text
      FROM concerts c1
      JOIN concerts c2
        ON c1.source = c2.source
       AND c1.url    = c2.url
       AND c1.id    != c2.id
       AND c1.updated_at < c2.updated_at
      WHERE c1.url IS NOT NULL
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public PersisterConcerts(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Vérifie si deux noms de salle normalisés désignent la même salle.
  ///
  /// Trois critères (union) :
  ///
  /// 1. égalité exacte
  /// 2. similarité Levenshtein ≥ 0.5
  /// 3. containment : la chaîne la plus courte (≥ 4 chars) est contenue dans la plus longue
  ///
  /// @param a premier nom normalisé
  /// @param b deuxième nom normalisé
  /// @return `true` si les salles sont considérées identiques
  public static boolean estDoublonSalle(String a, String b) {
    if (a.equals(b)) return true;
    if (Normalisation.similarite(a, b) >= SEUIL_SIMILARITE_SALLE) return true;
    String court = a.length() <= b.length() ? a : b;
    String long_ = a.length() <= b.length() ? b : a;
    return court.length() >= LONGUEUR_MIN_CONTAINMENT && long_.contains(court);
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
      if (sallesNorm.size() <= 1) continue;
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
    return remplacements;
  }

  private ValeurConcert appliquerRemplacementSalle(
      ValeurConcert c, Map<String, String> remplacements) {
    String canonique = remplacements.get(c.salleNorm());
    if (canonique == null || canonique.equals(c.salleNorm())) return c;
    log.debug(
        "doublon salle intra-lot réconcilié entrant={} canonique={}", c.salleNorm(), canonique);
    return new ValeurConcert(
        c.artiste(), c.artisteNorm(), c.date(), c.heure(), c.salle(), canonique,
        c.ville(), c.dept(), c.prixMin(), c.prixMax(), c.url(), c.source(),
        c.classification(), c.genre(), c.statut());
  }

  private List<ValeurConcert> reconcilierContreBase(
      List<ValeurConcert> lot, Map<String, List<String>> indexExistants) {
    return lot.stream()
        .map(concert -> reconcilierUnConcert(concert, indexExistants))
        .toList();
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
            concert.salleNorm(), salleNormExistante);
        return new ValeurConcert(
            concert.artiste(), concert.artisteNorm(), concert.date(), concert.heure(),
            concert.salle(), salleNormExistante, concert.ville(), concert.dept(),
            concert.prixMin(), concert.prixMax(), concert.url(), concert.source(),
            concert.classification(), concert.genre(), concert.statut());
      }
    }
    return concert;
  }

  private MapSqlParameterSource parametresUpsert(ValeurConcert v) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    params.addValue("artiste", v.artiste());
    params.addValue("artisteNorm", v.artisteNorm());
    params.addValue("date", v.date(), Types.DATE);
    params.addValue("heure", v.heure(), Types.TIME);
    params.addValue("salle", v.salle());
    params.addValue("salleNorm", v.salleNorm());
    params.addValue("ville", v.ville());
    params.addValue("dept", v.dept());
    params.addValue("prixMin", v.prixMin(), Types.NUMERIC);
    params.addValue("prixMax", v.prixMax(), Types.NUMERIC);
    params.addValue("url", v.url());
    params.addValue("source", v.source());
    params.addValue("classification", v.classification());
    params.addValue("genre", v.genre());
    params.addValue("statut", v.statut());
    return params;
  }

  /// Persiste les concerts dédoublonnés en base via upsert.
  ///
  /// Traitement par lots de 500. Pour chaque lot :
  ///
  /// 1. Réconciliation des noms de salle (intra-lot puis contre la base)
  /// 2. Déduplication sur la clé de conflit finale
  /// 3. Upsert ON CONFLICT avec préférence pour les diacritiques et la casse mixte
  /// 4. Suppression des doublons par `(source, url)`
  ///
  /// @param liste concerts à persister
  /// @return résultat avec nombre d'insertions et de mises à jour
  @Transactional
  /// Résultat de la persistance d'un lot de concerts.
  ///
  /// @param nbInseres nombre de concerts insérés
  /// @param nbMisAJour nombre de concerts mis à jour
  public record ResultatPersistance(int nbInseres, int nbMisAJour) {}

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
                          c.statut()))
              .toList();

      List<ValeurConcert> valeurs = deduplicerLotReconcilie(reconcilierSalles(valeursBrutes));

      for (ValeurConcert v : valeurs) {
        List<Map<String, Object>> lignes = jdbc.queryForList(SQL_UPSERT, parametresUpsert(v));
        for (Map<String, Object> ligne : lignes) {
          if (Boolean.TRUE.equals(ligne.get("est_insertion"))) totalInserts++;
          else totalUpdates++;
        }
      }
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

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.Normalisation;
import java.text.Normalizer;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Réconciliation des variations mineures de nommage de salle entre fetches successifs — extrait de
 * {@link ReconciliateurSalles} (v2.8.7, dette de taille pure, aucun changement fonctionnel).
 *
 * <p>Deux passes : intra-lot (variantes dans le même fetch) puis contre la base (variantes entre
 * deux fetches). Utilise {@link CorrespondanceSalle#estDoublonSalle} pour déterminer si deux noms
 * désignent la même salle. Gère également l'absorption des renommages détectés par {@code
 * upsertSalleSource} ({@link ResolveurSalleParConcert}) via mise à jour de {@code salles.nom}.
 *
 * @since 2.8.7
 * @author Kwaite
 */
@Service
class ReconciliationNomsSalles {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationNomsSalles.class);

  private final NamedParameterJdbcTemplate jdbc;

  ReconciliationNomsSalles(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Calcule un score de qualité pour choisir le meilleur nom de salle.
   *
   * @param salle nom brut de la salle
   * @return score (diacritiques favorisés, casse mixte favorisée)
   */
  private static int scoreSalle(String salle) {
    int diacr = Normalizer.normalize(salle, Normalizer.Form.NFD).length() - salle.length();
    int casseMixte = !salle.equals(salle.toUpperCase()) ? 1 : 0;
    return diacr + casseMixte;
  }

  /**
   * Résout les chaînes de remplacement transitives : si A→B et B→C, met à jour A→C.
   *
   * @param remplacements table de remplacement à résoudre sur place
   */
  private static void resoudreChaines(Map<String, String> remplacements) {
    for (String cle : new ArrayList<>(remplacements.keySet())) {
      String valeur = cle;
      while (remplacements.containsKey(valeur) && !remplacements.get(valeur).equals(valeur)) {
        valeur = remplacements.get(valeur);
      }
      remplacements.put(cle, valeur);
    }
  }

  /**
   * Réconcilie les variations mineures de nommage de salle dans un lot et contre la base.
   *
   * <p>Filtre temporel : seuls les concerts à partir de 7 jours avant aujourd'hui sont chargés
   * depuis la base, évitant de ramener des milliers de concerts passés en mémoire.
   *
   * @param lot valeurs de concerts à réconcilier
   * @return lot avec {@code salleNorm} canoniques
   */
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
            "SELECT artiste_norm, date::text AS date, to_char(heure, 'HH24:MI') AS heure, dept,"
                + " salle_norm"
                + " FROM concerts"
                + " WHERE artiste_norm IN (:artistesNorms)"
                + " AND date >= (CURRENT_DATE - INTERVAL '7 days')",
            Map.of("artistesNorms", artistesNorms));

    Map<String, List<String>> indexExistants = new HashMap<>();
    for (Map<String, Object> e : existants) {
      String cle =
          cleReconciliation(e.get("artiste_norm"), e.get("date"), e.get("heure"), e.get("dept"));
      indexExistants.computeIfAbsent(cle, _ -> new ArrayList<>()).add((String) e.get("salle_norm"));
    }

    return reconcilierContreBase(lotApresIntraLot, indexExistants);
  }

  /**
   * Construit la clé de regroupement de la réconciliation de salle.
   *
   * <p>Inclut {@code heure} et {@code dept} en plus d'{@code artisteNorm}/{@code date} — deux
   * productions différentes d'un même titre générique (ex. deux mises en scène indépendantes de
   * "Les Misérables") programmées le même jour dans deux salles distinctes n'ont ni la même heure
   * ni le même département ; sans ces deux champs, {@link CorrespondanceSalle#estDoublonSalle}
   * pouvait rapprocher à tort leurs {@code salleNorm} respectifs.
   *
   * @param artisteNorm nom d'artiste normalisé
   * @param date date du concert
   * @param heure heure du concert au format {@code HH:mm} (voir {@link #formaterHeure}), peut être
   *   {@code null}
   * @param dept département du concert, peut être {@code null}
   * @return clé de regroupement
   */
  private static String cleReconciliation(
      Object artisteNorm, Object date, Object heure, Object dept) {
    return artisteNorm + "|" + date + "|" + heure + "|" + dept;
  }

  /**
   * Formate une heure au même format que la requête SQL ({@code to_char(heure, 'HH24:MI')}) — la
   * précision à la minute suffit à distinguer deux représentations d'un même concert, tout en
   * restant insensible à un éventuel écart de précision entre Java et PostgreSQL.
   *
   * @param heure heure du concert, peut être {@code null}
   * @return heure au format {@code HH:mm}, ou {@code null}
   */
  private static String formaterHeure(LocalTime heure) {
    return heure == null ? null : heure.format(DateTimeFormatter.ofPattern("HH:mm"));
  }

  private Map<String, String> construireRemplacementsIntraLot(List<ValeurConcert> lot) {
    Map<String, String> remplacements = new HashMap<>();
    Map<String, List<String>> parGroupe = new LinkedHashMap<>();
    for (ValeurConcert concert : lot) {
      String cle =
          cleReconciliation(
              concert.artisteNorm(),
              concert.date(),
              formaterHeure(concert.heure()),
              concert.dept());
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
        if (CorrespondanceSalle.estDoublonSalle(a, b)) {
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

  /**
   * Réconcilie un concert contre les salles existantes d'une même clé (artiste, date).
   *
   * <p>Vérifie d'abord une correspondance exacte sur l'ensemble de la liste avant de retomber sur
   * une correspondance floue : sans cette priorité, l'absence de {@code ORDER BY} sur la requête
   * source (voir {@link #reconcilierSalles}) peut placer un candidat flou avant un candidat exact,
   * rattachant à tort le concert à la mauvaise salle alors qu'une correspondance exacte existait
   * plus loin dans la même liste.
   *
   * @param concert concert à réconcilier
   * @param indexExistants noms de salle existants indexés par {@link #cleReconciliation}
   * @return concert réconcilié (salleNorm canonique si un candidat correspond)
   */
  private ValeurConcert reconcilierUnConcert(
      ValeurConcert concert, Map<String, List<String>> indexExistants) {
    String cle =
        cleReconciliation(
            concert.artisteNorm(), concert.date(), formaterHeure(concert.heure()), concert.dept());
    List<String> sallesExistantes = indexExistants.get(cle);
    if (sallesExistantes == null || sallesExistantes.contains(concert.salleNorm())) {
      return concert;
    }
    for (String salleNormExistante : sallesExistantes) {
      if (CorrespondanceSalle.estDoublonSalle(concert.salleNorm(), salleNormExistante)) {
        log.debug(
            "doublon salle inter-jobs réconcilié entrant={} existant={}",
            concert.salleNorm(),
            salleNormExistante);
        return concert.avecSalleNorm(salleNormExistante);
      }
    }
    return concert;
  }

  /**
   * Absorbe les renommages de salle en mettant à jour {@code salles.nom}.
   *
   * @param concerts concerts avec {@code salleSourceId} résolus
   * @param cachesSalles map sourceId → UUID
   */
  void absorberRenommagesSalles(List<ValeurConcert> concerts, Map<String, UUID> cachesSalles) {
    Map<UUID, String> meilleurNom = new HashMap<>();
    Map<UUID, Integer> meilleurScore = new HashMap<>();
    for (ValeurConcert c : concerts) {
      if (c.salleSourceId() == null) continue;
      UUID salleId = cachesSalles.get(c.salleSourceId());
      if (salleId == null) continue;
      int score = scoreSalle(c.salle());
      if (!meilleurNom.containsKey(salleId) || score > meilleurScore.get(salleId)) {
        meilleurNom.put(salleId, c.salle());
        meilleurScore.put(salleId, score);
      }
    }
    for (var entree : meilleurNom.entrySet()) {
      String nouveauNom = entree.getValue();
      int modifies =
          jdbc.update(
              "UPDATE salles SET nom = :nom, nom_norm = :nomNorm"
                  + " WHERE id = CAST(:uuid AS uuid) AND nom <> :nom",
              Map.of(
                  "nom",
                  nouveauNom,
                  "nomNorm",
                  Normalisation.normaliserTexte(nouveauNom),
                  "uuid",
                  entree.getKey().toString()));
      if (modifies > 0) {
        log.info("renommage salle absorbé salleId={} nouveauNom={}", entree.getKey(), nouveauNom);
      }
    }
  }
}

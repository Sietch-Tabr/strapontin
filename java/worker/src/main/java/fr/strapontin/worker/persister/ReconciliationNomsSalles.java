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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Réconciliation des variations mineures de nommage de salle entre
 * fetches successifs — deux passes (intra-lot puis contre la base),
 * {@link CorrespondanceSalle#estDoublonSalle} pour la comparaison, gère
 * aussi l'absorption des renommages détectés par {@code upsertSalleSource}.
 * @since 2.8.7
 * @author Kwaite
 */
@Service
class ReconciliationNomsSalles {

  private static final Logger LOG = LoggerFactory.getLogger(ReconciliationNomsSalles.class);
  private static final int NB_NOMS_SANS_DOUBLON = 1;

  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : instancié uniquement par PersisterConcerts (même
   * package). */
  ReconciliationNomsSalles(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Calcule un score de qualité pour choisir le meilleur nom de salle.
   *
   * @param salle nom brut de la salle
   * @return score (diacritiques favorisés, casse mixte favorisée)
   */
  // equalsIgnoreCase ne convient pas ici : le test porte sur le nom lui-même
  // (déjà entièrement majuscule ou non), pas sur une comparaison insensible à
  // la casse entre deux chaînes distinctes.
  private static int scoreSalle(final String salle) {
    final int diacr = Normalizer.normalize(salle, Normalizer.Form.NFD).length() - salle.length();
    final int casseMixte = salle.equals(salle.toUpperCase(Locale.ROOT)) ? 0 : 1;
    return diacr + casseMixte;
  }

  /**
   * Résout les chaînes de remplacement transitives : si A→B et B→C, met à jour
   * A→C.
   *
   * @param remplacements table de remplacement à résoudre sur place
   */
  private static void resoudreChaines(final Map<String, String> remplacements) {
    for (final String cle : new ArrayList<>(remplacements.keySet())) {
      String valeur = cle;
      while (remplacements.containsKey(valeur) && !remplacements.get(valeur).equals(valeur)) {
        valeur = remplacements.get(valeur);
      }
      remplacements.put(cle, valeur);
    }
  }

  /**
   * Réconcilie les variations mineures de nommage de salle dans un lot et
   * contre la base — filtre temporel 7 jours, évite de ramener des
   * milliers de concerts passés en mémoire.
   * @param lot valeurs de concerts à réconcilier
   * @return lot avec salleNorm canoniques
   */
  /* package-private : appelé par PersisterConcerts (même package). */
  List<ValeurConcert> reconcilierSalles(final List<ValeurConcert> lot) {
    final Map<String, String> remplacementsIntraLot = construireRemplacementsIntraLot(lot);
    final List<ValeurConcert> lotApresIntraLot =
        remplacementsIntraLot.isEmpty()
            ? lot
            : lot.stream().map(c -> appliquerRemplacementSalle(c, remplacementsIntraLot)).toList();

    final Set<String> artistesNorms =
        new HashSet<>(lotApresIntraLot.stream().map(ValeurConcert::artisteNorm).toList());
    if (artistesNorms.isEmpty()) {
      return lotApresIntraLot;
    }

    final List<Map<String, Object>> existants =
        jdbc.queryForList(
            "SELECT artiste_norm, date::text AS date, to_char(heure, 'HH24:MI') AS heure, dept,"
                + " salle_norm"
                + " FROM concerts"
                + " WHERE artiste_norm IN (:artistesNorms)"
                + " AND date >= (CURRENT_DATE - INTERVAL '7 days')",
            Map.of("artistesNorms", artistesNorms));

    final Map<String, List<String>> indexExistants = new HashMap<>();
    for (final Map<String, Object> e : existants) {
      final String cle =
          cleReconciliation(e.get("artiste_norm"), e.get("date"), e.get("heure"), e.get("dept"));
      indexExistants.computeIfAbsent(cle, _ -> new ArrayList<>()).add((String) e.get("salle_norm"));
    }

    return reconcilierContreBase(lotApresIntraLot, indexExistants);
  }

  /**
   * Construit la clé de regroupement (détail : CLAUDE.md, dédoublonnage salle).
   * @param artisteNorm nom d'artiste normalisé
   * @param date date du concert
   * @param heure heure au format HH:mm, peut être null
   * @param dept département du concert, peut être null
   * @return clé de regroupement
   */
  private static String cleReconciliation(
      final Object artisteNorm, final Object date, final Object heure, final Object dept) {
    return artisteNorm + "|" + date + "|" + heure + "|" + dept;
  }

  /**
   * Formate une heure au format {@code to_char(heure, 'HH24:MI')} —
   * précision minute, suffisante et insensible à l'écart Java/PostgreSQL.
   * @param heure heure du concert, peut être null
   * @return heure au format HH:mm, ou null
   */
  private static String formaterHeure(final LocalTime heure) {
    return heure == null ? null : heure.format(DateTimeFormatter.ofPattern("HH:mm"));
  }

  private Map<String, String> construireRemplacementsIntraLot(final List<ValeurConcert> lot) {
    final Map<String, String> remplacements = new HashMap<>();
    final Map<String, List<String>> parGroupe = new LinkedHashMap<>();
    for (final ValeurConcert concert : lot) {
      final String cle =
          cleReconciliation(
              concert.artisteNorm(),
              concert.date(),
              formaterHeure(concert.heure()),
              concert.dept());
      parGroupe.computeIfAbsent(cle, _ -> new ArrayList<>());
      final List<String> groupe = parGroupe.get(cle);
      if (!groupe.contains(concert.salleNorm())) {
        groupe.add(concert.salleNorm());
      }
    }
    for (final List<String> sallesNorm : parGroupe.values()) {
      if (sallesNorm.size() > NB_NOMS_SANS_DOUBLON) {
        ajouterDoublonsSalle(sallesNorm, remplacements);
      }
    }
    resoudreChaines(remplacements);
    return remplacements;
  }

  private void ajouterDoublonsSalle(
      final List<String> sallesNorm, final Map<String, String> remplacements) {
    for (int i = 0; i < sallesNorm.size(); i++) {
      for (int j = i + 1; j < sallesNorm.size(); j++) {
        final String nomA = sallesNorm.get(i);
        final String nomB = sallesNorm.get(j);
        if (CorrespondanceSalle.estDoublonSalle(nomA, nomB)) {
          final String canonique = nomA.length() >= nomB.length() ? nomA : nomB;
          remplacements.put(nomA, canonique);
          remplacements.put(nomB, canonique);
        }
      }
    }
  }

  private ValeurConcert appliquerRemplacementSalle(
      final ValeurConcert concert, final Map<String, String> remplacements) {
    final String canonique = remplacements.get(concert.salleNorm());
    if (canonique == null || canonique.equals(concert.salleNorm())) {
      return concert;
    }
    LOG.debug(
        "doublon salle intra-lot réconcilié entrant={} canonique={}",
        concert.salleNorm(),
        canonique);
    return concert.avecSalleNorm(canonique);
  }

  private List<ValeurConcert> reconcilierContreBase(
      final List<ValeurConcert> lot, final Map<String, List<String>> indexExistants) {
    return lot.stream().map(concert -> reconcilierUnConcert(concert, indexExistants)).toList();
  }

  /**
   * Réconcilie un concert contre les salles existantes d'une même clé
   * (artiste, date) — priorité à la correspondance exacte sur la floue,
   * l'absence d'{@code ORDER BY} pouvant sinon inverser l'ordre.
   * @param concert concert à réconcilier
   * @param indexExistants noms de salle indexés par {@link #cleReconciliation}
   * @return concert réconcilié si un candidat correspond
   */
  private ValeurConcert reconcilierUnConcert(
      final ValeurConcert concert, final Map<String, List<String>> indexExistants) {
    final String cle =
        cleReconciliation(
            concert.artisteNorm(), concert.date(), formaterHeure(concert.heure()), concert.dept());
    final List<String> sallesExistantes = indexExistants.get(cle);
    if (sallesExistantes == null || sallesExistantes.contains(concert.salleNorm())) {
      return concert;
    }
    for (final String salleNormExistante : sallesExistantes) {
      if (CorrespondanceSalle.estDoublonSalle(concert.salleNorm(), salleNormExistante)) {
        LOG.debug(
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
  /* package-private : appelé par PersisterConcerts (même package). */
  void absorberRenommagesSalles(
      final List<ValeurConcert> concerts, final Map<String, UUID> cachesSalles) {
    final Map<UUID, String> meilleurNom = new HashMap<>();
    final Map<UUID, Integer> meilleurScore = new HashMap<>();
    for (final ValeurConcert c : concerts) {
      if (c.salleSourceId() == null) {
        continue;
      }
      final UUID salleId = cachesSalles.get(c.salleSourceId());
      if (salleId == null) {
        continue;
      }
      final int score = scoreSalle(c.salle());
      if (!meilleurNom.containsKey(salleId) || score > meilleurScore.get(salleId)) {
        meilleurNom.put(salleId, c.salle());
        meilleurScore.put(salleId, score);
      }
    }
    for (final Map.Entry<UUID, String> entree : meilleurNom.entrySet()) {
      final String nouveauNom = entree.getValue();
      final int modifies =
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
        LOG.info("renommage salle absorbé salleId={} nouveauNom={}", entree.getKey(), nouveauNom);
      }
    }
  }
}

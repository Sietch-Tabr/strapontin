/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceArtiste;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Service de réconciliation des noms d'artiste et de résolution des {@code artiste_id}.
 *
 * <p>Deux passes de réconciliation : intra-lot (variantes dans le même fetch) puis contre la base
 * (variantes entre deux fetches). Utilise {@link CorrespondanceArtiste#estDoublonArtiste} (module
 * {@code core}, partagé avec {@code web} — voir {@code ArtisteService.ajouter}) pour déterminer si
 * deux noms désignent le même artiste.
 *
 * <p>La résolution par nom ({@link #resoudreArtisteParNomNorm}) couvre toutes les sources sans
 * {@code artiste_id}, y compris Digitick qui ne fournit pas d'identifiant artiste externe.
 *
 * @since 2.5.7
 * @author Kwaite
 */
@Service
public class ReconciliateurArtistes {

  private static final Logger LOG = LoggerFactory.getLogger(ReconciliateurArtistes.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final CorrecteurArtisteParUrl correcteurArtisteParUrl;
  private final ResolveurArtisteParNomNorm resolveurArtisteParNomNorm;

  private record ArtisteExistant(String artisteNorm, UUID artisteId) {}

  /**
   * @param jdbc template JDBC injecté par Spring
   */
  public ReconciliateurArtistes(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.correcteurArtisteParUrl = new CorrecteurArtisteParUrl(jdbc);
    this.resolveurArtisteParNomNorm = new ResolveurArtisteParNomNorm(jdbc);
  }

  /**
   * Vérifie si deux noms d'artiste normalisés désignent le même artiste.
   *
   * @param a premier nom normalisé
   * @param b deuxième nom normalisé
   * @return {@code true} si les artistes sont considérés identiques
   * @see CorrespondanceArtiste#estDoublonArtiste
   */
  public static boolean estDoublonArtiste(final String a, final String b) {
    return CorrespondanceArtiste.estDoublonArtiste(a, b);
  }

  /**
   * Résout les chaînes de remplacement transitives : si A→B et B→C, met à jour A→C.
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
   * Réconcilie les variations mineures de nommage d'artiste dans un lot et contre la base.
   *
   * <p>Le regroupement se fait par {@code (date, salleNorm)} — déjà canonique à ce stade puisque
   * cette méthode s'exécute après {@code ReconciliateurSalles} — plutôt que par {@code
   * artisteNorm}, qui est précisément la valeur à corriger.
   *
   * <p>Filtre temporel : seuls les concerts à partir de 7 jours avant aujourd'hui sont chargés
   * depuis la base.
   *
   * @param lot valeurs de concerts après réconciliation des salles
   * @return lot avec {@code artisteNorm} réconcilié
   */
  List<ValeurConcert> reconcilierArtistes(final List<ValeurConcert> lot) {
    final Map<String, String> remplacementsIntraLot = construireRemplacementsArtisteIntraLot(lot);
    final List<ValeurConcert> lotApresIntraLot =
        remplacementsIntraLot.isEmpty()
            ? lot
            : lot.stream()
                .map(c -> appliquerRemplacementArtiste(c, remplacementsIntraLot))
                .toList();

    final Set<String> sallesNorms =
        new HashSet<>(lotApresIntraLot.stream().map(ValeurConcert::salleNorm).toList());
    if (sallesNorms.isEmpty()) {
      return lotApresIntraLot;
    }

    final List<Map<String, Object>> existants =
        jdbc.queryForList(
            "SELECT artiste_norm, artiste_id, date::text AS date, salle_norm FROM concerts"
                + " WHERE salle_norm IN (:sallesNorms)"
                + " AND date >= (CURRENT_DATE - INTERVAL '7 days')",
            Map.of("sallesNorms", sallesNorms));

    final Map<String, List<ArtisteExistant>> indexExistants = new HashMap<>();
    for (final Map<String, Object> e : existants) {
      final String cle = e.get("date") + "|" + e.get("salle_norm");
      indexExistants
          .computeIfAbsent(cle, _ -> new ArrayList<>())
          .add(new ArtisteExistant((String) e.get("artiste_norm"), (UUID) e.get("artiste_id")));
    }

    return lotApresIntraLot.stream()
        .map(concert -> reconcilierUnArtiste(concert, indexExistants))
        .toList();
  }

  /**
   * Corrige un artiste corrompu déjà persisté en s'ancrant sur l'identité de l'URL de l'événement
   * plutôt que sur la similarité de nom.
   *
   * <p>{@link #reconcilierUnArtiste} ne guérit une ligne existante que si {@link
   * CorrespondanceArtiste#estDoublonArtiste} relie encore l'ancien nom corrompu au nouveau — un
   * mauvais rapprochement Deezer peut produire un nom sans aucune parenté textuelle avec l'original
   * (ex. "nino arial" → "nino arbol"), rendant cette guérison structurellement impossible. Deux
   * fiches de la même URL désignent nécessairement le même événement réel : cette voie corrige donc
   * la ligne existante indépendamment de toute similarité de nom, dès que l'entrant est inéligible
   * à la résolution Deezer (nom brut fiable par construction).
   *
   * @param lot concerts après résolution des identités sources
   */
  void corrigerParUrl(final List<ValeurConcert> lot) {
    correcteurArtisteParUrl.corrigerParUrl(lot);
  }

  private Map<String, String> construireRemplacementsArtisteIntraLot(
      final List<ValeurConcert> lot) {
    final Map<String, String> remplacements = new HashMap<>();
    final Map<String, List<String>> parGroupe = new LinkedHashMap<>();
    for (final ValeurConcert concert : lot) {
      final String cle = concert.date() + "|" + concert.salleNorm();
      parGroupe.computeIfAbsent(cle, _ -> new ArrayList<>());
      final List<String> groupe = parGroupe.get(cle);
      if (!groupe.contains(concert.artisteNorm())) {
        groupe.add(concert.artisteNorm());
      }
    }
    for (final List<String> artistesNorm : parGroupe.values()) {
      if (artistesNorm.size() > 1) {
        ajouterDoublonsArtiste(artistesNorm, remplacements);
      }
    }
    resoudreChaines(remplacements);
    return remplacements;
  }

  private void ajouterDoublonsArtiste(
      final List<String> artistesNorm, final Map<String, String> remplacements) {
    for (int i = 0; i < artistesNorm.size(); i++) {
      for (int j = i + 1; j < artistesNorm.size(); j++) {
        final String a = artistesNorm.get(i);
        final String b = artistesNorm.get(j);
        if (estDoublonArtiste(a, b)) {
          final String canonique = a.length() >= b.length() ? a : b;
          remplacements.put(a, canonique);
          remplacements.put(b, canonique);
        }
      }
    }
  }

  private ValeurConcert appliquerRemplacementArtiste(
      final ValeurConcert c, final Map<String, String> remplacements) {
    final String canonique = remplacements.get(c.artisteNorm());
    if (canonique == null || canonique.equals(c.artisteNorm())) {
      return c;
    }
    LOG.debug(
        "doublon artiste intra-lot réconcilié entrant={} canonique={}", c.artisteNorm(), canonique);
    return c.avecArtisteNorm(canonique);
  }

  /**
   * Réconcilie un concert contre les candidats existants d'une même clé (date, salle).
   *
   * <p>Scanne toute la liste à la recherche d'une correspondance exacte avant de retomber sur une
   * correspondance floue : sans cette priorité, l'absence de {@code ORDER BY} sur la requête source
   * (voir {@link #reconcilierArtistes}) peut placer un candidat flou avant un candidat exact dans
   * le result set, faisant rattacher à tort le concert au mauvais artiste alors qu'une
   * correspondance exacte existait plus loin dans la même liste.
   *
   * @param concert concert à réconcilier
   * @param indexExistants candidats existants indexés par {@code date|salleNorm}
   * @return concert réconcilié (artisteNorm/artisteId propagés si un candidat correspond)
   */
  private ValeurConcert reconcilierUnArtiste(
      final ValeurConcert concert, final Map<String, List<ArtisteExistant>> indexExistants) {
    final String cle = concert.date() + "|" + concert.salleNorm();
    final List<ArtisteExistant> artistesExistants = indexExistants.get(cle);
    if (artistesExistants == null) {
      return concert;
    }

    final ValeurConcert exact = reconcilierParCorrespondanceExacte(concert, artistesExistants);
    if (exact != null) {
      return exact;
    }
    final ValeurConcert flou = reconcilierParCorrespondanceFloue(concert, artistesExistants);
    return flou != null ? flou : concert;
  }

  /**
   * @return le concert propageant l'{@code artisteId} existant si un {@code artisteNorm}
   *     strictement identique est trouvé, ou {@code null} si aucun candidat exact n'existe.
   */
  private static ValeurConcert reconcilierParCorrespondanceExacte(
      final ValeurConcert concert, final List<ArtisteExistant> artistesExistants) {
    for (final ArtisteExistant existant : artistesExistants) {
      if (existant.artisteNorm().equals(concert.artisteNorm())) {
        if (concert.artisteId() == null && existant.artisteId() != null) {
          return concert.avecArtisteId(existant.artisteId());
        }
        return concert;
      }
    }
    return null;
  }

  /**
   * @return le concert réconcilié avec le premier candidat flou trouvé ({@link
   *     CorrespondanceArtiste#estDoublonArtiste}), ou {@code null} si aucun candidat flou n'existe.
   */
  private ValeurConcert reconcilierParCorrespondanceFloue(
      final ValeurConcert concert, final List<ArtisteExistant> artistesExistants) {
    for (final ArtisteExistant existant : artistesExistants) {
      if (!estDoublonArtiste(concert.artisteNorm(), existant.artisteNorm())) {
        continue;
      }

      final UUID artisteIdPropague =
          concert.artisteId() == null ? existant.artisteId() : concert.artisteId();

      // Un concert inéligible à la résolution Deezer (théâtre, spectacle "divers", sport — voir
      // EligibiliteResolutionDeezer) n'est jamais passé par cette résolution : son artisteNorm
      // vient directement de la source, fiable par construction. Le corriger rétroactivement en
      // base plutôt que de forcer l'entrant vers l'ancienne valeur permet à une correction de la
      // cause racine (ex. #1063 sur la résolution Deezer elle-même) de guérir une ligne déjà
      // persistée sous une identité erronée, au lieu de rester bloquée indéfiniment par ce même
      // mécanisme de convergence. Pour un concert éligible, le comportement protecteur historique
      // est conservé (l'existant l'emporte) : la résolution Deezer reste sujette à un mauvais
      // rapprochement futur (cf. Van Halen, #770), qu'il ne faut pas laisser écraser une valeur
      // déjà correcte en base.
      if (!EligibiliteResolutionDeezer.estEligible(concert.classification(), concert.source())) {
        corrigerArtisteExistantEnBase(existant, concert);
        return concert.avecArtisteId(artisteIdPropague);
      }

      LOG.debug(
          "doublon artiste inter-jobs réconcilié entrant={} existant={} artisteId={}",
          concert.artisteNorm(),
          existant.artisteNorm(),
          artisteIdPropague);
      return concert.avecArtisteNormEtId(existant.artisteNorm(), artisteIdPropague);
    }
    return null;
  }

  /**
   * Corrige rétroactivement une ligne déjà persistée sous une identité d'artiste erronée, désormais
   * démentie par un fetch fiable (concert inéligible à la résolution Deezer).
   *
   * @param existant ancienne identité (erronée) à corriger
   * @param concert concert entrant portant l'identité corrigée
   */
  private void corrigerArtisteExistantEnBase(
      final ArtisteExistant existant, final ValeurConcert concert) {
    final MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("nouvelArtiste", concert.artiste())
            .addValue("nouvelArtisteNorm", concert.artisteNorm())
            .addValue("ancienArtisteNorm", existant.artisteNorm())
            .addValue("date", concert.date().toString())
            .addValue("salleNorm", concert.salleNorm());
    // Identité (evenements.artiste_norm) et affichage par source (observations.artiste_brut)
    // vivent désormais sur deux tables distinctes (#1192).
    final int nbCorrigees =
        jdbc.update(
            "UPDATE evenements SET artiste_norm = :nouvelArtisteNorm, updated_at = now()"
                + " WHERE artiste_norm = :ancienArtisteNorm AND date = CAST(:date AS date)"
                + " AND salle_norm = :salleNorm",
            params);
    if (nbCorrigees > 0) {
      jdbc.update(
          "UPDATE observations SET artiste_brut = :nouvelArtiste, updated_at = now()"
              + " WHERE evenement_id IN (SELECT id FROM evenements"
              + "   WHERE artiste_norm = :nouvelArtisteNorm AND date = CAST(:date AS date)"
              + "     AND salle_norm = :salleNorm)",
          params);
      LOG.info(
          "artiste corrigé rétroactivement ancien={} nouveau={} count={}",
          existant.artisteNorm(),
          concert.artisteNorm(),
          nbCorrigees);
    }
  }

  /**
   * Résout les {@code artiste_id} manquants par lookup sur {@code artistes.nom_norm}.
   *
   * <p>Couvre toutes les sources sans {@code artiste_id}, y compris Digitick qui ne fournit pas
   * d'identifiant artiste externe ({@code artisteSourceId == null}). Les mappings trouvés pour les
   * sources avec identifiant externe sont persistés dans {@code artiste_sources} (ON CONFLICT DO
   * NOTHING) pour accélérer les fetches suivants.
   *
   * @param concerts concerts après résolution par source
   * @return liste enrichie avec les {@code artiste_id} résolus par nom
   */
  List<ValeurConcert> resoudreArtisteParNomNorm(final List<ValeurConcert> concerts) {
    return resolveurArtisteParNomNorm.resoudre(concerts);
  }
}

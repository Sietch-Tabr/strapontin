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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service de réconciliation des noms d'artiste et de résolution des {@code artiste_id}.
///
/// <p>Deux passes de réconciliation : intra-lot (variantes dans le même fetch) puis
/// contre la base (variantes entre deux fetches). Utilise
/// {@link CorrespondanceArtiste#estDoublonArtiste} (module {@code core}, partagé avec {@code web}
/// — voir {@code ArtisteService.ajouter}) pour déterminer si deux noms désignent le même artiste.
///
/// <p>La résolution par nom ({@link #resoudreArtisteParNomNorm}) couvre toutes les
/// sources sans {@code artiste_id}, y compris Digitick qui ne fournit pas
/// d'identifiant artiste externe.
///
/// @since 2.5.7
/// @author Kwaite
@Service
public class ReconciliateurArtistes {

  private static final Logger log = LoggerFactory.getLogger(ReconciliateurArtistes.class);

  private record ArtisteExistant(String artisteNorm, UUID artisteId) {}

  private final NamedParameterJdbcTemplate jdbc;

  /// @param jdbc template JDBC injecté par Spring
  public ReconciliateurArtistes(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Vérifie si deux noms d'artiste normalisés désignent le même artiste.
  ///
  /// @param a premier nom normalisé
  /// @param b deuxième nom normalisé
  /// @return {@code true} si les artistes sont considérés identiques
  /// @see CorrespondanceArtiste#estDoublonArtiste
  public static boolean estDoublonArtiste(String a, String b) {
    return CorrespondanceArtiste.estDoublonArtiste(a, b);
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

  /// Réconcilie les variations mineures de nommage d'artiste dans un lot et contre la base.
  ///
  /// <p>Le regroupement se fait par {@code (date, salleNorm)} — déjà canonique à ce stade
  /// puisque cette méthode s'exécute après {@code ReconciliateurSalles} — plutôt que par
  /// {@code artisteNorm}, qui est précisément la valeur à corriger.
  ///
  /// <p>Filtre temporel : seuls les concerts à partir de 7 jours avant aujourd'hui sont
  /// chargés depuis la base.
  ///
  /// @param lot valeurs de concerts après réconciliation des salles
  /// @return lot avec {@code artisteNorm} réconcilié
  List<ValeurConcert> reconcilierArtistes(List<ValeurConcert> lot) {
    Map<String, String> remplacementsIntraLot = construireRemplacementsArtisteIntraLot(lot);
    List<ValeurConcert> lotApresIntraLot =
        remplacementsIntraLot.isEmpty()
            ? lot
            : lot.stream()
                .map(c -> appliquerRemplacementArtiste(c, remplacementsIntraLot))
                .toList();

    Set<String> sallesNorms =
        new HashSet<>(lotApresIntraLot.stream().map(ValeurConcert::salleNorm).toList());
    if (sallesNorms.isEmpty()) return lotApresIntraLot;

    List<Map<String, Object>> existants =
        jdbc.queryForList(
            "SELECT artiste_norm, artiste_id, date::text AS date, salle_norm FROM concerts"
                + " WHERE salle_norm IN (:sallesNorms)"
                + " AND date >= (CURRENT_DATE - INTERVAL '7 days')",
            Map.of("sallesNorms", sallesNorms));

    Map<String, List<ArtisteExistant>> indexExistants = new HashMap<>();
    for (Map<String, Object> e : existants) {
      String cle = e.get("date") + "|" + e.get("salle_norm");
      indexExistants
          .computeIfAbsent(cle, _ -> new ArrayList<>())
          .add(new ArtisteExistant((String) e.get("artiste_norm"), (UUID) e.get("artiste_id")));
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
    resoudreChaines(remplacements);
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
    return c.avecArtisteNorm(canonique);
  }

  /// Réconcilie un concert contre les candidats existants d'une même clé (date, salle).
  ///
  /// <p>Scanne toute la liste à la recherche d'une correspondance exacte avant de retomber sur
  /// une correspondance floue (#794) : sans cette priorité, l'absence de {@code ORDER BY} sur la
  /// requête source (voir {@link #reconcilierArtistes}) peut placer un candidat flou avant un
  /// candidat exact dans le result set, faisant rattacher à tort le concert au mauvais artiste
  /// alors qu'une correspondance exacte existait plus loin dans la même liste.
  ///
  /// @param concert        concert à réconcilier
  /// @param indexExistants candidats existants indexés par {@code date|salleNorm}
  /// @return concert réconcilié (artisteNorm/artisteId propagés si un candidat correspond)
  private ValeurConcert reconcilierUnArtiste(
      ValeurConcert concert, Map<String, List<ArtisteExistant>> indexExistants) {
    String cle = concert.date() + "|" + concert.salleNorm();
    List<ArtisteExistant> artistesExistants = indexExistants.get(cle);
    if (artistesExistants == null) return concert;

    for (ArtisteExistant existant : artistesExistants) {
      if (existant.artisteNorm().equals(concert.artisteNorm())) {
        if (concert.artisteId() == null && existant.artisteId() != null) {
          return concert.avecArtisteId(existant.artisteId());
        }
        return concert;
      }
    }
    for (ArtisteExistant existant : artistesExistants) {
      if (estDoublonArtiste(concert.artisteNorm(), existant.artisteNorm())) {
        UUID artisteIdPropague =
            concert.artisteId() == null ? existant.artisteId() : concert.artisteId();
        log.debug(
            "doublon artiste inter-jobs réconcilié entrant={} existant={} artisteId={}",
            concert.artisteNorm(),
            existant.artisteNorm(),
            artisteIdPropague);
        return concert.avecArtisteNormEtId(existant.artisteNorm(), artisteIdPropague);
      }
    }
    return concert;
  }

  /// Résout les {@code artiste_id} manquants par lookup sur {@code artistes.nom_norm}.
  ///
  /// <p>Couvre toutes les sources sans {@code artiste_id}, y compris Digitick qui ne
  /// fournit pas d'identifiant artiste externe ({@code artisteSourceId == null}). Les
  /// mappings trouvés pour les sources avec identifiant externe sont persistés dans
  /// {@code artiste_sources} (ON CONFLICT DO NOTHING) pour accélérer les fetches suivants.
  ///
  /// @param concerts concerts après résolution par source
  /// @return liste enrichie avec les {@code artiste_id} résolus par nom
  List<ValeurConcert> resoudreArtisteParNomNorm(List<ValeurConcert> concerts) {
    List<String> nomsNormSansId =
        concerts.stream()
            .filter(c -> c.artisteId() == null)
            .map(ValeurConcert::artisteNorm)
            .distinct()
            .toList();
    if (nomsNormSansId.isEmpty()) return concerts;

    List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT id, nom_norm FROM artistes WHERE nom_norm IN (:nomsNorm)",
            Map.of("nomsNorm", nomsNormSansId));

    Map<String, UUID> parNomNorm = new HashMap<>();
    for (Map<String, Object> ligne : lignes) {
      parNomNorm.put((String) ligne.get("nom_norm"), (UUID) ligne.get("id"));
    }
    if (parNomNorm.isEmpty()) return concerts;

    List<ValeurConcert> enrichis =
        concerts.stream()
            .map(
                c -> {
                  if (c.artisteId() != null) return c;
                  UUID artisteId = parNomNorm.get(c.artisteNorm());
                  return artisteId != null ? c.avecArtisteId(artisteId) : c;
                })
            .toList();

    persistersArtistesSources(enrichis, parNomNorm.keySet());
    return enrichis;
  }

  /// Persiste dans {@code artiste_sources} les mappings nouvellement résolus par nom.
  ///
  /// <p>N'insère que les concerts dont {@code artisteId} vient d'être résolu (nom_norm présent
  /// dans {@code nomNormsResolus}) et dont la source externe est connue
  /// ({@code artisteSourceId != null} — Digitick n'en a pas).
  ///
  /// @param concerts       concerts après résolution par nom
  /// @param nomNormsResolus noms normalisés effectivement résolus par le lookup
  private void persistersArtistesSources(
      List<ValeurConcert> concerts, java.util.Set<String> nomNormsResolus) {
    for (ValeurConcert c : concerts) {
      if (c.artisteId() == null
          || c.artisteSourceId() == null
          || !nomNormsResolus.contains(c.artisteNorm())) continue;
      int sep = c.artisteSourceId().indexOf(':');
      if (sep <= 0) continue;
      String source = c.artisteSourceId().substring(0, sep);
      String identifiant = c.artisteSourceId().substring(sep + 1);
      jdbc.update(
          "INSERT INTO artiste_sources (artiste_id, source, identifiant_externe)"
              + " VALUES (CAST(:artisteId AS uuid), :source, :identifiant)"
              + " ON CONFLICT DO NOTHING",
          Map.of(
              "artisteId", c.artisteId().toString(),
              "source", source,
              "identifiant", identifiant));
      log.info(
          "artiste source persisté artisteId={} source={} identifiant={}",
          c.artisteId(),
          source,
          identifiant);
    }
  }
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/// Orchestrateur de persistance des concerts dédoublonnés.
///
/// <p>Délègue les responsabilités spécialisées à trois services :
/// <ul>
///   <li>{@link ReconciliateurSalles} — réconciliation des noms de salle</li>
///   <li>{@link ReconciliateurArtistes} — réconciliation des noms d'artiste</li>
///   <li>{@link PersisteurConcertsDb} — opérations SQL (upsert, doublons, UUID)</li>
/// </ul>
///
/// <p>Traitement par lots de 500. Pour chaque lot :
/// <ol>
///   <li>Résolution des UUIDs par identifiants sources ({@code salle_sources},
///       {@code artiste_sources})</li>
///   <li>Première réconciliation des noms de salle (intra-lot puis contre la base)</li>
///   <li>Réconciliation des noms d'artiste (intra-lot puis contre la base)</li>
///   <li>Deuxième réconciliation des noms de salle (couvre les variantes simultanées)</li>
///   <li>Déduplication sur la clé de conflit finale</li>
///   <li>Rattachement des salles par {@code nom_norm} exact</li>
///   <li>Upsert ON CONFLICT avec préférence pour les diacritiques et la casse mixte</li>
///   <li>Suppression des doublons par URL et par {@code salle_uuid}</li>
/// </ol>
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class PersisterConcerts {

  private static final Logger log = LoggerFactory.getLogger(PersisterConcerts.class);

  private static final int TAILLE_LOT = 500;

  private final PersisteurConcertsDb db;
  private final ReconciliateurSalles reconciliateurSalles;
  private final ReconciliateurArtistes reconciliateurArtistes;

  /// @param db                    service de persistance SQL
  /// @param reconciliateurSalles  service de réconciliation des salles
  /// @param reconciliateurArtistes service de réconciliation des artistes
  public PersisterConcerts(
      PersisteurConcertsDb db,
      ReconciliateurSalles reconciliateurSalles,
      ReconciliateurArtistes reconciliateurArtistes) {
    this.db = db;
    this.reconciliateurSalles = reconciliateurSalles;
    this.reconciliateurArtistes = reconciliateurArtistes;
  }

  /// Vérifie si deux noms de salle normalisés désignent la même salle.
  ///
  /// @param a premier nom normalisé
  /// @param b deuxième nom normalisé
  /// @return {@code true} si les salles sont considérées identiques
  /// @see ReconciliateurSalles#estDoublonSalle
  public static boolean estDoublonSalle(String a, String b) {
    return ReconciliateurSalles.estDoublonSalle(a, b);
  }

  /// Vérifie si deux noms d'artiste normalisés désignent le même artiste.
  ///
  /// @param a premier nom normalisé
  /// @param b deuxième nom normalisé
  /// @return {@code true} si les artistes sont considérés identiques
  /// @see ReconciliateurArtistes#estDoublonArtiste
  public static boolean estDoublonArtiste(String a, String b) {
    return ReconciliateurArtistes.estDoublonArtiste(a, b);
  }

  /// Calcule le coefficient de Jaccard sur les tokens des deux chaînes.
  ///
  /// @param a première chaîne normalisée
  /// @param b deuxième chaîne normalisée
  /// @return coefficient de Jaccard entre 0.0 et 1.0
  /// @see ReconciliateurSalles#jaccardTokens
  public static double jaccardTokens(String a, String b) {
    return ReconciliateurSalles.jaccardTokens(a, b);
  }

  private static int scoreArtisteSalle(String artiste, String salle) {
    int artisteDiacr =
        Normalizer.normalize(artiste, Normalizer.Form.NFD).length() - artiste.length();
    int salleDiacr = Normalizer.normalize(salle, Normalizer.Form.NFD).length() - salle.length();
    int artisteCasseMixte = !artiste.equals(artiste.toUpperCase()) ? 5 : 0;
    int salleCasseMixte = !salle.equals(salle.toUpperCase()) ? 1 : 0;
    return artisteDiacr * 10 + artisteCasseMixte + salleDiacr + salleCasseMixte;
  }

  /// Dédoublonne par clé de conflit {@code (artiste_norm, date, heure, salle_norm)}.
  ///
  /// <p>Deux concerts du même artiste dans la même salle le même jour avec des heures
  /// différentes (matinée + soirée) sont considérés distincts. Deux concerts sans heure
  /// (null) sont considérés identiques et fusionnés. En cas de doublon, conserve la version
  /// avec le meilleur score (diacritiques et casse mixte préférés).
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
              + (c.heure() != null ? c.heure() : "")
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

  /// Dédoublonne un lot réconcilié par {@code (artisteNorm, date, heure, salleNorm)}.
  ///
  /// <p>Les réécritures de {@code salleNorm} par {@link ReconciliateurSalles} peuvent créer
  /// de nouveaux doublons intra-lot. Cette passe les élimine.
  ///
  /// @param lot valeurs de concerts après réconciliation des salles
  /// @return lot sans doublons
  public static List<ValeurConcert> deduplicerLotReconcilie(List<ValeurConcert> lot) {
    Map<String, ValeurConcert> parCle = new LinkedHashMap<>();
    for (ValeurConcert v : lot) {
      String cle =
          v.artisteNorm()
              + "|"
              + v.date()
              + "|"
              + (v.heure() != null ? v.heure() : "")
              + "|"
              + v.salleNorm();
      ValeurConcert existant = parCle.get(cle);
      if (existant == null
          || scoreArtisteSalle(v.artiste(), v.salle())
              > scoreArtisteSalle(existant.artiste(), existant.salle())) {
        parCle.put(cle, v);
      }
    }
    return new ArrayList<>(parCle.values());
  }

  /// Résout les UUIDs internes depuis les identifiants sources préfixés.
  ///
  /// <p>Pour chaque concert portant un {@code salleSourceId} ou {@code artisteSourceId},
  /// consulte {@code salle_sources} et {@code artiste_sources} pour trouver l'UUID stable.
  /// En cas de renommage de salle, met à jour {@code salles.nom}.
  ///
  /// <p>Pour les salles TM dont le {@code salleSourceId} est absent de {@code salle_sources},
  /// effectue un upsert dans {@code salles} et {@code salle_sources}.
  ///
  /// <p>Pour les concerts sans {@code artiste_id} après résolution par source (y compris
  /// les concerts Digitick sans {@code artisteSourceId}), effectue un lookup batch par
  /// {@code nom_norm} dans la table {@code artistes}.
  ///
  /// @param concerts valeurs normalisées avant upsert
  /// @return liste enrichie avec les UUIDs résolus
  private List<ValeurConcert> resoudreIdentitesSources(List<ValeurConcert> concerts) {
    Map<String, UUID> cachesSalles = db.resoudreUuidsParSource(concerts, "salle");
    Map<String, UUID> cachesArtistes = db.resoudreUuidsParSource(concerts, "artiste");
    reconciliateurSalles.absorberRenommagesSalles(concerts, cachesSalles);

    for (ValeurConcert c : concerts) {
      if (c.salleSourceId() != null && cachesSalles.get(c.salleSourceId()) == null) {
        UUID uuid = reconciliateurSalles.upsertSalleSource(c);
        if (uuid != null) cachesSalles.put(c.salleSourceId(), uuid);
      }
    }

    List<ValeurConcert> resolus =
        concerts.stream()
            .map(
                c -> {
                  UUID salleUuid =
                      c.salleSourceId() != null ? cachesSalles.get(c.salleSourceId()) : null;
                  UUID artisteId =
                      c.artisteSourceId() != null ? cachesArtistes.get(c.artisteSourceId()) : null;
                  if (salleUuid == null && artisteId == null) return c;
                  return c.avecUuids(
                      salleUuid != null ? salleUuid : c.salleUuid(),
                      artisteId != null ? artisteId : c.artisteId());
                })
            .toList();

    return reconciliateurArtistes.resoudreArtisteParNomNorm(resolus);
  }

  /// Résultat de la persistance d'un lot de concerts.
  ///
  /// @param nbInseres   nombre de concerts insérés
  /// @param nbMisAJour  nombre de concerts mis à jour
  public record ResultatPersistance(int nbInseres, int nbMisAJour) {}

  /// Persiste les concerts dédoublonnés en base via upsert par lots de {@value #TAILLE_LOT}.
  ///
  /// @param liste concerts à persister
  /// @return résultat avec nombre d'insertions et de mises à jour
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

      List<ValeurConcert> valeurs = resoudreIdentitesSources(valeursBrutes);
      valeurs = db.resoudreParUrl(valeurs);
      valeurs = reconciliateurSalles.reconcilierSalles(valeurs);
      valeurs = reconciliateurArtistes.reconcilierArtistes(valeurs);
      // Deuxième passe salle : couvre le cas où artiste_norm et salle_norm varient simultanément.
      valeurs = reconciliateurSalles.reconcilierSalles(valeurs);
      valeurs = deduplicerLotReconcilie(valeurs);
      valeurs = reconciliateurSalles.raccrocherSallesParNomNorm(valeurs);

      ResultatPersistance resultatLot = db.upsertLot(valeurs);
      totalInserts += resultatLot.nbInseres();
      totalUpdates += resultatLot.nbMisAJour();
    }

    db.supprimerDoublonsParUrl();
    db.supprimerDoublonsParSalleUuid();

    log.info(
        "concerts persistés total={} inserts={} updates={}",
        totalInserts + totalUpdates,
        totalInserts,
        totalUpdates);
    return new ResultatPersistance(totalInserts, totalUpdates);
  }
}

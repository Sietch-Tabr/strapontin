/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import fr.strapontin.worker.LogsAdminWorker;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrateur de persistance des concerts dédoublonnés.
 *
 * <p>Délègue les responsabilités spécialisées à trois services : <ul>
 *   <li>{@link ReconciliateurSalles} — réconciliation des noms de salle
 *   <li>{@link ReconciliateurArtistes} — réconciliation des noms d'artiste
 *   <li>{@link PersisteurConcertsDb} — opérations SQL (résolution d'événement, upsert, UUID) </ul>
 *
 * <p>Traitement par lots de 500. Pour chaque lot : <ol>
 *   <li>Résolution des UUIDs par identifiants sources ({@code salle_sources}, {@code
 *       artiste_sources})
 *   <li>Première réconciliation des noms de salle (intra-lot puis contre la base)
 *   <li>Réconciliation des noms d'artiste (intra-lot puis contre la base)
 *   <li>Deuxième réconciliation des noms de salle (couvre les variantes simultanées)
 *   <li>Déduplication sur la clé de conflit finale
 *   <li>Rattachement des salles par {@code nom_norm} exact
 *   <li>Résolution de l'événement canonique (existant ou nouveau) puis upsert de l'observation de
 *       la source (#1192, plus de suppression a posteriori) </ol>
 *
 * @since 2.0.0
 * @author Kwaite
 */
@Service
public class PersisterConcerts {

  private static final Logger LOG = LoggerFactory.getLogger(PersisterConcerts.class);

  private static final int TAILLE_LOT = 500;

  private final PersisteurConcertsDb db;
  private final ReconciliateurSalles reconciliateurSalles;
  private final ReconciliateurArtistes reconciliateurArtistes;
  private final LogsAdminWorker logsAdminWorker;
  private PersisterConcerts self;

  /**
   * @param db service de persistance SQL
   * @param reconciliateurSalles service de réconciliation des salles
   * @param reconciliateurArtistes service de réconciliation des artistes
   * @param logsAdminWorker journalisation admin
   */
  public PersisterConcerts(
      final PersisteurConcertsDb db,
      final ReconciliateurSalles reconciliateurSalles,
      final ReconciliateurArtistes reconciliateurArtistes,
      final LogsAdminWorker logsAdminWorker) {
    this.db = db;
    this.reconciliateurSalles = reconciliateurSalles;
    this.reconciliateurArtistes = reconciliateurArtistes;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Injection par setter (plutôt que constructeur) de la référence proxifiée de ce bean, pour
   * éviter le problème d'auto-référence à la construction — l'appel à {@link
   * #persisterLotTransactionnel} passe ainsi par le proxy AOP Spring et commit sa propre
   * transaction par sous-lot, plutôt qu'une transaction unique englobant toute la boucle. En
   * dehors d'un contexte Spring (tests unitaires n'exerçant pas ce chemin), {@code self} reste
   * {@code null} sans effet — seul {@link #persisterConcerts} avec une liste non vide l'exerce.
   *
   * @param self référence proxifiée de ce bean ({@code @Lazy})
   */
  @Autowired
  public void setSelf(@Lazy final PersisterConcerts self) {
    this.self = self;
  }

  /**
   * Vérifie si deux noms de salle normalisés désignent la même salle.
   *
   * @param a premier nom normalisé
   * @param b deuxième nom normalisé
   * @return {@code true} si les salles sont considérées identiques
   * @see ReconciliateurSalles#estDoublonSalle
   */
  public static boolean estDoublonSalle(final String a, final String b) {
    return ReconciliateurSalles.estDoublonSalle(a, b);
  }

  /**
   * Vérifie si deux noms d'artiste normalisés désignent le même artiste.
   *
   * @param a premier nom normalisé
   * @param b deuxième nom normalisé
   * @return {@code true} si les artistes sont considérés identiques
   * @see ReconciliateurArtistes#estDoublonArtiste
   */
  public static boolean estDoublonArtiste(final String a, final String b) {
    return ReconciliateurArtistes.estDoublonArtiste(a, b);
  }

  /**
   * Calcule le coefficient de Jaccard sur les tokens des deux chaînes.
   *
   * @param a première chaîne normalisée
   * @param b deuxième chaîne normalisée
   * @return coefficient de Jaccard entre 0.0 et 1.0
   * @see ReconciliateurSalles#jaccardTokens
   */
  public static double jaccardTokens(final String a, final String b) {
    return ReconciliateurSalles.jaccardTokens(a, b);
  }

  private static int scoreArtisteSalle(final String artiste, final String salle) {
    final int artisteDiacr =
        Normalizer.normalize(artiste, Normalizer.Form.NFD).length() - artiste.length();
    final int salleDiacr =
        Normalizer.normalize(salle, Normalizer.Form.NFD).length() - salle.length();
    final int artisteCasseMixte = artiste.equals(artiste.toUpperCase()) ? 0 : 5;
    final int salleCasseMixte = salle.equals(salle.toUpperCase()) ? 0 : 1;
    return artisteDiacr * 10 + artisteCasseMixte + salleDiacr + salleCasseMixte;
  }

  /**
   * Dédoublonne par clé de conflit {@code (artiste_norm, date, heure, salle_norm)}.
   *
   * <p>Deux concerts du même artiste dans la même salle le même jour avec des heures différentes
   * (matinée + soirée) sont considérés distincts. Deux concerts sans heure (null) sont considérés
   * identiques et fusionnés. En cas de doublon, conserve la version avec le meilleur score
   * (diacritiques et casse mixte préférés).
   *
   * @param liste concerts bruts à dédoublonner
   * @return liste sans doublons
   */
  public static List<ConcertBrut> deduplicerParCleConflit(final List<ConcertBrut> liste) {
    final Map<String, ConcertBrut> parCle = new LinkedHashMap<>();
    for (final ConcertBrut c : liste) {
      final String cle =
          Normalisation.normaliserTexte(c.artiste())
              + "|"
              + c.date()
              + "|"
              + (c.heure() != null ? c.heure() : "")
              + "|"
              + Normalisation.normaliserTexte(c.salle());
      final ConcertBrut existant = parCle.get(cle);
      if (existant == null
          || scoreArtisteSalle(c.artiste(), c.salle())
              > scoreArtisteSalle(existant.artiste(), existant.salle())) {
        parCle.put(cle, c);
      }
    }
    return new ArrayList<>(parCle.values());
  }

  /**
   * Dédoublonne un lot réconcilié par {@code (artisteNorm, date, heure, salleId)}.
   *
   * <p>La contrainte {@code uq_concerts_artiste_date_heure_salle} porte sur {@code salle_id}
   * (NULLS NOT DISTINCT) depuis la migration V37/V14. Deux concerts TM/Eventim nommant la même
   * salle différemment ont le même {@code salle_id} après résolution — ils conflicteraient au sein
   * d'un même INSERT ON CONFLICT DO UPDATE. Ce dedup les élimine en amont.
   *
   * <p>Doit être appelé APRÈS {@link ReconciliateurSalles#raccrocherSallesParNomNorm} afin que
   * tous les {@code salle_id} soient résolus.
   *
   * @param lot valeurs de concerts après réconciliation complète des salles
   * @return lot sans doublons
   */
  public static List<ValeurConcert> deduplicerLotReconcilie(final List<ValeurConcert> lot) {
    final Map<String, ValeurConcert> parCle = new LinkedHashMap<>();
    for (final ValeurConcert v : lot) {
      final String salleId = v.salleId() != null ? v.salleId().toString() : v.salleNorm();
      final String cle =
          v.artisteNorm()
              + "|"
              + v.date()
              + "|"
              + (v.heure() != null ? v.heure() : "")
              + "|"
              + salleId;
      final ValeurConcert existant = parCle.get(cle);
      if (existant == null
          || scoreArtisteSalle(v.artiste(), v.salle())
              > scoreArtisteSalle(existant.artiste(), existant.salle())) {
        parCle.put(cle, v);
      }
    }
    return new ArrayList<>(parCle.values());
  }

  /**
   * Résout les UUIDs internes depuis les identifiants sources préfixés.
   *
   * <p>Pour chaque concert portant un {@code salleSourceId} ou {@code artisteSourceId}, consulte
   * {@code salle_sources} et {@code artiste_sources} pour trouver l'UUID stable. En cas de
   * renommage de salle, met à jour {@code salles.nom}.
   *
   * <p>Pour les salles TM dont le {@code salleSourceId} est absent de {@code salle_sources},
   * effectue un upsert dans {@code salles} et {@code salle_sources}.
   *
   * <p>Pour les concerts sans {@code salleSourceId} du tout (TM omet parfois {@code venue.id} pour
   * un événement donné, sans identifiant source exploitable), tente un repli par correspondance
   * floue ({@link ReconciliateurSalles#chercherSalleParTokens}) plutôt que de laisser {@code
   * salle_id} indéfiniment {@code null} — sans identifiant source fiable, aucune salle n'est créée
   * si le repli échoue (pas de salle fantôme).
   *
   * <p>Pour les concerts sans {@code artiste_id} après résolution par source (y compris les
   * concerts Digitick sans {@code artisteSourceId}), effectue un lookup batch par {@code nom_norm}
   * dans la table {@code artistes}.
   *
   * @param concerts valeurs normalisées avant upsert
   * @return liste enrichie avec les UUIDs résolus
   */
  private List<ValeurConcert> resoudreIdentitesSources(final List<ValeurConcert> concerts) {
    final Map<String, UUID> cachesSalles = db.resoudreUuidsParSource(concerts, "salle");
    final Map<String, UUID> cachesArtistes = db.resoudreUuidsParSource(concerts, "artiste");
    reconciliateurSalles.absorberRenommagesSalles(concerts, cachesSalles);

    for (final ValeurConcert c : concerts) {
      if (c.salleSourceId() != null && cachesSalles.get(c.salleSourceId()) == null) {
        final UUID uuid = reconciliateurSalles.upsertSalleSource(c);
        if (uuid != null) {
          cachesSalles.put(c.salleSourceId(), uuid);
        }
      }
    }

    // Un mapping salle_sources réutilisé contourne les garde-fous géographiques appliqués à la
    // création d'un nouveau mapping — à valider avant de lui faire confiance.
    final Set<UUID> sallesMappees =
        concerts.stream()
            .filter(c -> c.salleSourceId() != null)
            .map(c -> cachesSalles.get(c.salleSourceId()))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    final Map<UUID, String> deptsConnus = reconciliateurSalles.deptsConnus(sallesMappees);

    final Map<String, UUID> cachesSallesParNom = new HashMap<>();
    for (final ValeurConcert c : concerts) {
      final UUID salleMappee =
          c.salleSourceId() != null ? cachesSalles.get(c.salleSourceId()) : null;
      if (mappingSalleIncoherent(salleMappee, c.dept(), deptsConnus)) {
        logsAdminWorker.inserer(
            "warn",
            "persistance",
            "mapping salle_sources ignoré — incohérence géographique salleId="
                + salleMappee
                + " deptConnu="
                + deptsConnus.get(salleMappee)
                + " deptEntrant="
                + c.dept()
                + " artiste="
                + c.artisteNorm(),
            Map.of("salleId", salleMappee.toString(), "artiste", c.artisteNorm()));
      }
      final boolean besoinFallback =
          c.salleSourceId() == null || mappingSalleIncoherent(salleMappee, c.dept(), deptsConnus);
      if (besoinFallback && !cachesSallesParNom.containsKey(c.salleNorm())) {
        UUID salleId = reconciliateurSalles.chercherSalleParTokens(c.salle(), c.dept());
        if (salleId == null) {
          salleId = reconciliateurSalles.chercherSalleParCleForte(c);
        }
        cachesSallesParNom.put(c.salleNorm(), salleId);
      }
    }

    final List<ValeurConcert> resolus =
        concerts.stream()
            .map(
                c -> {
                  final UUID salleMappee =
                      c.salleSourceId() != null ? cachesSalles.get(c.salleSourceId()) : null;
                  final UUID salleId =
                      c.salleSourceId() != null
                              && !mappingSalleIncoherent(salleMappee, c.dept(), deptsConnus)
                          ? salleMappee
                          : cachesSallesParNom.get(c.salleNorm());
                  final UUID artisteId =
                      c.artisteSourceId() != null ? cachesArtistes.get(c.artisteSourceId()) : null;
                  if (salleId == null && artisteId == null) {
                    return c;
                  }
                  return c.avecUuids(
                      salleId != null ? salleId : c.salleId(),
                      artisteId != null ? artisteId : c.artisteId());
                })
            .toList();

    return reconciliateurArtistes.resoudreArtisteParNomNorm(resolus);
  }

  /**
   * Indique si un mapping {@code salle_sources} réutilisé contredit le département déjà connu de
   * la salle qu'il désigne.
   *
   * @param salleMappee salle résolue via le mapping source, ou {@code null} si non résolue
   * @param deptEntrant département du concert entrant, ou {@code null} si inconnu
   * @param deptsConnus départements connus par salle (voir {@link
   *   ReconciliateurSalles#deptsConnus})
   * @return {@code true} si le mapping doit être écarté au profit d'une résolution par nom
   */
  private static boolean mappingSalleIncoherent(
      final UUID salleMappee, final String deptEntrant, final Map<UUID, String> deptsConnus) {
    if (salleMappee == null || deptEntrant == null) {
      return false;
    }
    final String deptConnu = deptsConnus.get(salleMappee);
    return deptConnu != null && !deptConnu.equals(deptEntrant);
  }

  /**
   * Résultat de la persistance d'un lot de concerts.
   *
   * @param nbInseres nombre de concerts insérés
   * @param nbMisAJour nombre de concerts mis à jour
   */
  public record ResultatPersistance(int nbInseres, int nbMisAJour) {}

  /**
   * Persiste les concerts dédoublonnés en base via upsert par lots de {@value #TAILLE_LOT}.
   *
   * <p>Chaque sous-lot est persisté dans sa propre transaction ({@link
   * #persisterLotTransactionnel}) plutôt que dans une transaction unique englobant toute la boucle
   * — une interruption (exception sur un sous-lot) ne perd ainsi que le sous-lot en cours, pas les
   * sous-lots déjà committés, conformément au motif de résilience "persistance par lot" déjà
   * appliqué ailleurs dans le worker (enrichissement, fetch).
   *
   * @param liste concerts à persister
   * @return résultat avec nombre d'insertions et de mises à jour
   */
  public ResultatPersistance persisterConcerts(final List<ConcertBrut> liste) {
    if (liste.isEmpty()) {
      return new ResultatPersistance(0, 0);
    }

    final List<ConcertBrut> apresDedup = deduplicerParCleConflit(liste);
    final List<ConcertBrut> listeFiltree =
        apresDedup.stream()
            .filter(
                c -> {
                  if (Normalisation.normaliserTexte(c.artiste()).isBlank()) {
                    LOG.warn(
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
      final List<ConcertBrut> lot =
          listeFiltree.subList(debut, Math.min(debut + TAILLE_LOT, listeFiltree.size()));
      final ResultatPersistance resultatLot = self.persisterLotTransactionnel(lot);
      totalInserts += resultatLot.nbInseres();
      totalUpdates += resultatLot.nbMisAJour();
    }

    LOG.info(
        "concerts persistés total={} inserts={} updates={}",
        totalInserts + totalUpdates,
        totalInserts,
        totalUpdates);
    return new ResultatPersistance(totalInserts, totalUpdates);
  }

  /**
   * Partie transactionnelle et isolée d'un sous-lot de {@link #persisterConcerts}.
   *
   * @param lot sous-lot de concerts bruts (au plus {@value #TAILLE_LOT})
   * @return résultat du sous-lot
   */
  @Transactional
  ResultatPersistance persisterLotTransactionnel(final List<ConcertBrut> lot) {
    final List<ValeurConcert> valeursBrutes =
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
    reconciliateurArtistes.corrigerParUrl(valeurs);
    db.alignerHeureReportTM(valeurs);
    valeurs = reconciliateurSalles.reconcilierSalles(valeurs);
    valeurs = reconciliateurArtistes.reconcilierArtistes(valeurs);
    // Deuxième passe salle : couvre le cas où artiste_norm et salle_norm varient simultanément.
    valeurs = reconciliateurSalles.reconcilierSalles(valeurs);
    valeurs = reconciliateurSalles.raccrocherSallesParNomNorm(valeurs);
    valeurs = deduplicerLotReconcilie(valeurs);

    return db.upsertLot(valeurs);
  }
}

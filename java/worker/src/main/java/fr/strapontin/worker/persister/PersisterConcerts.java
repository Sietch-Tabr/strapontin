/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * Orchestrateur de persistance des concerts dédoublonnés par lots de 500
 * (délègue à {@link ReconciliateurSalles}/{@link ReconciliateurArtistes}/
 * {@link PersisteurConcertsDb}) — pipeline détaillé : CLAUDE.md,
 * Dédoublonnage inter-sources.
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
  private final RepliMappingSalle repliMappingSalle;
  private PersisterConcerts self;

  /**
   * @param db service de persistance SQL
   * @param reconciliateurSalles service de réconciliation des salles
   * @param reconciliateurArtistes service de réconciliation des artistes
   * @param repliMappingSalle repli quand un mapping salle_sources est absent
   *     ou géographiquement incohérent
   */
  public PersisterConcerts(
      final PersisteurConcertsDb db,
      final ReconciliateurSalles reconciliateurSalles,
      final ReconciliateurArtistes reconciliateurArtistes,
      final RepliMappingSalle repliMappingSalle) {
    this.db = db;
    this.reconciliateurSalles = reconciliateurSalles;
    this.reconciliateurArtistes = reconciliateurArtistes;
    this.repliMappingSalle = repliMappingSalle;
  }

  /**
   * Injection par setter (pas par constructeur, évite l'auto-référence à la
   * construction) — {@link #persisterLotTransactionnel} passe par le proxy
   * AOP Spring, commit par sous-lot plutôt qu'une transaction unique.
   * @param self référence proxifiée de ce bean ({@code @Lazy})
   */
  @Autowired
  public void setSelf(@Lazy final PersisterConcerts self) {
    this.self = self;
  }

  /**
   * Vérifie si deux noms de salle normalisés désignent la même salle.
   *
   * @param nomA premier nom normalisé
   * @param nomB deuxième nom normalisé
   * @return {@code true} si les salles sont considérées identiques
   * @see ReconciliateurSalles#estDoublonSalle
   */
  public static boolean estDoublonSalle(final String nomA, final String nomB) {
    return ReconciliateurSalles.estDoublonSalle(nomA, nomB);
  }

  /**
   * Vérifie si deux noms d'artiste normalisés désignent le même artiste.
   *
   * @param nomA premier nom normalisé
   * @param nomB deuxième nom normalisé
   * @return {@code true} si les artistes sont considérés identiques
   * @see ReconciliateurArtistes#estDoublonArtiste
   */
  public static boolean estDoublonArtiste(final String nomA, final String nomB) {
    return ReconciliateurArtistes.estDoublonArtiste(nomA, nomB);
  }

  /**
   * Calcule le coefficient de Jaccard sur les tokens des deux chaînes.
   *
   * @param nomA première chaîne normalisée
   * @param nomB deuxième chaîne normalisée
   * @return coefficient de Jaccard entre 0.0 et 1.0
   * @see ReconciliateurSalles#jaccardTokens
   */
  public static double jaccardTokens(final String nomA, final String nomB) {
    return ReconciliateurSalles.jaccardTokens(nomA, nomB);
  }

  // equalsIgnoreCase ne convient pas ici : le test porte sur chaque nom
  // lui-même (déjà entièrement majuscule ou non), pas sur une comparaison
  // insensible à la casse entre deux chaînes distinctes.
  private static int scoreArtisteSalle(final String artiste, final String salle) {
    final int artisteDiacr =
        Normalizer.normalize(artiste, Normalizer.Form.NFD).length() - artiste.length();
    final int salleDiacr =
        Normalizer.normalize(salle, Normalizer.Form.NFD).length() - salle.length();
    final int artisteCasseMixte = artiste.equals(artiste.toUpperCase(Locale.ROOT)) ? 0 : 5;
    final int salleCasseMixte = salle.equals(salle.toUpperCase(Locale.ROOT)) ? 0 : 1;
    return artisteDiacr * 10 + artisteCasseMixte + salleDiacr + salleCasseMixte;
  }

  /**
   * Dédoublonne par clé de conflit {@code (artiste_norm, date, heure,
   * salle_norm)} — deux concerts avec heures différentes le même jour sont
   * distincts, sans heure sont fusionnés ; garde la version au meilleur
   * score (diacritiques/casse mixte préférés).
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
   * Dédoublonne un lot réconcilié par {@code (artisteNorm, date, heure,
   * salleId)} — la contrainte {@code uq_evenements} porte sur {@code
   * salle_id} ; deux noms résolus au même identifiant conflicteraient
   * sinon. Après {@link ReconciliateurSalles#raccrocherSallesParNomNorm}.
   * @param lot valeurs après réconciliation complète des salles
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
   * Résout les UUIDs internes depuis les identifiants sources préfixés
   * (upsert des salles TM absentes de {@code salle_sources}, repli flou
   * ou lookup {@code nom_norm} si aucun identifiant source n'est résolu) —
   * détail : CLAUDE.md, Dédoublonnage inter-sources.
   * @param concerts valeurs normalisées avant upsert
   * @return liste enrichie avec les UUIDs résolus
   */
  private List<ValeurConcert> resoudreIdentitesSources(final List<ValeurConcert> concerts) {
    final Map<String, UUID> cachesSalles = db.resoudreUuidsParSource(concerts, "salle");
    db.reprendreIdentifiantsEventimHistoriques(concerts, cachesSalles);
    final Map<String, UUID> cachesArtistes = db.resoudreUuidsParSource(concerts, "artiste");
    // Calculé avant absorberRenommagesSalles : un mapping incohérent ne doit
    // pas renommer la mauvaise salle avant validation géographique.
    final Map<UUID, String> deptsConnus = resoudreDeptsConnusSallesMappees(concerts, cachesSalles);
    reconciliateurSalles.absorberRenommagesSalles(concerts, cachesSalles, deptsConnus);
    upsertSallesSourcesManquantes(concerts, cachesSalles);

    final Map<String, UUID> cachesSallesParNom =
        repliMappingSalle.resoudreSallesParNomFallback(concerts, cachesSalles, deptsConnus);

    final List<ValeurConcert> resolus =
        appliquerResolutionUuids(
            concerts, cachesSalles, cachesArtistes, cachesSallesParNom, deptsConnus);
    db.enregistrerPositions(concerts, cachesSalles);

    return reconciliateurArtistes.resoudreArtisteParNomNorm(resolus);
  }

  private void upsertSallesSourcesManquantes(
      final List<ValeurConcert> concerts, final Map<String, UUID> cachesSalles) {
    for (final ValeurConcert c : concerts) {
      if (c.salleSourceId() != null && cachesSalles.get(c.salleSourceId()) == null) {
        final UUID uuid = reconciliateurSalles.upsertSalleSource(c);
        if (uuid != null) {
          cachesSalles.put(c.salleSourceId(), uuid);
        }
      }
    }
  }

  private Map<UUID, String> resoudreDeptsConnusSallesMappees(
      final List<ValeurConcert> concerts, final Map<String, UUID> cachesSalles) {
    final Set<UUID> sallesMappees =
        concerts.stream()
            .filter(c -> c.salleSourceId() != null)
            .map(c -> cachesSalles.get(c.salleSourceId()))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    return reconciliateurSalles.deptsConnus(sallesMappees);
  }

  private List<ValeurConcert> appliquerResolutionUuids(
      final List<ValeurConcert> concerts,
      final Map<String, UUID> cachesSalles,
      final Map<String, UUID> cachesArtistes,
      final Map<String, UUID> cachesSallesParNom,
      final Map<UUID, String> deptsConnus) {
    return concerts.stream()
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
  }

  /**
   * Indique si un mapping {@code salle_sources} réutilisé contredit le
   * département déjà connu de la salle qu'il désigne.
   * @param salleMappee salle résolue via le mapping, ou null si non résolue
   * @param deptEntrant département du concert entrant, ou null si inconnu
   * @param deptsConnus départements connus par salle (cf. deptsConnus)
   * @return {@code true} si le mapping doit être écarté
   */
  /* package-private : réutilisé par ReconciliationNomsSalles (même garde-fou,
   * même package) pour ne pas renommer une salle sur un mapping incohérent. */
  static boolean mappingSalleIncoherent(
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
   * Persiste les concerts dédoublonnés via upsert par lots de {@value
   * #TAILLE_LOT}, chaque sous-lot dans sa propre transaction ({@link
   * #persisterLotTransactionnel}) — une interruption ne perd que le
   * sous-lot en cours, pas ceux déjà committés.
   * @param liste concerts à persister
   * @return résultat avec nombre d'insertions et de mises à jour
   */
  public ResultatPersistance persisterConcerts(final List<ConcertBrut> liste) {
    if (liste.isEmpty()) {
      return new ResultatPersistance(0, 0);
    }

    final List<ConcertBrut> apresDedup = deduplicerParCleConflit(liste);
    final LocalDate aujourdHui = LocalDate.now();
    final List<ConcertBrut> listeFiltree =
        apresDedup.stream()
            .filter(
                c -> {
                  // Une source peut dater un produit sans séance réelle (billet à validité
                  // longue, visite) : un événement déjà passé n'est jamais ingéré.
                  if (estDejaPasse(c, aujourdHui)) {
                    LOG.info(
                        "concert ignoré — date déjà passée source={} artiste={} date={} url={}",
                        c.source(),
                        c.artiste(),
                        c.date(),
                        c.url());
                    return false;
                  }
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

  private static boolean estDejaPasse(final ConcertBrut concert, final LocalDate aujourdHui) {
    try {
      return LocalDate.parse(concert.date()).isBefore(aujourdHui);
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  /**
   * Partie transactionnelle et isolée d'un sous-lot de
   * {@link #persisterConcerts} .
   *
   * @param lot sous-lot de concerts bruts (au plus {@value #TAILLE_LOT} )
   * @return résultat du sous-lot
   */
  @Transactional
  /* package-private : requis pour l'auto-invocation via le proxy self
   * (transaction dédiée par lot). */
  ResultatPersistance persisterLotTransactionnel(final List<ConcertBrut> lot) {
    final List<ValeurConcert> valeursBrutes = lot.stream().map(ValeurConcert::depuis).toList();

    List<ValeurConcert> valeurs = resoudreIdentitesSources(valeursBrutes);
    valeurs = db.resoudreParUrl(valeurs);
    reconciliateurArtistes.corrigerParUrl(valeurs);
    db.alignerHeureReportTM(valeurs);
    valeurs = reconciliateurSalles.reconcilierSalles(valeurs);
    valeurs = reconciliateurArtistes.reconcilierArtistes(valeurs);
    // Deuxième passe salle : couvre le cas où artiste_norm et salle_norm
    // varient simultanément.
    valeurs = reconciliateurSalles.reconcilierSalles(valeurs);
    valeurs = reconciliateurSalles.raccrocherSallesParNomNorm(valeurs);
    valeurs = deduplicerLotReconcilie(valeurs);

    return db.upsertLot(valeurs);
  }
}

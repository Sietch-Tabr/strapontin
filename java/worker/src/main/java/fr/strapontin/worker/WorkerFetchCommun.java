/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.CorrespondanceArtiste;
import fr.strapontin.core.Dedoublonner;
import fr.strapontin.core.Normalisation;
import fr.strapontin.core.QuotaTM;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.persister.EligibiliteResolutionDeezer;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.quota.GestionQuotaTM;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Briques partagées par les différents pipelines de fetch de {@link WorkerFetchService} (batch
 * artistes, région, salles) — dédoublonnage/résolution du nom canonique, suivi des absences,
 * remontée des échecs de lot, sauvegarde du quota TM.
 *
 * <p>Extrait de {@link WorkerFetchService} pour rester sous la limite de 500 lignes par fichier.
 *
 * @since 2.9.1
 * @author Kwaite
 */
class WorkerFetchCommun {

  private static final int DELAI_ENTRE_ARTISTES_MS = 250;

  // Nombre d'absences consécutives (cycles fetch:artistes:batch/fetch:salles:all successifs sans
  // qu'un concert précédemment vu ne réapparaisse) à partir duquel il est marqué comme non
  // confirmé récemment — évite un faux positif sur un simple aléa réseau isolé, tout en restant
  // assez bas pour signaler une disparition réelle dans un délai raisonnable (cron ~11 cycles/jour
  // — 5 absences correspond à environ une demi-journée de silence continu). Package-private :
  // référencé par WorkerPurgeService pour dériver le seuil de purge définitive (voir
  // WorkerPurgeService.SEUIL_PURGE_ABSENCES).
  static final int SEUIL_ABSENCES_CONSECUTIVES = 5;

  private static final String SQL_ABSENCES_BASE =
      "UPDATE evenements SET"
          + " absences_consecutives = CASE WHEN updated_at >= CAST(:debut AS timestamptz)"
          + "   THEN 0 ELSE absences_consecutives + 1 END,"
          + " absence_detectee_le = CASE"
          + "   WHEN updated_at >= CAST(:debut AS timestamptz) THEN NULL"
          + "   WHEN absences_consecutives + 1 >= :seuil THEN COALESCE(absence_detectee_le, now())"
          + "   ELSE absence_detectee_le END,"
          // Un concert flagué pour suppression puis effectivement reconfirmé (ex. après fusion
          // d'un doublon de salle) n'a plus de raison de rester marqué suppression_suggeree_le.
          + " suppression_suggeree_le = CASE"
          + "   WHEN updated_at >= CAST(:debut AS timestamptz) THEN NULL"
          + "   ELSE suppression_suggeree_le END"
          + " WHERE %s::text IN (:ids) AND date >= CURRENT_DATE";

  private static final String SQL_ABSENCES_ARTISTE = SQL_ABSENCES_BASE.formatted("artiste_id");
  private static final String SQL_ABSENCES_SALLE = SQL_ABSENCES_BASE.formatted("salle_id");

  private final NamedParameterJdbcTemplate jdbc;
  private final ResolveurNomCanonique resolveur;
  private final LogsAdminWorker logsAdminWorker;
  private final RetryLotsService retryLotsService;
  private final TicketmasterSource tm;
  private final GestionQuotaTM gestionQuotaTM;

  WorkerFetchCommun(
      final NamedParameterJdbcTemplate jdbc,
      final ResolveurNomCanonique resolveur,
      final LogsAdminWorker logsAdminWorker,
      final RetryLotsService retryLotsService,
      final TicketmasterSource tm,
      final GestionQuotaTM gestionQuotaTM) {
    this.jdbc = jdbc;
    this.resolveur = resolveur;
    this.logsAdminWorker = logsAdminWorker;
    this.retryLotsService = retryLotsService;
    this.tm = tm;
    this.gestionQuotaTM = gestionQuotaTM;
  }

  static ResultatPersistance accumuler(final ResultatPersistance a, final ResultatPersistance b) {
    return new ResultatPersistance(a.nbInseres() + b.nbInseres(), a.nbMisAJour() + b.nbMisAJour());
  }

  /**
   * Remonte dans {@code logs_admin} les échecs de lots d'un job multi-lots, indépendamment du
   * résultat des autres lots — sans cela, un lot en échec reste invisible tant que le job dans son
   * ensemble se termine sans exception et que son bilan agrégé reste positif grâce aux autres lots.
   *
   * <p>Niveau {@code error} si tous les lots du job ont échoué (panne totale), {@code warn} sinon
   * (échec partiel, même convention que {@code WorkerStatsService#remonterBilan}). Délègue ensuite
   * à {@link RetryLotsService} la décision de retenter ou d'abandonner les identifiants en échec, y
   * compris quand ce cycle est propre (remise à zéro du compteur de tentatives).
   *
   * @param typeJob type de job (ex. {@code "fetch:salles:all"})
   * @param echecs messages d'échec, un par lot en échec
   * @param totalLots nombre total de lots traités par le job
   * @param identifiantsEnEchec identifiants des entités des lots en échec, pour un retry ciblé
   */
  void remonterEchecsLots(
      final String typeJob,
      final List<String> echecs,
      final int totalLots,
      final List<String> identifiantsEnEchec) {
    if (!echecs.isEmpty()) {
      final String niveau = echecs.size() == totalLots ? "error" : "warn";
      final String message =
          typeJob
              + " — "
              + echecs.size()
              + "/"
              + totalLots
              + " lot(s) échoué(s) : "
              + String.join(" | ", echecs);
      logsAdminWorker.inserer(niveau, "worker", message, Map.of("echecs", echecs));
    }
    retryLotsService.gererResultat(typeJob, identifiantsEnEchec);
  }

  /**
   * Met à jour le compteur d'absences consécutives des concerts futurs liés aux entités
   * effectivement retraitées dans ce cycle : remis à zéro pour tout concert réellement revu ({@code
   * updated_at} avancé par l'upsert), incrémenté sinon.
   *
   * @param colonne "artiste_id" ou "salle_id"
   * @param ids identifiants (texte) des entités traitées dans ce cycle
   * @param debutCycle horodatage capturé avant la persistance du lot
   */
  void mettreAJourAbsences(final String colonne, final List<String> ids, final String debutCycle) {
    if (ids.isEmpty()) {
      return;
    }
    final String sql =
        switch (colonne) {
          case "artiste_id" -> SQL_ABSENCES_ARTISTE;
          case "salle_id" -> SQL_ABSENCES_SALLE;
          default -> throw new IllegalArgumentException("colonne inconnue : " + colonne);
        };
    jdbc.update(sql, Map.of("debut", debutCycle, "seuil", SEUIL_ABSENCES_CONSECUTIVES, "ids", ids));
  }

  /**
   * Détermine si un concert est éligible à la résolution du nom canonique via Deezer.
   *
   * <p>Exclut les classifications "theatre", "divers" et "sports" (jamais un vrai musicien côté
   * Deezer) ainsi que, spécifiquement pour Eventim, une classification vide/absente — Digitick omet
   * aussi sa classification mais uniquement pour de vrais concerts (filtré en amont sur le genre
   * "Concert"), alors qu'Eventim l'omet fréquemment y compris pour des spectacles non musicaux.
   *
   * @param c concert à évaluer
   * @return {@code true} si la résolution Deezer peut s'appliquer à ce concert
   */
  private static boolean estEligibleResolutionDeezer(final ConcertBrut c) {
    return EligibiliteResolutionDeezer.estEligible(c.classification(), c.source());
  }

  /**
   * Rejette un candidat Deezer divergent du {@code nom_affiche} Wikidata déjà connu pour cet
   * artiste : un mauvais rapprochement Deezer est une cause de corruption déjà rencontrée (ex. «
   * Nino Arial » → « Niño Árbol »), détectable avant la persistance quand l'identité de l'artiste
   * est déjà établie ailleurs par une source plus fiable (Wikidata).
   *
   * @param nomBrut nom brut entrant, avant résolution Deezer
   * @param nomDeezer nom canonique proposé par {@link ResolveurNomCanonique}
   * @param log logger de l'appelant, pour tracer un rejet
   * @return {@code nomDeezer}, ou {@code nomBrut} si un {@code nom_affiche} déjà connu diverge
   */
  private String nomCanoniqueValideOuBrut(
      final String nomBrut, final String nomDeezer, final Logger log) {
    if (nomDeezer.equals(nomBrut)) {
      return nomDeezer;
    }
    final List<String> nomsAffiches =
        jdbc.query(
            "SELECT nom_affiche FROM artistes WHERE nom_norm = :nomNorm AND nom_affiche IS NOT"
                + " NULL",
            Map.of("nomNorm", Normalisation.normaliserTexte(nomBrut)),
            (rs, _) -> rs.getString("nom_affiche"));
    if (nomsAffiches.isEmpty()) {
      return nomDeezer;
    }
    final String nomAffiche = nomsAffiches.get(0);
    if (CorrespondanceArtiste.estDoublonArtiste(
        Normalisation.normaliserTexte(nomAffiche), Normalisation.normaliserTexte(nomDeezer))) {
      return nomDeezer;
    }
    log.info(
        "résolution Deezer rejetée — divergence avec nom_affiche Wikidata connu brut={} deezer={}"
            + " nomAffiche={}",
        nomBrut,
        nomDeezer,
        nomAffiche);
    return nomBrut;
  }

  List<ConcertBrut> resoudreEtDeduplicer(final List<ConcertBrut> concerts, final Logger log) {
    final List<ConcertBrut> dedoublonnes = Dedoublonner.dedoublonner(concerts);

    final Map<String, String> tableauResolution = new LinkedHashMap<>();
    final List<String> noms =
        dedoublonnes.stream()
            .filter(WorkerFetchCommun::estEligibleResolutionDeezer)
            .map(ConcertBrut::artiste)
            .distinct()
            .toList();

    for (final String nom : noms) {
      final ResolveurNomCanonique.ResultatResolution resultat =
          resolveur.resoudreNomCanonique("artiste", nom);
      tableauResolution.put(nom, nomCanoniqueValideOuBrut(nom, resultat.nom(), log));
    }

    return dedoublonnes.stream()
        .map(
            c -> {
              if (!estEligibleResolutionDeezer(c)) {
                return c;
              }
              final String nomResolu = tableauResolution.getOrDefault(c.artiste(), c.artiste());
              if (nomResolu.equals(c.artiste())) {
                return c;
              }
              return new ConcertBrut(
                  nomResolu,
                  c.date(),
                  c.heure(),
                  c.salle(),
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
                  c.artisteSourceId());
            })
        .toList();
  }

  void sauvegarderQuota() {
    final QuotaTM quota = tm.obtenirQuota();
    if (quota != null) {
      gestionQuotaTM.ecrireQuota(quota);
    }
  }

  // Thread.sleep est la seule primitive de cadencement synchrone bloquant disponible — pas un
  // contexte de requête J2EE (appel HTTP sortant en job Quartz), la justification de la règle ne
  // s'applique pas ici.
  @SuppressWarnings("PMD.DoNotUseThreads")
  void attendreRateLimit() {
    try {
      Thread.sleep(DELAI_ENTRE_ARTISTES_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

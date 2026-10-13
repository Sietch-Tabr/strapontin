/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.core.Classement;
import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.CorrespondanceArtiste;
import fr.strapontin.core.Dedoublonner;
import fr.strapontin.core.Normalisation;
import fr.strapontin.core.QuotaTM;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.persister.EligibiliteResolutionDeezer;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.quota.GestionQuotaTM;
import java.io.IOException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Briques partagées par les pipelines de fetch de {@link
 * WorkerFetchService} — dédoublonnage/résolution du nom canonique, suivi
 * des absences, remontée des échecs de lot, sauvegarde du quota TM.
 * @since 2.9.1
 * @author Kwaite
 */
@Component
public class WorkerFetchCommun {

  private static final int DELAI_ENTRE_ARTISTES_MS = 250;

  // Nombre d'absences consécutives (cycles
  // fetch:artistes:batch/fetch:salles:all successifs sans qu'un concert
  // précédemment vu ne réapparaisse) à partir duquel il est marqué comme non
  // confirmé récemment — évite un faux positif sur un simple aléa réseau isolé,
  // tout en restant assez bas pour signaler une disparition réelle dans un
  // délai raisonnable (cron ~11 cycles/jour — 5 absences correspond à environ
  // une demi-journée de silence continu). Package-private : référencé par
  // WorkerPurgeService pour dériver le seuil de purge définitive (voir
  // WorkerPurgeService.SEUIL_PURGE_ABSENCES).
  /** Absences consécutives à partir desquelles un concert est marqué non confirmé. */
  public static final int SEUIL_ABSENCES_CONSECUTIVES = 5;

  private static final String SQL_ABSENCES_BASE =
      "UPDATE evenements SET"
          + " absences_consecutives = CASE WHEN updated_at >= CAST(:debut AS timestamptz)"
          + "   THEN 0 ELSE absences_consecutives + 1 END,"
          + " absence_detectee_le = CASE"
          + "   WHEN updated_at >= CAST(:debut AS timestamptz) THEN NULL"
          + "   WHEN absences_consecutives + 1 >= :seuil THEN COALESCE(absence_detectee_le, now())"
          + "   ELSE absence_detectee_le END,"
          // Un concert flagué pour suppression puis effectivement reconfirmé
          // (ex. après fusion d'un doublon de salle) n'a plus de raison de
          // rester marqué suppression_suggeree_le.
          + " suppression_suggeree_le = CASE"
          + "   WHEN updated_at >= CAST(:debut AS timestamptz) THEN NULL"
          + "   ELSE suppression_suggeree_le END"
          + " WHERE %s::text IN (:ids) AND date >= CURRENT_DATE";

  // Valeur qu'aucune clé de classement ne peut prendre (elle contient « | »).
  private static final String CLASSEMENT_AUCUN = "-";

  private static final String SQL_ABSENCES_ARTISTE = SQL_ABSENCES_BASE.formatted("artiste_id");

  // Par salle, une absence n'est comptée que si l'événement est observé par une
  // source qui garantit avoir couvert sa date (fenêtre de requête, plafond de
  // pagination) et son classement : au-delà, il est hors de portée, pas disparu.
  private static final String SQL_ABSENCES_SALLE =
      SQL_ABSENCES_BASE.formatted("salle_id")
          + " AND EXISTS (SELECT 1 FROM observations o WHERE o.evenement_id = evenements.id AND (  "
          + " (o.source = 'ticketmaster' AND evenements.date <= CAST(:horizonTicketmaster AS date) "
          + "   AND (CAST(:tousClassementsTicketmaster AS boolean)      OR"
          + " coalesce(evenements.classification, '') || '|' || coalesce(evenements.genre, '')     "
          + "    || '|' || coalesce(evenements.sous_genre, '') IN (:classementsTicketmaster)))   OR"
          + " (o.source = 'eventim' AND evenements.date <= CAST(:horizonEventim AS date))))";

  private final NamedParameterJdbcTemplate jdbc;
  private final ResolveurNomCanonique resolveur;
  private final LogsAdminWorker logsAdminWorker;
  private final RetryLotsService retryLotsService;
  private final TicketmasterSource tm;
  private final GestionQuotaTM gestionQuotaTM;

  /**
   * @param jdbc accès JDBC
   * @param resolveur résolution du nom canonique artiste/salle
   * @param logsAdminWorker journalisation des échecs de lots
   * @param retryLotsService retry ciblé des lots en échec
   * @param tm fetch par lot d'attractionIds Ticketmaster
   * @param gestionQuotaTM lecture du quota Ticketmaster restant
   */
  public WorkerFetchCommun(
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

  /**
   * @return accès JDBC partagé
   */
  /* package-private : accesseur réservé aux exécuteurs de fetch (même
   * package). */
  NamedParameterJdbcTemplate obtenirJdbc() {
    return jdbc;
  }

  /**
   * @return source Ticketmaster partagée
   */
  /* package-private : accesseur réservé aux exécuteurs de fetch (même
   * package). */
  TicketmasterSource obtenirTm() {
    return tm;
  }

  /**
   * @return gestion du quota Ticketmaster partagée
   */
  /* package-private : accesseur réservé aux exécuteurs de fetch (même
   * package). */
  GestionQuotaTM obtenirGestionQuotaTM() {
    return gestionQuotaTM;
  }

  /**
   * Distingue une vraie panne réseau (DNS, I/O, timeout — {@link IOException}
   * et sous-classes, y compris en cause imbriquée) d'un échec applicatif
   * (erreur métier, JSON invalide) : seule la première justifie un retry
   * ciblé et interdit de compter des absences sur le périmètre concerné.
   * @param erreur exception levée par un fetch de source
   * @return {@code true} si l'échec est une panne réseau transitoire
   */
  /* package-private : appelé par les exécuteurs de fetch (même package). */
  static boolean estEchecReseauTransitoire(final Throwable erreur) {
    Throwable cause = erreur;
    while (cause != null) {
      if (cause instanceof IOException) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  /* package-private : appelé par les exécuteurs de fetch (même package). */
  static ResultatPersistance accumuler(
      final ResultatPersistance resultatA, final ResultatPersistance resultatB) {
    return new ResultatPersistance(
        resultatA.nbInseres() + resultatB.nbInseres(),
        resultatA.nbMisAJour() + resultatB.nbMisAJour());
  }

  /**
   * Remonte les échecs de lots dans {@code logs_admin} (détail : CLAUDE.md,
   * Jobs background) — délègue à {@link RetryLotsService}.
   * @param typeJob type de job (ex. {@code "fetch:salles:all"})
   * @param echecs messages d'échec, un par lot en échec
   * @param totalLots nombre total de lots traités par le job
   * @param identifiantsEnEchec identifiants en échec, pour un retry ciblé
   */
  /* package-private : appelé par les exécuteurs de fetch (même package). */
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
   * Met à jour le compteur d'absences consécutives des artistes retraités ce
   * cycle (détail : CLAUDE.md, Jobs background) — remis à zéro si revu,
   * incrémenté sinon.
   * @param ids identifiants (texte) des artistes traités dans ce cycle
   * @param debutCycle horodatage capturé avant la persistance du lot
   */
  /* package-private : appelé par les exécuteurs de fetch (même package). */
  void mettreAJourAbsencesArtistes(final List<String> ids, final String debutCycle) {
    if (ids.isEmpty()) {
      return;
    }
    jdbc.update(
        SQL_ABSENCES_ARTISTE,
        Map.of("debut", debutCycle, "seuil", SEUIL_ABSENCES_CONSECUTIVES, "ids", ids));
  }

  /**
   * Met à jour le compteur d'absences des événements des salles retraitées
   * ce cycle, limité aux dates effectivement couvertes par chaque source.
   * @param ids identifiants (texte) des salles traitées dans ce cycle
   * @param debutCycle horodatage capturé avant la persistance du lot
   * @param horizons dernière date couverte par nom de source ({@code
   *     "ticketmaster"}, {@code "eventim"}) ; source absente = aucune absence
   */
  /* package-private : appelé par les exécuteurs de fetch (même package). */
  void mettreAJourAbsencesSalles(
      final List<String> ids, final String debutCycle, final Map<String, LocalDate> horizons) {
    mettreAJourAbsencesSalles(ids, debutCycle, horizons, null);
  }

  /**
   * Recherche filtrée par classification : seuls les événements Ticketmaster
   * d'un classement renvoyé ({@link Classement#cle()}) peuvent être absents.
   * @param ids identifiants (texte) des salles traitées dans ce cycle
   * @param debutCycle horodatage capturé avant la persistance du lot
   * @param horizons dernière date couverte par nom de source
   * @param classementsTicketmaster classements renvoyés, null sans filtre
   */
  /* package-private : appelé par les exécuteurs de fetch (même package). */
  void mettreAJourAbsencesSalles(
      final List<String> ids,
      final String debutCycle,
      final Map<String, LocalDate> horizons,
      final Set<String> classementsTicketmaster) {
    if (ids.isEmpty()) {
      return;
    }
    final boolean tous = classementsTicketmaster == null;
    final MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("debut", debutCycle)
            .addValue("seuil", SEUIL_ABSENCES_CONSECUTIVES)
            .addValue("ids", ids)
            .addValue("horizonTicketmaster", horizons.get("ticketmaster"), Types.DATE)
            .addValue("horizonEventim", horizons.get("eventim"), Types.DATE)
            .addValue("tousClassementsTicketmaster", tous)
            // Liste jamais vide : IN () n'est pas du SQL valide.
            .addValue(
                "classementsTicketmaster",
                tous || classementsTicketmaster.isEmpty()
                    ? List.of(CLASSEMENT_AUCUN)
                    : List.copyOf(classementsTicketmaster));
    jdbc.update(SQL_ABSENCES_SALLE, params);
  }

  /**
   * @param sources sources de récupération
   * @return les sources hors Ticketmaster, pour un cycle à quota bas
   */
  /* package-private : appelé par les exécuteurs de fetch (même package). */
  static List<SourceConcerts> sansTicketmaster(final List<SourceConcerts> sources) {
    return sources.stream().filter(s -> !"ticketmaster".equals(s.nom())).toList();
  }

  /**
   * Exclut de la résolution Deezer les classifications "theatre"/"divers"/
   * "sports" (jamais un vrai musicien) et, pour Eventim, une
   * classification vide.
   * @param c concert à évaluer
   * @return {@code true} si la résolution Deezer peut s'appliquer
   */
  private static boolean estEligibleResolutionDeezer(final ConcertBrut concert) {
    return EligibiliteResolutionDeezer.estEligible(concert.classification(), concert.source());
  }

  /**
   * Rejette un candidat Deezer divergent du {@code nom_affiche} Wikidata
   * déjà connu (corruption Deezer connue, ex. "Nino Arial" → "Niño Árbol").
   * @param nomBrut nom brut entrant, avant résolution Deezer
   * @param nomDeezer nom canonique proposé par {@link ResolveurNomCanonique}
   * @param log logger de l'appelant, pour tracer un rejet
   * @return nomDeezer, ou nomBrut si un nom_affiche connu diverge
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

  /* package-private : appelé par les exécuteurs de fetch (même package). */
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
              return ConcertBrut.nouveau()
                  .artiste(nomResolu)
                  .date(c.date())
                  .heure(c.heure())
                  .salle(c.salle())
                  .ville(c.ville())
                  .dept(c.dept())
                  .url(c.url())
                  .source(c.source())
                  .classification(c.classification())
                  .genre(c.genre())
                  .statut(c.statut())
                  .presaleDebut(c.presaleDebut())
                  .presaleNom(c.presaleNom())
                  .salleSourceId(c.salleSourceId())
                  .artisteSourceId(c.artisteSourceId())
                  .construire();
            })
        .toList();
  }

  /* package-private : appelé par les exécuteurs de fetch (même package). */
  void sauvegarderQuota() {
    final QuotaTM quota = tm.obtenirQuota();
    if (quota == null) {
      return;
    }
    // Quota épuisé signalé sans réponse complète depuis le démarrage : total
    // inconnu, celui déjà enregistré est conservé.
    final QuotaTM complet =
        quota.total() > 0
            ? quota
            : gestionQuotaTM
                .lireQuota()
                .map(
                    precedent ->
                        new QuotaTM(
                            quota.dispo(), precedent.total(), quota.reset(), quota.misAJour()))
                .orElse(quota);
    gestionQuotaTM.ecrireQuota(complet);
  }

  /* package-private : appelé par les exécuteurs de fetch (même package). */
  void attendreRateLimit() {
    try {
      Thread.sleep(DELAI_ENTRE_ARTISTES_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

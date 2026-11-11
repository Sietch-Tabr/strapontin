/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Suivi passif de santé des identifiants externes (phase 1) — compteur
 * générique salle/artiste, suspect au 3e échec consécutif (détail :
 * CLAUDE.md, Santé/référentiel admin).
 * @since 2.6.0
 * @author Kwaite
 */
@Service
public class SuiviSanteSourcesService {

  private static final Logger LOG = LoggerFactory.getLogger(SuiviSanteSourcesService.class);

  private static final int SEUIL_SUSPECT = 3;
  /* package-private : délai partagé avec HealthCheckArtistesTM (même package). */
  static final int DELAI_REVERIFICATION_VIVACITE_JOURS = 7;
  private static final String CLE_SOURCE = "source";

  // Chute brutale : un lieu qui change de billetterie ne tombe presque jamais à
  // zéro d'un coup (un concert annulé ou un pack reste listé), il faut donc
  // aussi comparer au volume précédent, pas seulement détecter une salle vide.
  private static final int VOLUME_MIN_CHUTE = 5;
  private static final int POURCENTAGE_RESTANT_CHUTE = 30;

  /**
   * Préfixes abrégés utilisés par le déclenchement manuel (voir
   * {@code TriggerController} , {@code "tm:venueId"}) vers la valeur canonique
   * stockée dans {@code salle_sources.source} / {@code artiste_sources.source}
   * ({@code source_concert} enum). Normalisation défensive uniquement pour ce
   * suivi — ne corrige pas {@code TicketmasterSource.accepteIdentifiantSalle}
   * (hors périmètre).
   */
  private static final Map<String, String> PREFIXES_ABREGES = Map.of("tm", "ticketmaster");

  private final NamedParameterJdbcTemplate jdbc;
  private final TicketmasterSource tm;
  private final SignalementMatchingService signalementMatchingService;

  /**
   * @param jdbc accès JDBC
   * @param tm revérification du nom TM avant effacement d'une intervention
   *     requise
   * @param signalementMatchingService signalement d'une chute brutale du
   *     nombre de concerts d'une salle
   */
  public SuiviSanteSourcesService(
      final NamedParameterJdbcTemplate jdbc,
      final TicketmasterSource tm,
      final SignalementMatchingService signalementMatchingService) {
    this.jdbc = jdbc;
    this.tm = tm;
    this.signalementMatchingService = signalementMatchingService;
  }

  /**
   * Enregistre le résultat d'un fetch salle (identifiant préfixé, ex.
   * {@code "tm:rZFSnyZdFvA"}, {@code "eventim:internal:accor arena"}).
   * @param identifiantPrefixe identifiant préfixé {@code source:id_externe}
   * @param nbResultats nombre de concerts retournés par ce fetch
   */
  public void enregistrerResultatSalle(final String identifiantPrefixe, final int nbResultats) {
    final int sep = identifiantPrefixe.indexOf(':');
    if (sep < 0) {
      return;
    }
    final String source = normaliserSource(identifiantPrefixe.substring(0, sep));
    final String identifiant = identifiantPrefixe.substring(sep + 1);
    mettreAJourCompteur(TypeEntiteSource.SALLE, source, identifiant, nbResultats > 0);
    suivreVolumeSalle(source, identifiant, nbResultats);
  }

  /**
   * Mémorise le volume renvoyé et signale une chute brutale par rapport au
   * fetch précédent (lieu passé à une autre billetterie, source devenue
   * partielle).
   */
  private void suivreVolumeSalle(
      final String source, final String identifiant, final int nbResultats) {
    try {
      final List<Map<String, Object>> lignes =
          jdbc.queryForList(
              """
              UPDATE salle_sources ss SET dernier_nb_resultats = :nb, dernier_fetch_le = now()
              FROM salle_sources avant
              WHERE ss.source = :source
                AND ss.identifiant_externe = :identifiant
                AND avant.source = ss.source AND avant.identifiant_externe = ss.identifiant_externe
              RETURNING ss.salle_id AS salle_id, avant.dernier_nb_resultats AS precedent
              """,
              Map.of("nb", nbResultats, CLE_SOURCE, source, "identifiant", identifiant));
      if (lignes.isEmpty() || !(lignes.get(0).get("precedent") instanceof Number precedent)) {
        return;
      }
      if (estChuteBrutale(precedent.intValue(), nbResultats)) {
        LOG.warn(
            "chute brutale du nombre de concerts source={} identifiant={} {} → {}",
            source,
            identifiant,
            precedent,
            nbResultats);
        signalementMatchingService.signaler(
            "chute_resultats_salle",
            "salle",
            (UUID) lignes.get(0).get("salle_id"),
            "moyenne",
            Map.of(
                CLE_SOURCE,
                source,
                "identifiantExterne",
                identifiant,
                "precedent",
                precedent.intValue(),
                "actuel",
                nbResultats));
      } else {
        signalementMatchingService.cloreEntite(
            "chute_resultats_salle", (UUID) lignes.get(0).get("salle_id"));
      }
    } catch (Exception e) {
      LOG.warn("Suivi du volume impossible source={} identifiant={}", source, identifiant, e);
    }
  }

  /**
   * @param precedent volume du fetch précédent
   * @param actuel volume du fetch courant
   * @return {@code true} si le volume est tombé sous {@value
   *     #POURCENTAGE_RESTANT_CHUTE}% d'un volume initial significatif
   */
  /* package-private : visibilité élargie pour SuiviSanteSourcesServiceTest. */
  static boolean estChuteBrutale(final int precedent, final int actuel) {
    return precedent >= VOLUME_MIN_CHUTE && actuel * 100 <= precedent * POURCENTAGE_RESTANT_CHUTE;
  }

  /**
   * Enregistre le résultat d'un fetch TM par lot d'artistes (détail :
   * CLAUDE.md, Santé/référentiel admin).
   * @param attractionIds identifiants TM (bruts, sans préfixe) du lot
   * @param concertsLot concerts retournés par ce lot
   */
  public void enregistrerResultatArtistesLot(
      final List<String> attractionIds, final List<ConcertBrut> concertsLot) {
    for (final String attractionId : attractionIds) {
      final String artisteSourceId = "ticketmaster:" + attractionId;
      final boolean succes =
          concertsLot.stream().anyMatch(c -> artisteSourceId.equals(c.artisteSourceId()));
      mettreAJourCompteur(TypeEntiteSource.ARTISTE, "ticketmaster", attractionId, succes);
    }
  }

  private static String normaliserSource(final String prefixe) {
    return PREFIXES_ABREGES.getOrDefault(prefixe, prefixe);
  }

  private void mettreAJourCompteur(
      final TypeEntiteSource type,
      final String source,
      final String identifiant,
      final boolean succes) {
    // necessite_intervention_le n'existe que sur artiste_sources — résolution
    // naturelle dès qu'un fetch redevient positif ET que le nom TM revérifié
    // reste cohérent.
    final boolean estArtisteSources = type == TypeEntiteSource.ARTISTE;
    final String colonneInterventionResolue =
        estArtisteSources
            ? ", necessite_intervention_le = CASE WHEN :effacerIntervention THEN NULL ELSE"
                + " necessite_intervention_le END"
            : "";
    // Artiste confirmé vivant récemment : des résultats vides signifient
    // seulement qu'il n'a pas de date annoncée.
    final String vivaciteRecente =
        estArtisteSources
            ? " AND (vivacite_verifiee_le IS NULL"
                + " OR vivacite_verifiee_le < now() - make_interval(days => :delaiVivacite))"
            : "";
    final String sql =
        "UPDATE "
            + type.table()
            + " SET nb_zero_consecutifs = CASE WHEN :succes THEN 0 ELSE nb_zero_consecutifs + 1"
            + " END, suspect_depuis = CASE WHEN :succes THEN NULL WHEN nb_zero_consecutifs + 1 >="
            + " :seuil AND suspect_depuis IS NULL"
            + vivaciteRecente
            + " THEN now() ELSE suspect_depuis END"
            + colonneInterventionResolue
            + " WHERE source = :source AND identifiant_externe = :identifiant";
    try {
      final boolean effacerIntervention =
          estArtisteSources && succes ? peutEffacerIntervention(source, identifiant) : succes;
      jdbc.update(
          sql,
          Map.of(
              "succes",
              succes,
              "seuil",
              SEUIL_SUSPECT,
              CLE_SOURCE,
              source,
              "identifiant",
              identifiant,
              "effacerIntervention",
              effacerIntervention,
              "delaiVivacite",
              DELAI_REVERIFICATION_VIVACITE_JOURS));
    } catch (Exception e) {
      LOG.warn(
          "Impossible de mettre à jour la santé source={} identifiant={}", source, identifiant, e);
    }
  }

  /**
   * N'autorise l'effacement de necessite_intervention_le que si le
   * nom TM revérifié reste cohérent (détail : CLAUDE.md,
   * Santé/référentiel admin).
   * @param source source de l'{@code artiste_sources} concerné
   * @param identifiant identifiant externe (TM : attractionId brut)
   * @return {@code true} si l'effacement peut avoir lieu
   */
  private boolean peutEffacerIntervention(final String source, final String identifiant) {
    if (!"ticketmaster".equals(source)) {
      return true;
    }
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT asrc.necessite_intervention_le, a.nom
            FROM artiste_sources asrc JOIN artistes a ON a.id = asrc.artiste_id
            WHERE asrc.source = :source AND asrc.identifiant_externe = :identifiant
            """,
            Map.of(CLE_SOURCE, source, "identifiant", identifiant));
    if (lignes.isEmpty() || lignes.get(0).get("necessite_intervention_le") == null) {
      return true;
    }
    final String nomAttraction = tm.recupererNomAttraction(identifiant);
    return nomAttraction != null
        && Normalisation.normaliserTexte((String) lignes.get(0).get("nom"))
            .equals(Normalisation.normaliserTexte(nomAttraction));
  }
}

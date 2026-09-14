/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.TicketmasterSource;
import java.util.List;
import java.util.Map;
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

  /**
   * @param jdbc accès JDBC
   * @param tm revérification du nom TM avant effacement d'une intervention
   *     requise
   */
  public SuiviSanteSourcesService(
      final NamedParameterJdbcTemplate jdbc, final TicketmasterSource tm) {
    this.jdbc = jdbc;
    this.tm = tm;
  }

  /**
   * Enregistre le résultat d'un fetch salle (identifiant préfixé, ex.
   * {@code "tm:rZFSnyZdFvA"}, {@code "digitick:slug"}).
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
    final String sql =
        "UPDATE "
            + type.table()
            + " SET nb_zero_consecutifs = CASE WHEN :succes THEN 0 ELSE nb_zero_consecutifs + 1"
            + " END, suspect_depuis = CASE WHEN :succes THEN NULL WHEN nb_zero_consecutifs + 1 >="
            + " :seuil AND suspect_depuis IS NULL THEN now() ELSE suspect_depuis END"
            + colonneInterventionResolue
            + " WHERE source = :source AND identifiant_externe = :identifiant";
    try {
      final boolean effacerIntervention =
          estArtisteSources && succes ? peutEffacerIntervention(source, identifiant) : succes;
      jdbc.update(
          sql,
          Map.of(
              "succes", succes,
              "seuil", SEUIL_SUSPECT,
              "source", source,
              "identifiant", identifiant,
              "effacerIntervention", effacerIntervention));
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
            Map.of("source", source, "identifiant", identifiant));
    if (lignes.isEmpty() || lignes.get(0).get("necessite_intervention_le") == null) {
      return true;
    }
    final String nomAttraction = tm.recupererNomAttraction(identifiant);
    return nomAttraction != null
        && Normalisation.normaliserTexte((String) lignes.get(0).get("nom"))
            .equals(Normalisation.normaliserTexte(nomAttraction));
  }
}

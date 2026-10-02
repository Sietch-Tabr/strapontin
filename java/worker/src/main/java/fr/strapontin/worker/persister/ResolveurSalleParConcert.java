/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.Normalisation;
import fr.strapontin.core.PositionLieu;
import fr.strapontin.sources.ClientNominatim;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Résolution de la salle d'un concert entrant (identifiant, tokens, ou clé
 * forte artiste+date+heure+dept), ou création — extrait de {@link
 * ReconciliateurSalles} (v2.8.7, dette de taille pure).
 *
 * @since 2.8.7
 * @author Kwaite
 */
@Service
class ResolveurSalleParConcert {

  private static final Logger LOG = LoggerFactory.getLogger(ResolveurSalleParConcert.class);
  private static final String CLE_DEPT = "dept";
  private static final String CLE_NOM = "nom";
  private static final String CLE_SALLE_CLE = "salleCle";
  // Département connu d'une salle, avec repli sur un concert déjà rattaché
  // (salles.dept n'est pas toujours renseigné).
  private static final String DEPT_SALLE =
      "COALESCE(s.dept, (SELECT c.dept FROM concerts c WHERE c.salle_id = s.id"
          + " AND c.dept IS NOT NULL LIMIT 1))";

  private final NamedParameterJdbcTemplate jdbc;
  private final ClientNominatim clientNominatim;
  private final RaccrocheurSallesParNomNorm raccrocheur;
  private final ResolveurSalleParProximiteGps resolveurGps;
  private final CreateurSalle createur;
  private final ResolveurSallePosition parPosition;
  private boolean resolutionParPosition = true;

  /* package-private : instancié uniquement par ReconciliateurSalles (même
   * package). */
  ResolveurSalleParConcert(
      final NamedParameterJdbcTemplate jdbc, final ClientNominatim clientNominatim) {
    this.jdbc = jdbc;
    this.clientNominatim = clientNominatim;
    this.raccrocheur = new RaccrocheurSallesParNomNorm(jdbc);
    this.resolveurGps = new ResolveurSalleParProximiteGps(jdbc);
    this.createur = new CreateurSalle(jdbc, this::deptsConnus);
    this.parPosition = new ResolveurSallePosition(jdbc);
  }

  /**
   * Active (par défaut) la résolution par position du lieu ({@link ResolveurSallePosition}) :
   * recherche d'abord une salle au même endroit, écarte toute salle trouvée
   * par son nom mais distante du lieu, et crée une salle à la position
   * publiée plutôt que de géocoder son nom.
   * @param active valeur de {@code strapontin.resolution-salles.par-position}
   */
  @Autowired
  void setResolutionParPosition(
      @Value("${strapontin.resolution-salles.par-position:true}") final boolean active) {
    this.resolutionParPosition = active;
  }

  /**
   * Résout ou crée une salle, insère son mapping source dans salle_sources
   * (nom exact, tokens LIKE ANY, puis {@link #chercherSalleParCleForte}).
   * @param concert concert portant le {@code salleSourceId} à résoudre
   * @return UUID de la salle, ou {@code null} si salleSourceId mal formé
   */
  /* package-private : appelé par ReconciliateurSalles (même package). */
  UUID upsertSalleSource(final ValeurConcert concert) {
    final int sep = concert.salleSourceId().indexOf(':');
    if (sep <= 0) {
      return null;
    }
    final String source = concert.salleSourceId().substring(0, sep);
    final String identifiant = concert.salleSourceId().substring(sep + 1);

    final UUID salleId = resoudreOuCreerSalleId(concert);
    if (salleId == null) {
      return null;
    }

    // salles.dept n'était écrit qu'à la création puis jamais mis à jour —
    // 281/284 salles sans dept en prod avaient pourtant l'info via un concert
    // déjà lié. COALESCE : n'écrase jamais une valeur déjà connue, même pattern
    // que wikidata_id/nom_affiche (EnrichissementSallesService).
    if (concert.dept() != null) {
      jdbc.update(
          "UPDATE salles SET dept = COALESCE(dept, :dept) WHERE id = CAST(:uuid AS uuid)",
          Map.of(CLE_DEPT, concert.dept(), "uuid", salleId.toString()));
    }

    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:uuid AS uuid), :source, :identifiant)"
            + " ON CONFLICT DO NOTHING",
        Map.of("uuid", salleId.toString(), "source", source, "identifiant", identifiant));

    LOG.info(
        "salle upsert source salle={} source={} identifiant={} uuid={}",
        concert.salle(),
        source,
        identifiant,
        salleId);
    return salleId;
  }

  private UUID resoudreOuCreerSalleId(final ValeurConcert concert) {
    final PositionLieu position = concert.position();
    if (resolutionParPosition) {
      final ResolveurSallePosition.SalleProche proche =
          parPosition.chercher(concert.salle(), concert.dept(), position);
      if (proche != null) {
        LOG.info(
            "salle résolue par position salle={} uuid={} distance={}m",
            concert.salle(),
            proche.id(),
            Math.round(proche.distance()));
        return proche.id();
      }
    }

    UUID salleId = retenir(chercherSalleParNomExact(concert.salle(), concert.dept()), concert);

    if (salleId == null) {
      salleId = retenir(chercherSalleParTokens(concert.salle(), concert.dept()), concert);
    }

    if (salleId == null) {
      salleId = retenir(resoudreSousSalle(concert.salle(), concert.dept()), concert);
    }

    if (salleId == null) {
      salleId = retenir(chercherSalleParCleForte(concert), concert);
    }

    // Créée sans coordonnées : l'enregistrement des positions y pose celle publiée par la source.
    if (salleId == null && resolutionParPosition && position.estGeolocalisee()) {
      return createur.creer(concert, Optional.empty());
    }

    Optional<ClientNominatim.Coordonnees> coordsGeocodees = Optional.empty();
    if (salleId == null) {
      // Un nom générique ("Palais des Congrès") se géocode n'importe où en France
      // sans la ville.
      coordsGeocodees =
          clientNominatim.geocoder(
              concert.ville() != null ? concert.salle() + ", " + concert.ville() : concert.salle());
      if (coordsGeocodees.isPresent()) {
        salleId = resolveurGps.chercher(coordsGeocodees.get(), concert.salle(), concert.dept());
      }
    }

    return salleId != null ? salleId : createur.creer(concert, coordsGeocodees);
  }

  // Écarte une salle trouvée par son nom mais géolocalisée loin du lieu publié (homonyme).
  private UUID retenir(final UUID candidat, final ValeurConcert concert) {
    if (resolutionParPosition && parPosition.incompatible(candidat, concert.position())) {
      LOG.warn(
          "salle candidate écartée : position incompatible avec le lieu salle={} candidat={}",
          concert.salle(),
          candidat);
      return null;
    }
    return candidat;
  }

  // Salle portant exactement ce nom, dans le département du concert : des
  // dizaines de salles distinctes partagent un nom générique ("Palais des
  // Congrès") ; sans ce filtre, la première trouvée ailleurs était retenue.
  private UUID chercherSalleParNomExact(final String nom, final String dept) {
    final List<UUID> existants =
        jdbc.query(
            "SELECT s.id FROM salles s WHERE unaccent(lower(s.nom)) = unaccent(lower(:nom))"
                + " AND (CAST(:dept AS text) IS NULL OR "
                + DEPT_SALLE
                + " IS NULL OR "
                + DEPT_SALLE
                + " = :dept)"
                + " ORDER BY ("
                + DEPT_SALLE
                + " = :dept) DESC NULLS LAST LIMIT 1",
            new MapSqlParameterSource()
                .addValue(CLE_NOM, nom)
                .addValue(CLE_DEPT, dept, Types.VARCHAR),
            (rs, _) -> rs.getObject("id", UUID.class));
    return existants.isEmpty() ? null : existants.get(0);
  }

  /**
   * Cherche une salle dont nom_norm est un doublon du nom fourni (requête
   * unique sur tous les tokens + nom_norm sans espaces pour les variantes
   * d'apostrophe), filtre dept avec repli sur un concert déjà rattaché.
   * @param nom nom brut de la salle à créer
   * @param dept département connu du concert (nullable — désactive le filtre)
   * @return UUID de la salle existante, ou {@code null} si aucune
   */
  /* package-private : appelé par
   * ReconciliateurSalles/RaccrocheurSallesParNomNorm (même package). */
  UUID chercherSalleParTokens(final String nom, final String dept) {
    final String nomNorm = Normalisation.normaliserTexte(nom);
    final Set<String> tokens = CorrespondanceSalle.tokensSignificatifs(nomNorm);
    final String nomNormSquash = nomNorm.replace(" ", "");
    if (tokens.isEmpty() && nomNormSquash.isEmpty()) {
      return null;
    }

    final List<Map<String, Object>> lignes =
        requeterCandidatsParTokens(tokens, nomNormSquash, dept);
    return meilleurCandidatValide(nom, nomNorm, lignes);
  }

  private List<Map<String, Object>> requeterCandidatsParTokens(
      final Set<String> tokens, final String nomNormSquash, final String dept) {
    final List<String> tokensList = new ArrayList<>(tokens);
    final StringBuilder sbWhere = new StringBuilder(100).append('(');
    for (int i = 0; i < tokensList.size(); i++) {
      if (i > 0) {
        sbWhere.append(" OR ");
      }
      sbWhere.append("nom_norm LIKE :t").append(i);
    }
    if (!tokensList.isEmpty()) {
      sbWhere.append(" OR ");
    }
    sbWhere.append("replace(nom_norm, ' ', '') = :nomNormSquash)");
    final String whereTokens = sbWhere.toString();

    final MapSqlParameterSource params = new MapSqlParameterSource();
    for (int i = 0; i < tokensList.size(); i++) {
      params.addValue("t" + i, "%" + tokensList.get(i) + "%");
    }
    params.addValue("nomNormSquash", nomNormSquash);
    params.addValue(CLE_DEPT, dept, Types.VARCHAR);

    // salles.dept n'est écrit qu'à la création et n'est jamais mis à jour
    // ensuite, contrairement à concerts.dept (fiablement renseigné à chaque
    // ingestion) — un simple "dept IS NULL" désactivait le filtre pour la
    // quasi-totalité des salles existantes. Repli sur le département d'un
    // concert déjà rattaché à la salle candidate quand salles.dept est vide.
    return jdbc.queryForList(
        "SELECT s.id AS id, s.nom AS nom, s.nom_norm AS nom_norm FROM salles s"
            + " WHERE "
            + whereTokens
            + " AND (:dept IS NULL"
            + "      OR COALESCE(s.dept, (SELECT c.dept FROM concerts c"
            + "                           WHERE c.salle_id = s.id AND c.dept IS NOT NULL"
            + "                           LIMIT 1)) IS NULL"
            + "      OR COALESCE(s.dept, (SELECT c.dept FROM concerts c"
            + "                           WHERE c.salle_id = s.id AND c.dept IS NOT NULL"
            + "                           LIMIT 1)) = :dept)"
            + " LIMIT 50",
        params);
  }

  /**
   * Retient, parmi les candidats validés, celui au Jaccard de tokens le plus
   * élevé — pas le premier de la liste (ordre SQL non garanti), un nom
   * générique de complexe multi-salles pouvant valider plusieurs salles
   * distinctes via le seul pivot. qualificatifsDivergents écarte aussi tout
   * candidat dont le qualificatif de sous-salle diverge.
   */
  private UUID meilleurCandidatValide(
      final String nom, final String nomNorm, final List<Map<String, Object>> lignes) {
    UUID meilleurUuid = null;
    String meilleurNomNorm = null;
    double meilleurScore = -1;
    for (final Map<String, Object> ligne : lignes) {
      final UUID uuid = (UUID) ligne.get("id");
      final String nomCandidat = (String) ligne.get(CLE_NOM);
      final String nomNormCandidat = (String) ligne.get("nom_norm");
      if (!CorrespondanceSalle.estDoublonSalle(nomNorm, nomNormCandidat)) {
        continue;
      }
      if (CorrespondanceSalle.qualificatifsDivergents(nom, nomCandidat)) {
        continue;
      }
      final double score = CorrespondanceSalle.jaccardTokens(nomNorm, nomNormCandidat);
      if (score > meilleurScore) {
        meilleurScore = score;
        meilleurUuid = uuid;
        meilleurNomNorm = nomNormCandidat;
      }
    }
    if (meilleurUuid != null) {
      LOG.info(
          "salle doublon détecté par tokens : '{}' ≈ '{}' uuid={} score={}",
          nom,
          meilleurNomNorm,
          meilleurUuid,
          meilleurScore);
    }
    return meilleurUuid;
  }

  /**
   * Résout/crée une sous-salle rattachée à un complexe connu (nom qualifié,
   * ex. "La Seine Musicale - Grande Seine") plutôt que de mélanger ses
   * événements avec ceux du complexe.
   * @param nom nom brut entrant, potentiellement qualifié
   * @param dept département connu du concert
   * @return UUID de la sous-salle, ou {@code null} si pas de qualificatif
   */
  private UUID resoudreSousSalle(final String nom, final String dept) {
    final String base = CorrespondanceSalle.extraireBase(nom);
    if (base == null) {
      return null;
    }

    final UUID complexeId = chercherSalleParTokens(base, dept);
    if (complexeId == null) {
      return null;
    }

    final String salleCle = Normalisation.normaliserTexte(nom);
    // MapSqlParameterSource plutôt que Map.of — dept peut être null ici (Map.of
    // lève une NPE sur toute valeur null).
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm, dept, salle_parent_id)"
            + " VALUES (:salleCle, :nom, :nomNorm, :dept, CAST(:parentId AS uuid))"
            + " ON CONFLICT (salle_cle) DO NOTHING",
        new MapSqlParameterSource()
            .addValue(CLE_SALLE_CLE, salleCle)
            .addValue(CLE_NOM, nom)
            .addValue("nomNorm", salleCle)
            .addValue(CLE_DEPT, dept)
            .addValue("parentId", complexeId.toString()));
    final UUID sousSalleId =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = :salleCle",
            Map.of(CLE_SALLE_CLE, salleCle),
            UUID.class);
    LOG.info(
        "sous-salle créée pour complexe nom={} base={} complexeId={} sousSalleId={}",
        nom,
        base,
        complexeId,
        sousSalleId);
    return sousSalleId;
  }

  /**
   * Recherche la salle d'un concert existant à même artiste/date/heure/dept
   * — dernier repli quand deux sources désignent le même évènement sous des
   * noms sans rapport (ex. Eventim "Ed&n"/TM "Espace Dollfus Noack").
   * @param concert concert dont la salle reste à résoudre
   * @return UUID de la salle du concert existant correspondant, ou {@code null}
   */
  /* package-private : appelé par ReconciliateurSalles (même package). */
  UUID chercherSalleParCleForte(final ValeurConcert concert) {
    if (concert.heure() == null || concert.dept() == null || concert.artisteNorm() == null) {
      return null;
    }
    final List<UUID> candidats =
        jdbc.query(
            "SELECT DISTINCT salle_id FROM concerts"
                + " WHERE artiste_norm = :artisteNorm AND date = :date AND heure = :heure"
                + " AND dept = :dept AND salle_id IS NOT NULL"
                + " ORDER BY salle_id"
                + " LIMIT 20",
            Map.of(
                "artisteNorm",
                concert.artisteNorm(),
                "date",
                concert.date(),
                "heure",
                concert.heure(),
                CLE_DEPT,
                concert.dept()),
            (rs, _) -> rs.getObject("salle_id", UUID.class));
    if (candidats.isEmpty()) {
      return null;
    }

    final UUID salleId = premierCandidatGeographiquementCoherent(candidats, concert.dept());
    if (salleId == null) {
      LOG.warn(
          "clé forte (artiste+date+heure+dept) sans candidat géographiquement cohérent"
              + " artiste={} date={} candidats={}",
          concert.artiste(),
          concert.date(),
          candidats);
      return null;
    }

    LOG.info(
        "salle rattachée par clé forte (artiste+date+heure+dept) artiste={} date={} salle={}"
            + " uuid={}",
        concert.artiste(),
        concert.date(),
        concert.salle(),
        salleId);
    return salleId;
  }

  /**
   * Retient, parmi les candidats de la clé forte, le premier dont le
   * département connu ne contredit pas le concert entrant (évite qu'un
   * concert mal rattaché reconfirme son propre mauvais rattachement).
   * @param candidats identifiants candidats, dans un ordre déterministe
   * @param deptEntrant département du concert à résoudre
   * @return premier candidat géographiquement cohérent, ou {@code null}
   */
  private UUID premierCandidatGeographiquementCoherent(
      final List<UUID> candidats, final String deptEntrant) {
    final Map<UUID, String> depts = deptsConnus(new HashSet<>(candidats));
    for (final UUID candidat : candidats) {
      final String deptConnu = depts.get(candidat);
      if (deptConnu == null || deptConnu.equals(deptEntrant)) {
        return candidat;
      }
    }
    return null;
  }

  /**
   * @param concerts concerts après déduplication
   * @return liste enrichie avec les {@code salleId} résolus ou réalignés par
   *     nom_norm — voir {@link RaccrocheurSallesParNomNorm}
   */
  /* package-private : appelé par ReconciliateurSalles (même package). */
  List<ValeurConcert> raccrocherSallesParNomNorm(final List<ValeurConcert> concerts) {
    return raccrocheur.raccrocher(concerts);
  }

  /**
   * Charge en une requête le département connu de chaque salle (colonne
   * dept, avec repli sur un concert déjà rattaché comme
   * chercherSalleParTokens) — valide la cohérence géographique d'un mapping
   * salle_sources réutilisé sans une requête par concert.
   * @param salleIds identifiants des salles à vérifier
   * @return département connu par salle, absente si aucun département connu
   */
  /* package-private : appelé par ReconciliateurSalles (même package). */
  Map<UUID, String> deptsConnus(final Set<UUID> salleIds) {
    if (salleIds.isEmpty()) {
      return Map.of();
    }
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT s.id AS id, COALESCE(s.dept, (SELECT c.dept FROM concerts c"
                + " WHERE c.salle_id = s.id AND c.dept IS NOT NULL LIMIT 1)) AS dept"
                + " FROM salles s WHERE s.id IN (:ids)",
            Map.of("ids", salleIds));
    final Map<UUID, String> resultat = new HashMap<>();
    for (final Map<String, Object> ligne : lignes) {
      final String dept = (String) ligne.get(CLE_DEPT);
      if (dept != null) {
        resultat.put((UUID) ligne.get("id"), dept);
      }
    }
    return resultat;
  }
}

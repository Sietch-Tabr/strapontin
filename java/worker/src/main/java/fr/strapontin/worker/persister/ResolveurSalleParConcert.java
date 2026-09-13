/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.Normalisation;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Résolution de la salle d'un concert entrant (par identifiant source, par tokens de nom, ou par
 * clé forte artiste+date+heure+dept), ou création si aucune ne correspond — extrait de {@link
 * ReconciliateurSalles} (v2.8.7, dette de taille pure, aucun changement fonctionnel).
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

  private final NamedParameterJdbcTemplate jdbc;
  private final ClientNominatim clientNominatim;
  private final RaccrocheurSallesParNomNorm raccrocheur;

  /*
   * package-private : instancié uniquement par ReconciliateurSalles (même
   * package).
   */
  ResolveurSalleParConcert(
      final NamedParameterJdbcTemplate jdbc, final ClientNominatim clientNominatim) {
    this.jdbc = jdbc;
    this.clientNominatim = clientNominatim;
    this.raccrocheur = new RaccrocheurSallesParNomNorm(jdbc);
  }

  /**
   * Résout ou crée une salle et insère son mapping source dans {@code salle_sources}.
   *
   * <p>Cherche d'abord par {@code unaccent(lower(nom))} (égalité exacte), puis par tokens
   * significatifs via une requête unique {@code LIKE ANY} (corrige le pattern N+1), puis par clé
   * forte (voir {@link #chercherSalleParCleForte}). Si aucune correspondance, crée une nouvelle
   * salle.
   *
   * @param concert concert portant le {@code salleSourceId} à résoudre
   * @return UUID de la salle résolue ou créée, ou {@code null} si le {@code salleSourceId} est mal
   *     formé
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
    final List<UUID> existants =
        jdbc.query(
            "SELECT id FROM salles WHERE unaccent(lower(nom)) = unaccent(lower(:nom)) LIMIT 1",
            Map.of(CLE_NOM, concert.salle()),
            (rs, _) -> rs.getObject("id", UUID.class));
    UUID salleId = existants.isEmpty() ? null : existants.get(0);

    if (salleId == null) {
      salleId = chercherSalleParTokens(concert.salle(), concert.dept());
    }

    if (salleId == null) {
      salleId = resoudreSousSalle(concert.salle(), concert.dept());
    }

    if (salleId == null) {
      salleId = chercherSalleParCleForte(concert);
    }

    Optional<ClientNominatim.Coordonnees> coordsGeocodees = Optional.empty();
    if (salleId == null) {
      coordsGeocodees = clientNominatim.geocoder(concert.salle());
      if (coordsGeocodees.isPresent()) {
        salleId = chercherSalleParProximiteGps(coordsGeocodees.get(), concert.dept());
      }
    }

    return salleId != null ? salleId : creerNouvelleSalle(concert, coordsGeocodees);
  }

  private UUID creerNouvelleSalle(
      final ValeurConcert concert, final Optional<ClientNominatim.Coordonnees> coordsGeocodees) {
    final String salleCle = Normalisation.normaliserTexte(concert.salle());
    final MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue(CLE_SALLE_CLE, salleCle)
            .addValue(CLE_NOM, concert.salle())
            .addValue("nomNorm", salleCle)
            .addValue(CLE_DEPT, concert.dept())
            .addValue(
                "latitude", coordsGeocodees.map(ClientNominatim.Coordonnees::latitude).orElse(null))
            .addValue(
                "longitude",
                coordsGeocodees.map(ClientNominatim.Coordonnees::longitude).orElse(null));
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm, dept, latitude, longitude)"
            + " VALUES (:salleCle, :nom, :nomNorm, :dept, :latitude, :longitude)"
            + " ON CONFLICT (salle_cle) DO NOTHING",
        params);
    return jdbc.queryForObject(
        "SELECT id FROM salles WHERE salle_cle = :salleCle",
        Map.of(CLE_SALLE_CLE, salleCle),
        UUID.class);
  }

  /**
   * Dernier recours avant de conclure à une salle réellement nouvelle (aucune correspondance par
   * nom ni par clé forte) : cherche, parmi les salles déjà connues dont les coordonnées sont
   * connues, une salle proche des coordonnées géocodées du nom entrant — même seuil que {@link
   * FusionSallesClePorteeService#coordonneesConnuesEtProches}, réutilisé tel quel. Couvre le cas où
   * deux sources désignent le même lieu physique sous des noms sans aucun rapport textuel, sans
   * même partager d'artiste/date/heure/dept déjà en base.
   *
   * @param coords coordonnées géocodées du nom entrant
   * @param dept département connu du concert (filtre les candidats, désactivé si absent)
   * @return UUID de la salle existante la plus proche dans le rayon autorisé, ou {@code null} si
   *     aucune
   */
  private UUID chercherSalleParProximiteGps(
      final ClientNominatim.Coordonnees coords, final String dept) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT id, latitude, longitude FROM salles"
                + " WHERE latitude IS NOT NULL AND longitude IS NOT NULL"
                + " AND (:dept IS NULL OR dept IS NULL OR dept = :dept)",
            new MapSqlParameterSource().addValue(CLE_DEPT, dept, Types.VARCHAR));
    for (final Map<String, Object> ligne : lignes) {
      final Double latitude = (Double) ligne.get("latitude");
      final Double longitude = (Double) ligne.get("longitude");
      if (FusionSallesClePorteeService.coordonneesConnuesEtProches(
          coords.latitude(), coords.longitude(), latitude, longitude)) {
        final UUID salleId = (UUID) ligne.get("id");
        LOG.info("salle résolue par proximité GPS (géocodage dernier recours) uuid={}", salleId);
        return salleId;
      }
    }
    return null;
  }

  /**
   * Cherche une salle existante dont le {@code nom_norm} est un doublon du nom fourni.
   *
   * <p>Émet une seule requête SQL pour tous les tokens significatifs du nom (corrige le pattern N+1
   * antérieur où une requête était émise par token), complétée par une clause sur le {@code
   * nom_norm} sans espaces : sans elle, une variante d'apostrophe/article entre deux sources ("l
   * olympia" vs "lolympia") ne partage aucun token substantiel et le candidat n'est jamais
   * récupéré, quand bien même {@link CorrespondanceSalle#estDoublonSalle} le détecterait. Le filtre
   * {@code dept} élimine les salles homonymes de départements distincts, réduisant le risque de
   * faux positif du critère pivot. Repli sur le département d'un concert déjà rattaché à la salle
   * candidate quand {@code salles.dept} est vide — cette colonne n'est écrite qu'à la création et
   * jamais mise à jour ensuite, contrairement à {@code concerts.dept}.
   *
   * @param nom nom brut de la salle à créer
   * @param dept département connu du concert (nullable — désactive le filtre si absent)
   * @return UUID de la salle existante correspondante, ou {@code null} si aucune trouvée
   */
  /*
   * package-private : appelé par
   * ReconciliateurSalles/RaccrocheurSallesParNomNorm (même package).
   */
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
   * Retient, parmi les candidats validés par {@link CorrespondanceSalle#estDoublonSalle}, celui au
   * Jaccard de tokens le plus élevé, pas le premier de la liste (l'ordre SQL n'est pas garanti) :
   * un nom générique de complexe multi-salles (ex. "La Seine Musicale - Boulogne Billancourt") peut
   * valider plusieurs salles physiquement distinctes du même complexe via le seul critère pivot —
   * sans ce score, le choix entre elles n'était pas déterministe et pouvait rattacher au mauvais
   * auditorium alors qu'une salle plus proche textuellement existait dans le même lot de candidats.
   *
   * <p>{@link CorrespondanceSalle#qualificatifsDivergents} écarte en amont tout candidat dont le
   * qualificatif de sous-salle brut (ex. "- Grande Seine" / "- Auditorium P.devedjian") diverge du
   * nôtre — le nom du complexe seul ne doit jamais suffire à conclure à un doublon entre deux halls
   * physiquement distincts.
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
   * Résout ou crée une sous-salle rattachée à un complexe déjà connu, quand le nom entrant porte un
   * qualificatif de sous-salle (ex. "La Seine Musicale - Grande Seine") et qu'aucune sous-salle
   * exacte n'a encore été trouvée par {@link #chercherSalleParTokens}.
   *
   * <p>Rattache toujours le concert à une sous-salle nouvellement créée plutôt qu'au complexe lui
   * même — un nom sans qualificatif continue de résoudre vers le complexe (site, salle non
   * précisée), mais un nom qualifié ne doit jamais mélanger son événement avec ceux d'un autre hall
   * du même complexe.
   *
   * @param nom nom brut entrant, potentiellement qualifié
   * @param dept département connu du concert
   * @return UUID de la sous-salle résolue ou créée, ou {@code null} si {@code nom} ne porte aucun
   *     qualificatif ou si le complexe de base n'est pas encore connu
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
   * Recherche la salle d'un concert existant partageant exactement le même artiste, la même date,
   * la même heure et le même département qu'un concert à résoudre — dernier repli avant création
   * d'une salle, pour les cas où deux sources désignent le même évènement réel sous des noms de
   * salle sans aucun rapport textuel (ex. Eventim "Ed&n" / TM "Espace Dollfus Noack" pour la même
   * salle physique).
   *
   * <p>Deux sources indépendantes rapportant le même artiste à la minute et au département près ne
   * peuvent structurellement pas désigner deux évènements réels distincts — restreint aux cas où
   * l'heure et le département sont tous deux connus pour ne pas affaiblir la spécificité de la clé.
   *
   * @param concert concert dont la salle reste à résoudre
   * @return UUID de la salle du concert existant correspondant, ou {@code null} si aucun
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
   * Retient, parmi plusieurs salles candidates partageant la même clé forte, la première dont le
   * département connu ne contredit pas celui du concert entrant.
   *
   * <p>Sans ce filtre, un concert déjà mal rattaché (département de sa salle incohérent avec le
   * sien) pouvait servir de référence et reconfirmer son propre mauvais rattachement — la requête
   * SQL source ne garantit qu'un ordre déterministe, pas la pertinence géographique du résultat.
   *
   * @param candidats identifiants de salle candidats, dans un ordre déterministe
   * @param deptEntrant département du concert à résoudre
   * @return premier candidat géographiquement cohérent, ou {@code null} si aucun ne l'est
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
   * Charge en une seule requête le département connu de chaque salle donnée (colonne {@code dept},
   * avec le même repli sur un concert déjà rattaché que {@link #chercherSalleParTokens}) — permet
   * de valider la cohérence géographique d'un mapping {@code salle_sources} réutilisé sans une
   * requête par concert.
   *
   * @param salleIds identifiants des salles à vérifier
   * @return département connu par salle, absente si aucun département connu (ni {@code
   *     salles.dept}, ni aucun concert déjà rattaché)
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

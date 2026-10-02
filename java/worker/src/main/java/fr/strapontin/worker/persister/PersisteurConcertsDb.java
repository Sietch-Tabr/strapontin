/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceArtiste;
import fr.strapontin.core.IdentifiantLieuEventim;
import fr.strapontin.core.Normalisation;
import java.sql.Time;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Service de persistance SQL pur pour les concerts (modèle canonique
 * evenements+observations, résolution avant écriture) — résout les UUIDs
 * depuis les identifiants sources préfixés (salle_sources, artiste_sources).
 *
 * @since 2.5.7
 * @author Kwaite
 */
@Service
public class PersisteurConcertsDb {

  // Tolérance sur l'écart d'heure entre deux observations d'un même événement
  // réel (fiches TM de types différents pour le même évènement) — très en
  // dessous de tout écart matinée/soirée réel.
  private static final int TOLERANCE_HEURE_MINUTES = 3;
  private static final String CLE_SALLE = "salle";
  private static final String CLE_DATE = "date";
  private static final String CLE_HEURE = "heure";
  private static final String PREFIXE_EVENTIM = "eventim:";

  private final NamedParameterJdbcTemplate jdbc;
  private final ResolveurArtisteParUrl resolveurArtisteParUrl;
  private final AlignementReportTM alignementReportTM;
  private final EnregistreurPositionsLieux enregistreurPositions;

  private record ResolutionEvenement(UUID id, boolean nouveau) {}

  /**
   * @param jdbc template JDBC injecté par Spring
   * @param resolveurArtisteParUrl résolution des doublons Eventim par URL
   */
  public PersisteurConcertsDb(
      final NamedParameterJdbcTemplate jdbc, final ResolveurArtisteParUrl resolveurArtisteParUrl) {
    this.jdbc = jdbc;
    this.resolveurArtisteParUrl = resolveurArtisteParUrl;
    this.alignementReportTM = new AlignementReportTM(jdbc);
    this.enregistreurPositions = new EnregistreurPositionsLieux(jdbc);
  }

  /**
   * Enregistre la position publiée de chaque lieu sur son mapping et sa
   * salle ({@link EnregistreurPositionsLieux}).
   * @param concerts concerts dont l'identifiant source de salle est résolu
   * @param cachesSalles identifiant source préfixé → salle
   */
  /* package-private : appelé par PersisterConcerts (même package). */
  void enregistrerPositions(
      final List<ValeurConcert> concerts, final Map<String, UUID> cachesSalles) {
    enregistreurPositions.enregistrer(concerts, cachesSalles);
  }

  /**
   * Rattache un lieu Eventim publié sous un identifiant qualifié par code
   * postal encore inconnu à la salle de son identifiant historique (nom
   * seul), si cette salle est dans le même département : un mapping qualifié
   * est alors créé. Les lieux homonymes d'autres départements, qui
   * partageaient l'identifiant historique, en restent écartés.
   * @param concerts concerts du lot
   * @param cachesSalles identifiant source préfixé → salle, complété en place
   */
  /* package-private : appelé par PersisterConcerts (même package). */
  void reprendreIdentifiantsEventimHistoriques(
      final List<ValeurConcert> concerts, final Map<String, UUID> cachesSalles) {
    final Map<String, ValeurConcert> qualifiesInconnus = new HashMap<>();
    for (final ValeurConcert c : concerts) {
      final String id = c.salleSourceId();
      if (id != null
          && id.startsWith(PREFIXE_EVENTIM)
          && !cachesSalles.containsKey(id)
          && !IdentifiantLieuEventim.historique(id).equals(id)) {
        qualifiesInconnus.putIfAbsent(id, c);
      }
    }
    if (qualifiesInconnus.isEmpty()) {
      return;
    }
    final List<String> historiques =
        qualifiesInconnus.keySet().stream()
            .map(id -> IdentifiantLieuEventim.historique(id.substring(PREFIXE_EVENTIM.length())))
            .distinct()
            .toList();
    final Map<String, Map<String, Object>> parHistorique = new HashMap<>();
    for (final Map<String, Object> ligne :
        jdbc.queryForList(
            """
            SELECT ss.identifiant_externe AS identifiant, ss.salle_id AS salle_id, s.dept AS dept
            FROM salle_sources ss JOIN salles s ON s.id = ss.salle_id
            WHERE ss.source = 'eventim' AND ss.identifiant_externe IN (:ids)
            """,
            Map.of("ids", historiques))) {
      parHistorique.put((String) ligne.get("identifiant"), ligne);
    }
    for (final Map.Entry<String, ValeurConcert> entree : qualifiesInconnus.entrySet()) {
      final String qualifie = entree.getKey().substring(PREFIXE_EVENTIM.length());
      final Map<String, Object> historique =
          parHistorique.get(IdentifiantLieuEventim.historique(qualifie));
      if (historique == null) {
        continue;
      }
      final String deptSalle = (String) historique.get("dept");
      final String deptEntrant = entree.getValue().dept();
      if (deptSalle != null && deptEntrant != null && !deptSalle.equals(deptEntrant)) {
        continue;
      }
      final UUID salleId = (UUID) historique.get("salle_id");
      jdbc.update(
          """
          INSERT INTO salle_sources (salle_id, source, identifiant_externe)
          VALUES (CAST(:salleId AS uuid), 'eventim', :identifiant)
          ON CONFLICT (source, identifiant_externe) DO NOTHING
          """,
          Map.of("salleId", salleId.toString(), "identifiant", qualifie));
      cachesSalles.put(entree.getKey(), salleId);
    }
  }

  /**
   * Résout les UUIDs internes depuis les identifiants sources préfixés
   * (salle_sources/artiste_sources par lot).
   * @param concerts concerts à résoudre
   * @param type "salle" ou "artiste"
   * @return cache des UUIDs résolus par identifiant préfixé
   */
  /* package-private : appelé par PersisterConcerts (même package). */
  Map<String, UUID> resoudreUuidsParSource(final List<ValeurConcert> concerts, final String type) {
    final Map<String, List<String>> parSource = new HashMap<>();
    for (final ValeurConcert c : concerts) {
      final String sourceId = CLE_SALLE.equals(type) ? c.salleSourceId() : c.artisteSourceId();
      if (sourceId != null) {
        final int sep = sourceId.indexOf(':');
        if (sep > 0) {
          final String source = sourceId.substring(0, sep);
          final String identifiant = sourceId.substring(sep + 1);
          parSource.computeIfAbsent(source, _ -> new ArrayList<>()).add(identifiant);
        }
      }
    }

    final boolean estSalle = CLE_SALLE.equals(type);
    final String sql =
        estSalle
            ? "SELECT salle_id, identifiant_externe FROM salle_sources"
                + " WHERE source = :source AND identifiant_externe IN (:ids)"
            : "SELECT artiste_id, identifiant_externe FROM artiste_sources"
                + " WHERE source = :source AND identifiant_externe IN (:ids)";
    final String colUuid = estSalle ? "salle_id" : "artiste_id";

    final Map<String, UUID> cache = new HashMap<>();
    for (final Map.Entry<String, List<String>> entry : parSource.entrySet()) {
      final String source = entry.getKey();
      final List<String> ids = entry.getValue().stream().distinct().toList();
      final List<Map<String, Object>> lignes =
          jdbc.queryForList(sql, Map.of("source", source, "ids", ids));
      for (final Map<String, Object> ligne : lignes) {
        final String cle = source + ":" + ligne.get("identifiant_externe");
        cache.put(cle, (UUID) ligne.get(colUuid));
      }
    }
    return cache;
  }

  /**
   * @param concerts concerts après résolution des identités sources
   * @return liste avec les {@code artiste_norm} et {@code artiste_id} unifiés
   *     par URL, pour Eventim uniquement — voir {@link ResolveurArtisteParUrl}
   */
  /* package-private : appelé par PersisterConcerts (même package). */
  List<ValeurConcert> resoudreParUrl(final List<ValeurConcert> concerts) {
    return resolveurArtisteParUrl.resoudreParUrl(concerts);
  }

  /**
   * Aligne l'heure d'un événement TM sur celle d'un report entrant (même
   * url/date, heure différente) — évite qu'un nouvel événement soit créé
   * au lieu de mettre à jour l'ancien. Ne s'applique que si une seule
   * heure distincte existe déjà (ambiguïté sinon : nouvel événement créé).
   * @param concerts concerts après réconciliation, avant upsert
   */
  /* package-private : appelé par PersisterConcerts (même package). */
  void alignerHeureReportTM(final List<ValeurConcert> concerts) {
    alignementReportTM.aligner(concerts);
  }

  /**
   * Motif d'URL Ticketmaster signant un événement "package"/VIP — jamais
   * accessible à un achat de billetterie standard, ne doit jamais empêcher le
   * rapprochement avec la fiche normale du même événement réel.
   */
  private static boolean estPackage(final String url) {
    return url != null && url.matches("(?i).*\\Q/pack/\\E.*");
  }

  private static boolean heuresCompatibles(
      final LocalTime existante,
      final LocalTime entrante,
      final boolean packageExistant,
      final boolean packageEntrant) {
    if (packageExistant || packageEntrant) {
      return true;
    }
    if (existante == null || entrante == null) {
      return true;
    }
    final long diffMinutes = Math.abs(Duration.between(existante, entrante).toMinutes());
    return diffMinutes <= TOLERANCE_HEURE_MINUTES;
  }

  /**
   * Résout l'événement canonique d'un concert entrant, ou en crée un
   * nouveau (#1192) : correspondance exacte (artiste_norm/date/heure/salle_id),
   * sinon repli flou même salle/date ({@link #heuresCompatibles}, {@link
   * CorrespondanceArtiste#estDoublonArtiste}), sinon création.
   */
  private ResolutionEvenement resoudreOuCreerEvenement(final ValeurConcert concert) {
    final UUID exactId = chercherEvenementExact(concert);
    if (exactId != null) {
      return new ResolutionEvenement(exactId, false);
    }

    if (concert.salleId() != null) {
      final UUID candidatId = chercherEvenementParSalleEtDate(concert);
      if (candidatId != null) {
        return new ResolutionEvenement(candidatId, false);
      }
    }

    return new ResolutionEvenement(creerNouvelEvenement(concert), true);
  }

  private UUID chercherEvenementExact(final ValeurConcert concert) {
    final MapSqlParameterSource paramsExact =
        new MapSqlParameterSource()
            .addValue("artisteNorm", concert.artisteNorm())
            .addValue(CLE_DATE, concert.date(), Types.DATE)
            .addValue(CLE_HEURE, concert.heure(), Types.TIME)
            .addValue(
                "salleId",
                concert.salleId() != null ? concert.salleId().toString() : null,
                Types.VARCHAR);
    final List<UUID> exact =
        jdbc.query(
            "SELECT id FROM evenements WHERE artiste_norm = :artisteNorm AND date = :date"
                + " AND heure IS NOT DISTINCT FROM :heure"
                + " AND salle_id IS NOT DISTINCT FROM CAST(:salleId AS uuid)",
            paramsExact,
            (rs, _) -> rs.getObject("id", UUID.class));
    return exact.isEmpty() ? null : exact.get(0);
  }

  // return en dernière position de boucle nécessaire : arrête la recherche au
  // premier candidat compatible plutôt que de poursuivre l'itération sans effet
  // utile.
  @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop")
  private UUID chercherEvenementParSalleEtDate(final ValeurConcert concert) {
    final List<Map<String, Object>> candidats =
        jdbc.queryForList(
            """
            SELECT e.id AS id, e.artiste_id AS artiste_id, e.artiste_norm AS artiste_norm,
                   e.heure AS heure,
                   EXISTS(SELECT 1 FROM observations o WHERE o.evenement_id = e.id
                          AND o.url ~* '/pack/') AS a_observation_package
            FROM evenements e WHERE e.salle_id = CAST(:salleId AS uuid) AND e.date = :date
            """,
            Map.of("salleId", concert.salleId().toString(), CLE_DATE, concert.date()));
    final boolean entrantPackage = estPackage(concert.url());
    for (final Map<String, Object> c : candidats) {
      final UUID candArtisteId = (UUID) c.get("artiste_id");
      final String candArtisteNorm = (String) c.get("artiste_norm");
      final boolean artisteCorrespond =
          concert.artisteId() != null
              ? concert.artisteId().equals(candArtisteId)
              : candArtisteId == null
                  && CorrespondanceArtiste.estDoublonArtiste(
                      candArtisteNorm, concert.artisteNorm());
      if (!artisteCorrespond) {
        continue;
      }
      final Object heureBrute = c.get(CLE_HEURE);
      final LocalTime heureExistante =
          heureBrute != null ? ((Time) heureBrute).toLocalTime() : null;
      final boolean candidatPackage = Boolean.TRUE.equals(c.get("a_observation_package"));
      if (!heuresCompatibles(heureExistante, concert.heure(), candidatPackage, entrantPackage)) {
        continue;
      }
      return (UUID) c.get("id");
    }
    return null;
  }

  private UUID creerNouvelEvenement(final ValeurConcert concert) {
    return jdbc.queryForObject(
        """
        INSERT INTO evenements
            (artiste_norm, date, heure, salle_id, salle_norm, ville, dept, classification,
             genre, statut, presale_debut, presale_nom, artiste_id)
        VALUES (:artisteNorm, :date, :heure, CAST(:salleId AS uuid), :salleNorm, :ville, :dept,
                :classification, :genre, :statut, CAST(:presaleDebut AS timestamptz),
                :presaleNom, CAST(:artisteId AS uuid))
        RETURNING id
        """,
        paramsCreation(concert),
        UUID.class);
  }

  private static MapSqlParameterSource paramsCreation(final ValeurConcert concert) {
    return new MapSqlParameterSource()
        .addValue("artisteNorm", concert.artisteNorm())
        .addValue(CLE_DATE, concert.date(), Types.DATE)
        .addValue(CLE_HEURE, concert.heure(), Types.TIME)
        .addValue("salleId", concert.salleId() != null ? concert.salleId().toString() : null)
        .addValue(CLE_SALLE, concert.salle())
        .addValue("salleNorm", concert.salleNorm())
        .addValue("ville", concert.ville())
        .addValue("dept", concert.dept())
        .addValue("classification", concert.classification())
        .addValue("genre", concert.genre())
        .addValue("statut", concert.statut())
        .addValue("presaleDebut", concert.presaleDebut())
        .addValue("presaleNom", concert.presaleNom())
        .addValue("artisteId", concert.artisteId() != null ? concert.artisteId().toString() : null);
  }

  /**
   * Met à jour les champs dénormalisés d'un événement déjà résolu (jamais
   * {@code artiste_norm}/{@code heure}, identité stable) — priorité à la
   * valeur entrante, {@code statut} ne recule jamais depuis un état terminal.
   * {@code salle_norm} resynchronisé via {@link #meilleurSalleBrut}, appelé
   * après {@link #upsertObservation}.
   */
  private void mettreAJourChampsEvenement(final UUID evenementId, final ValeurConcert concert) {
    final String salleBrutReference = meilleurSalleBrut(evenementId);
    final String salleNormReference =
        salleBrutReference != null ? Normalisation.normaliserTexte(salleBrutReference) : null;
    jdbc.update(
        """
        UPDATE evenements SET
            salle_norm     = COALESCE(:salleNormReference, salle_norm),
            ville          = COALESCE(:ville, ville),
            dept           = COALESCE(:dept, dept),
            classification = COALESCE(:classification, classification),
            genre          = COALESCE(:genre, genre),
            statut         = CASE
                               WHEN statut IN ('cancelled', 'rescheduled', 'postponed') THEN statut
                               ELSE COALESCE(:statut, statut) END,
            presale_debut  = COALESCE(CAST(:presaleDebut AS timestamptz), presale_debut),
            presale_nom    = COALESCE(:presaleNom, presale_nom),
            salle_id       = COALESCE(CAST(:salleId AS uuid), salle_id),
            artiste_id     = COALESCE(CAST(:artisteId AS uuid), artiste_id),
            updated_at     = now()
        WHERE id = CAST(:id AS uuid)
        """,
        paramsCreation(concert)
            .addValue("id", evenementId.toString())
            .addValue("salleNormReference", salleNormReference));
  }

  private void upsertObservation(final UUID evenementId, final ValeurConcert concert) {
    jdbc.update(
        """
        INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut)
        VALUES (CAST(:evenementId AS uuid), CAST(:source AS source_concert), :url, :artiste, :salle)
        ON CONFLICT ON CONSTRAINT uq_observations DO UPDATE SET
            artiste_brut = CASE WHEN unaccent(EXCLUDED.artiste_brut) != EXCLUDED.artiste_brut
                                THEN EXCLUDED.artiste_brut ELSE observations.artiste_brut END,
            salle_brut   = CASE WHEN upper(EXCLUDED.salle_brut) != EXCLUDED.salle_brut
                                THEN EXCLUDED.salle_brut ELSE observations.salle_brut END,
            updated_at   = now()
        """,
        new MapSqlParameterSource()
            .addValue("evenementId", evenementId.toString())
            .addValue("source", concert.source())
            .addValue("url", concert.url())
            .addValue("artiste", concert.artiste())
            .addValue(CLE_SALLE, concert.salle()));
  }

  /**
   * Retrouve, parmi les observations déjà attachées à un événement, le
   * {@code salle_brut} de celle qui servirait de référence dans la vue
   * {@code concerts} — même priorité (package puis TM &gt; Eventim &gt;
   * Digitick) que la vue elle-même, pour garder {@code evenements.salle_norm}
   * cohérent avec ce que {@code concerts.salle} affichera.
   */
  private String meilleurSalleBrut(final UUID evenementId) {
    final List<String> resultat =
        jdbc.query(
            """
            SELECT salle_brut FROM observations WHERE evenement_id = CAST(:id AS uuid)
            ORDER BY (CASE WHEN url ~* '/pack/' THEN 10 ELSE 0 END
                      + CASE source::text
                          WHEN 'ticketmaster' THEN 1 WHEN 'eventim' THEN 2 WHEN 'digitick' THEN 3
                          ELSE 4 END)
            LIMIT 1
            """,
            Map.of("id", evenementId.toString()),
            (rs, _) -> rs.getString("salle_brut"));
    return resultat.isEmpty() ? null : resultat.get(0);
  }

  /**
   * Résout ou crée l'événement canonique de chaque concert du lot, upsert
   * l'observation de sa source puis met à jour ses champs dénormalisés (un
   * aller-retour par concert plutôt qu'un {@code INSERT} multi-lignes, volume
   * trivial en production).
   * @param valeurs concerts réconciliés et dédupliqués du lot
   * @return décompte des événements créés et mis à jour du lot
   */
  /* package-private : appelé par PersisterConcerts (même package). */
  PersisterConcerts.ResultatPersistance upsertLot(final List<ValeurConcert> valeurs) {
    if (valeurs.isEmpty()) {
      return new PersisterConcerts.ResultatPersistance(0, 0);
    }

    int inserts = 0;
    int updates = 0;
    for (final ValeurConcert concert : valeurs) {
      final ResolutionEvenement resolution = resoudreOuCreerEvenement(concert);
      upsertObservation(resolution.id(), concert);
      if (resolution.nouveau()) {
        inserts++;
      } else {
        mettreAJourChampsEvenement(resolution.id(), concert);
        updates++;
      }
    }
    return new PersisterConcerts.ResultatPersistance(inserts, updates);
  }
}

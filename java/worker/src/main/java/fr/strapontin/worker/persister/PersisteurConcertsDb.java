/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.Hommage;
import fr.strapontin.core.Normalisation;
import fr.strapontin.core.ProduitEventim;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger LOG = LoggerFactory.getLogger(PersisteurConcertsDb.class);

  private static final String CLE_SALLE = "salle";
  private static final String CLE_DATE = "date";
  private static final String CLE_HEURE = "heure";

  private final NamedParameterJdbcTemplate jdbc;
  private final ResolveurArtisteParUrl resolveurArtisteParUrl;
  private final AlignementReportTM alignementReportTM;
  private final EnregistreurPositionsLieux enregistreurPositions;
  private final RepriseIdentifiantsEventim repriseIdentifiantsEventim;
  private final RechercheEvenementExistant rechercheEvenement;
  private final RepublicationFicheDeplacee republicationFiche;

  private record ResolutionEvenement(UUID id, boolean nouveau, boolean parProduit) {}

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
    this.repriseIdentifiantsEventim = new RepriseIdentifiantsEventim(jdbc);
    this.rechercheEvenement = new RechercheEvenementExistant(jdbc);
    this.republicationFiche = new RepublicationFicheDeplacee(jdbc);
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
   * Rattache un lieu Eventim qualifié par code postal, encore inconnu, à la
   * salle de son identifiant historique du même département
   * ({@link RepriseIdentifiantsEventim}).
   */
  /* package-private : appelé par PersisterConcerts (même package). */
  void reprendreIdentifiantsEventimHistoriques(
      final List<ValeurConcert> concerts, final Map<String, UUID> cachesSalles) {
    repriseIdentifiantsEventim.reprendre(concerts, cachesSalles);
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
   * Résout l'événement canonique d'un concert entrant, ou en crée un
   * nouveau : correspondance exacte, produit Eventim déjà porté, sinon repli
   * flou même salle et même date ({@link RechercheEvenementExistant}), sinon
   * création.
   */
  private ResolutionEvenement resoudreOuCreerEvenement(final ValeurConcert concert) {
    final UUID exactId = rechercheEvenement.exact(concert);
    if (exactId != null) {
      return new ResolutionEvenement(exactId, false, false);
    }

    final UUID produitId = rechercheEvenement.parProduit(concert);
    if (produitId != null) {
      return new ResolutionEvenement(produitId, false, true);
    }

    if (concert.salleId() != null) {
      final UUID candidatId = rechercheEvenement.parSalleEtDate(concert);
      if (candidatId != null) {
        return new ResolutionEvenement(candidatId, false, false);
      }
    }

    return new ResolutionEvenement(creerNouvelEvenement(concert), true, false);
  }

  private UUID creerNouvelEvenement(final ValeurConcert concert) {
    return jdbc.queryForObject(
        """
        INSERT INTO evenements
            (artiste_norm, date, heure, salle_id, salle_norm, ville, dept, classification,
             genre, sous_genre, statut, presale_debut, presale_nom, artiste_id)
        VALUES (:artisteNorm, :date, :heure, CAST(:salleId AS uuid), :salleNorm,
                COALESCE(NULLIF(:ville, ''), (SELECT s.ville FROM salles s
                                               WHERE s.id = CAST(:salleId AS uuid))), :dept,
                :classification, :genre, :sousGenre, :statut, CAST(:presaleDebut AS timestamptz),
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
        .addValue("sousGenre", concert.sousGenre())
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
            ville          = COALESCE(NULLIF(:ville, ''), ville, (SELECT s.ville FROM salles s
                               WHERE s.id = COALESCE(CAST(:salleId AS uuid), salle_id))),
            dept           = COALESCE(:dept, dept),
            classification = COALESCE(:classification, classification),
            genre          = COALESCE(:genre, genre),
            sous_genre     = COALESCE(:sousGenre, sous_genre),
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

  // Un hommage rattaché à l'artiste imité avant que son titre soit reconnu : le
  // rattachement et les correspondances de ses abonnés tombent.
  private void detacherArtisteImite(final UUID evenementId, final ValeurConcert concert) {
    if (!Hommage.estHommage(concert.artiste())) {
      return;
    }
    final Map<String, Object> id = Map.of("id", evenementId.toString());
    jdbc.update(
        "UPDATE evenements SET artiste_id = NULL WHERE id = CAST(:id AS uuid)"
            + " AND artiste_id IS NOT NULL",
        id);
    jdbc.update("DELETE FROM user_matches WHERE concert_id = CAST(:id AS uuid)", id);
  }

  // L'adresse d'un produit suit son titre : l'ancienne, qui ne sera plus revue,
  // ne doit pas rester à côté de la nouvelle.
  private void retirerAnciennesAdressesProduit(
      final UUID evenementId, final ValeurConcert concert) {
    if (!"eventim".equals(concert.source())) {
      return;
    }
    jdbc.update(
        "DELETE FROM observations o WHERE o.evenement_id = CAST(:id AS uuid)"
            + " AND o.source = 'eventim' AND o.url IS DISTINCT FROM :url AND "
            + ProduitEventim.expressionSql("o")
            + " = :produit",
        Map.of(
            "id",
            evenementId.toString(),
            "url",
            concert.url(),
            "produit",
            ProduitEventim.identifiant(concert.url()).orElse("")));
  }

  // Un événement que la seule source du produit alimente en suit les changements
  // de titre et d'heure ; une autre source garde l'identité établie.
  // La clé (artiste, date, heure, salle) ne doit pas déjà exister, sinon l'identité
  // reste inchangée.
  private void alignerIdentiteSurProduit(final UUID evenementId, final ValeurConcert concert) {
    jdbc.update(
        """
        UPDATE evenements e SET
            heure       = COALESCE(CAST(:heure AS time), e.heure),
            artiste_norm = CASE WHEN e.artiste_id IS NULL OR e.artiste_id = CAST(:artisteId AS uuid)
                                THEN :artisteNorm ELSE e.artiste_norm END,
            updated_at  = now()
        WHERE e.id = CAST(:id AS uuid)
          AND NOT EXISTS (SELECT 1 FROM observations o
                          WHERE o.evenement_id = e.id
                            AND o.source <> CAST(:source AS source_concert))
          AND NOT EXISTS (
            SELECT 1 FROM evenements x
            WHERE x.id <> e.id AND x.date = e.date AND x.salle_id IS NOT DISTINCT FROM e.salle_id
              AND x.heure IS NOT DISTINCT FROM COALESCE(CAST(:heure AS time), e.heure)
              AND x.artiste_norm = CASE WHEN e.artiste_id IS NULL
                                             OR e.artiste_id = CAST(:artisteId AS uuid)
                                        THEN :artisteNorm ELSE e.artiste_norm END)
        """,
        new MapSqlParameterSource()
            .addValue("id", evenementId.toString())
            .addValue(CLE_HEURE, concert.heure(), Types.TIME)
            .addValue("source", concert.source())
            .addValue("artisteNorm", concert.artisteNorm())
            .addValue(
                "artisteId", concert.artisteId() != null ? concert.artisteId().toString() : null));
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
   * Ajoute les autres artistes de la soirée, jamais retirés : deux sources ne
   * publient pas toujours le même plateau, et un lot dédoublonné n'en garde
   * qu'une. L'artiste est relié s'il existe déjà au référentiel.
   */
  private void enregistrerPlateau(final UUID evenementId, final ValeurConcert concert) {
    for (final String nom : concert.brut().artistesInvites()) {
      final String norm = Normalisation.normaliserTexte(nom);
      if (norm.isEmpty() || norm.equals(concert.artisteNorm())) {
        continue;
      }
      jdbc.update(
          """
          INSERT INTO evenement_artistes (evenement_id, artiste_norm, artiste_nom, artiste_id)
          VALUES (CAST(:id AS uuid), :norm, :nom,
                  (SELECT a.id FROM artistes a WHERE a.nom_norm = :norm))
          ON CONFLICT (evenement_id, artiste_norm) DO UPDATE
            SET artiste_id = COALESCE(evenement_artistes.artiste_id, EXCLUDED.artiste_id)
          """,
          Map.of("id", evenementId.toString(), "norm", norm, "nom", nom));
    }
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
                          ELSE 4 END),
                     updated_at DESC, first_seen, id
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
    int republications = 0;
    for (final ValeurConcert concert : valeurs) {
      if (republicationFiche.estRepublication(concert)) {
        republications++;
        continue;
      }
      final ResolutionEvenement resolution = resoudreOuCreerEvenement(concert);
      upsertObservation(resolution.id(), concert);
      enregistrerPlateau(resolution.id(), concert);
      if (resolution.nouveau()) {
        inserts++;
      } else {
        mettreAJourChampsEvenement(resolution.id(), concert);
        if (resolution.parProduit()) {
          detacherArtisteImite(resolution.id(), concert);
          retirerAnciennesAdressesProduit(resolution.id(), concert);
          alignerIdentiteSurProduit(resolution.id(), concert);
        }
        updates++;
      }
    }
    if (republications > 0) {
      LOG.info("{} fiche(s) d'une ancienne salle republiée(s) ignorée(s)", republications);
    }
    return new PersisterConcerts.ResultatPersistance(inserts, updates);
  }
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.Normalisation;
import java.sql.Types;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service de réconciliation des noms de salle entre fetches successifs.
///
/// <p>Deux passes de réconciliation : intra-lot (variantes dans le même fetch) puis
/// contre la base (variantes entre deux fetches). Utilise {@link
// CorrespondanceSalle#estDoublonSalle}
/// (module {@code core}, partagé avec {@code sources} — voir `WikidataSalleSource`) pour déterminer
/// si deux noms désignent la même salle.
///
/// <p>Gère également l'upsert de nouvelles salles et le rattachement des salles par {@code
/// nom_norm} exact pour les concerts sans {@code salleSourceId}.
///
/// @since 2.5.7
/// @author Kwaite
@Service
public class ReconciliateurSalles {

  private static final Logger log = LoggerFactory.getLogger(ReconciliateurSalles.class);

  private final NamedParameterJdbcTemplate jdbc;

  /// @param jdbc template JDBC injecté par Spring
  public ReconciliateurSalles(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  static Set<String> tokensSignificatifs(String s) {
    return CorrespondanceSalle.tokensSignificatifs(s);
  }

  /// Calcule le coefficient de Jaccard sur les tokens des deux chaînes.
  ///
  /// @param a première chaîne normalisée
  /// @param b deuxième chaîne normalisée
  /// @return coefficient de Jaccard entre 0.0 et 1.0
  /// @see CorrespondanceSalle#jaccardTokens
  public static double jaccardTokens(String a, String b) {
    return CorrespondanceSalle.jaccardTokens(a, b);
  }

  /// Vérifie si deux noms de salle normalisés désignent la même salle.
  ///
  /// @param a premier nom normalisé
  /// @param b deuxième nom normalisé
  /// @return {@code true} si les salles sont considérées identiques
  /// @see CorrespondanceSalle#estDoublonSalle
  public static boolean estDoublonSalle(String a, String b) {
    return CorrespondanceSalle.estDoublonSalle(a, b);
  }

  /// Calcule un score de qualité pour choisir le meilleur nom de salle.
  ///
  /// @param salle nom brut de la salle
  /// @return score (diacritiques favorisés, casse mixte favorisée)
  static int scoreSalle(String salle) {
    int diacr = Normalizer.normalize(salle, Normalizer.Form.NFD).length() - salle.length();
    int casseMixte = !salle.equals(salle.toUpperCase()) ? 1 : 0;
    return diacr + casseMixte;
  }

  /// Résout les chaînes de remplacement transitives : si A→B et B→C, met à jour A→C.
  ///
  /// @param remplacements table de remplacement à résoudre sur place
  private static void resoudreChaines(Map<String, String> remplacements) {
    for (String cle : new ArrayList<>(remplacements.keySet())) {
      String valeur = cle;
      while (remplacements.containsKey(valeur) && !remplacements.get(valeur).equals(valeur)) {
        valeur = remplacements.get(valeur);
      }
      remplacements.put(cle, valeur);
    }
  }

  /// Réconcilie les variations mineures de nommage de salle dans un lot et contre la base.
  ///
  /// <p>Filtre temporel : seuls les concerts à partir de 7 jours avant aujourd'hui sont chargés
  /// depuis la base, évitant de ramener des milliers de concerts passés en mémoire.
  ///
  /// @param lot valeurs de concerts à réconcilier
  /// @return lot avec {@code salleNorm} canoniques
  List<ValeurConcert> reconcilierSalles(List<ValeurConcert> lot) {
    Map<String, String> remplacementsIntraLot = construireRemplacementsIntraLot(lot);
    List<ValeurConcert> lotApresIntraLot =
        remplacementsIntraLot.isEmpty()
            ? lot
            : lot.stream().map(c -> appliquerRemplacementSalle(c, remplacementsIntraLot)).toList();

    Set<String> artistesNorms =
        new HashSet<>(lotApresIntraLot.stream().map(ValeurConcert::artisteNorm).toList());
    if (artistesNorms.isEmpty()) return lotApresIntraLot;

    List<Map<String, Object>> existants =
        jdbc.queryForList(
            "SELECT artiste_norm, date::text AS date, to_char(heure, 'HH24:MI') AS heure, dept,"
                + " salle_norm"
                + " FROM concerts"
                + " WHERE artiste_norm IN (:artistesNorms)"
                + " AND date >= (CURRENT_DATE - INTERVAL '7 days')",
            Map.of("artistesNorms", artistesNorms));

    Map<String, List<String>> indexExistants = new HashMap<>();
    for (Map<String, Object> e : existants) {
      String cle =
          cleReconciliation(e.get("artiste_norm"), e.get("date"), e.get("heure"), e.get("dept"));
      indexExistants.computeIfAbsent(cle, _ -> new ArrayList<>()).add((String) e.get("salle_norm"));
    }

    return reconcilierContreBase(lotApresIntraLot, indexExistants);
  }

  /// Construit la clé de regroupement de la réconciliation de salle.
  ///
  /// <p>Inclut {@code heure} et {@code dept} en plus d'{@code artisteNorm}/{@code date} — deux
  /// productions différentes d'un même titre générique (ex. deux mises en scène indépendantes de
  /// "Les Misérables") programmées le même jour dans deux salles distinctes n'ont ni la même heure
  /// ni le même département ; sans ces deux champs, {@link CorrespondanceSalle#estDoublonSalle}
  /// pouvait rapprocher à tort leurs {@code salleNorm} respectifs.
  ///
  /// @param artisteNorm nom d'artiste normalisé
  /// @param date date du concert
  /// @param heure heure du concert au format {@code HH:mm} (voir {@link #formaterHeure}), peut être
  ///   {@code null}
  /// @param dept département du concert, peut être {@code null}
  /// @return clé de regroupement
  private static String cleReconciliation(
      Object artisteNorm, Object date, Object heure, Object dept) {
    return artisteNorm + "|" + date + "|" + heure + "|" + dept;
  }

  /// Formate une heure au même format que la requête SQL (`to_char(heure, 'HH24:MI')`) — la
  /// précision à la minute suffit à distinguer deux représentations d'un même concert, tout en
  /// restant insensible à un éventuel écart de précision entre Java et PostgreSQL.
  ///
  /// @param heure heure du concert, peut être {@code null}
  /// @return heure au format {@code HH:mm}, ou {@code null}
  private static String formaterHeure(java.time.LocalTime heure) {
    return heure == null
        ? null
        : heure.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
  }

  private Map<String, String> construireRemplacementsIntraLot(List<ValeurConcert> lot) {
    Map<String, String> remplacements = new HashMap<>();
    Map<String, List<String>> parGroupe = new LinkedHashMap<>();
    for (ValeurConcert concert : lot) {
      String cle =
          cleReconciliation(
              concert.artisteNorm(),
              concert.date(),
              formaterHeure(concert.heure()),
              concert.dept());
      parGroupe.computeIfAbsent(cle, _ -> new ArrayList<>());
      List<String> groupe = parGroupe.get(cle);
      if (!groupe.contains(concert.salleNorm())) groupe.add(concert.salleNorm());
    }
    for (List<String> sallesNorm : parGroupe.values()) {
      if (sallesNorm.size() > 1) ajouterDoublonsSalle(sallesNorm, remplacements);
    }
    resoudreChaines(remplacements);
    return remplacements;
  }

  private void ajouterDoublonsSalle(List<String> sallesNorm, Map<String, String> remplacements) {
    for (int i = 0; i < sallesNorm.size(); i++) {
      for (int j = i + 1; j < sallesNorm.size(); j++) {
        String a = sallesNorm.get(i);
        String b = sallesNorm.get(j);
        if (estDoublonSalle(a, b)) {
          String canonique = a.length() >= b.length() ? a : b;
          remplacements.put(a, canonique);
          remplacements.put(b, canonique);
        }
      }
    }
  }

  private ValeurConcert appliquerRemplacementSalle(
      ValeurConcert c, Map<String, String> remplacements) {
    String canonique = remplacements.get(c.salleNorm());
    if (canonique == null || canonique.equals(c.salleNorm())) return c;
    log.debug(
        "doublon salle intra-lot réconcilié entrant={} canonique={}", c.salleNorm(), canonique);
    return c.avecSalleNorm(canonique);
  }

  private List<ValeurConcert> reconcilierContreBase(
      List<ValeurConcert> lot, Map<String, List<String>> indexExistants) {
    return lot.stream().map(concert -> reconcilierUnConcert(concert, indexExistants)).toList();
  }

  /// Réconcilie un concert contre les salles existantes d'une même clé (artiste, date).
  ///
  /// <p>Vérifie d'abord une correspondance exacte sur l'ensemble de la liste avant de retomber sur
  /// une correspondance floue : sans cette priorité, l'absence de {@code ORDER BY} sur la requête
  /// source (voir {@link #reconcilierSalles}) peut placer un candidat flou avant un candidat exact,
  /// rattachant à tort le concert à la mauvaise salle alors qu'une correspondance exacte existait
  /// plus loin dans la même liste.
  ///
  /// @param concert concert à réconcilier
  /// @param indexExistants noms de salle existants indexés par {@link #cleReconciliation}
  /// @return concert réconcilié (salleNorm canonique si un candidat correspond)
  private ValeurConcert reconcilierUnConcert(
      ValeurConcert concert, Map<String, List<String>> indexExistants) {
    String cle =
        cleReconciliation(
            concert.artisteNorm(), concert.date(), formaterHeure(concert.heure()), concert.dept());
    List<String> sallesExistantes = indexExistants.get(cle);
    if (sallesExistantes == null || sallesExistantes.contains(concert.salleNorm())) {
      return concert;
    }
    for (String salleNormExistante : sallesExistantes) {
      if (estDoublonSalle(concert.salleNorm(), salleNormExistante)) {
        log.debug(
            "doublon salle inter-jobs réconcilié entrant={} existant={}",
            concert.salleNorm(),
            salleNormExistante);
        return concert.avecSalleNorm(salleNormExistante);
      }
    }
    return concert;
  }

  /// Absorbe les renommages de salle en mettant à jour {@code salles.nom}.
  ///
  /// @param concerts concerts avec {@code salleSourceId} résolus
  /// @param cachesSalles map sourceId → UUID
  void absorberRenommagesSalles(List<ValeurConcert> concerts, Map<String, UUID> cachesSalles) {
    Map<UUID, String> meilleurNom = new HashMap<>();
    Map<UUID, Integer> meilleurScore = new HashMap<>();
    for (ValeurConcert c : concerts) {
      if (c.salleSourceId() == null) continue;
      UUID salleId = cachesSalles.get(c.salleSourceId());
      if (salleId == null) continue;
      int score = scoreSalle(c.salle());
      if (!meilleurNom.containsKey(salleId) || score > meilleurScore.get(salleId)) {
        meilleurNom.put(salleId, c.salle());
        meilleurScore.put(salleId, score);
      }
    }
    for (var entree : meilleurNom.entrySet()) {
      String nouveauNom = entree.getValue();
      int modifies =
          jdbc.update(
              "UPDATE salles SET nom = :nom, nom_norm = :nomNorm"
                  + " WHERE id = CAST(:uuid AS uuid) AND nom <> :nom",
              Map.of(
                  "nom",
                  nouveauNom,
                  "nomNorm",
                  Normalisation.normaliserTexte(nouveauNom),
                  "uuid",
                  entree.getKey().toString()));
      if (modifies > 0) {
        log.info("renommage salle absorbé salleId={} nouveauNom={}", entree.getKey(), nouveauNom);
      }
    }
  }

  /// Résout ou crée une salle et insère son mapping source dans {@code salle_sources}.
  ///
  /// <p>Cherche d'abord par {@code unaccent(lower(nom))} (égalité exacte), puis par tokens
  /// significatifs via une requête unique {@code LIKE ANY} (corrige le pattern N+1), puis par clé
  /// forte (voir {@link #chercherSalleParCleForte}). Si aucune correspondance, crée une nouvelle
  /// salle.
  ///
  /// @param c concert portant le {@code salleSourceId} à résoudre
  /// @return UUID de la salle résolue ou créée, ou {@code null} si le {@code salleSourceId} est mal
  ///   formé
  UUID upsertSalleSource(ValeurConcert c) {
    int sep = c.salleSourceId().indexOf(':');
    if (sep <= 0) return null;
    String source = c.salleSourceId().substring(0, sep);
    String identifiant = c.salleSourceId().substring(sep + 1);

    List<UUID> existants =
        jdbc.query(
            "SELECT id FROM salles WHERE unaccent(lower(nom)) = unaccent(lower(:nom)) LIMIT 1",
            Map.of("nom", c.salle()),
            (rs, _) -> rs.getObject("id", UUID.class));
    UUID salleId = existants.isEmpty() ? null : existants.get(0);

    if (salleId == null) {
      salleId = chercherSalleParTokens(c.salle(), c.dept());
    }

    if (salleId == null) {
      salleId = chercherSalleParCleForte(c);
    }

    if (salleId == null) {
      String salleCle = Normalisation.normaliserTexte(c.salle());
      jdbc.update(
          "INSERT INTO salles (salle_cle, nom, nom_norm, dept)"
              + " VALUES (:salleCle, :nom, :nomNorm, :dept)"
              + " ON CONFLICT (salle_cle) DO NOTHING",
          Map.of("salleCle", salleCle, "nom", c.salle(), "nomNorm", salleCle, "dept", c.dept()));
      salleId =
          jdbc.queryForObject(
              "SELECT id FROM salles WHERE salle_cle = :salleCle",
              Map.of("salleCle", salleCle),
              UUID.class);
    }

    if (salleId == null) return null;

    // salles.dept n'était écrit qu'à la création puis jamais mis à jour — 281/284 salles
    // sans dept en prod avaient pourtant l'info via un concert déjà lié. COALESCE : n'écrase
    // jamais une valeur déjà connue, même pattern que wikidata_id/nom_affiche
    // (EnrichissementSallesService).
    if (c.dept() != null) {
      jdbc.update(
          "UPDATE salles SET dept = COALESCE(dept, :dept) WHERE id = CAST(:uuid AS uuid)",
          Map.of("dept", c.dept(), "uuid", salleId.toString()));
    }

    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:uuid AS uuid), :source, :identifiant)"
            + " ON CONFLICT DO NOTHING",
        Map.of("uuid", salleId.toString(), "source", source, "identifiant", identifiant));

    log.info(
        "salle upsert source salle={} source={} identifiant={} uuid={}",
        c.salle(),
        source,
        identifiant,
        salleId);
    return salleId;
  }

  /// Cherche une salle existante dont le {@code nom_norm} est un doublon du nom fourni.
  ///
  /// <p>Émet une seule requête SQL pour tous les tokens significatifs du nom (corrige le pattern
  /// N+1 antérieur où une requête était émise par token), complétée par une clause sur le {@code
  /// nom_norm} sans espaces : sans elle, une variante d'apostrophe/article entre deux sources ("l
  /// olympia" vs "lolympia") ne partage aucun token substantiel et le candidat n'est jamais
  /// récupéré, quand bien même {@link #estDoublonSalle} le détecterait. Le filtre {@code dept}
  /// élimine les salles homonymes de départements distincts, réduisant le risque de faux positif du
  /// critère pivot dans {@link #estDoublonSalle}. Repli sur le département d'un concert déjà
  /// rattaché à la salle candidate quand {@code salles.dept} est vide — cette colonne n'est écrite
  /// qu'à la création et jamais mise à jour ensuite, contrairement à {@code concerts.dept}.
  ///
  /// @param nom nom brut de la salle à créer
  /// @param dept département connu du concert (nullable — désactive le filtre si absent)
  /// @return UUID de la salle existante correspondante, ou {@code null} si aucune trouvée
  UUID chercherSalleParTokens(String nom, String dept) {
    String nomNorm = Normalisation.normaliserTexte(nom);
    Set<String> tokens = tokensSignificatifs(nomNorm);
    String nomNormSquash = nomNorm.replace(" ", "");
    if (tokens.isEmpty() && nomNormSquash.isEmpty()) return null;

    List<String> tokensList = new ArrayList<>(tokens);
    StringBuilder sbWhere = new StringBuilder("(");
    for (int i = 0; i < tokensList.size(); i++) {
      if (i > 0) sbWhere.append(" OR ");
      sbWhere.append("nom_norm LIKE :t").append(i);
    }
    if (!tokensList.isEmpty()) sbWhere.append(" OR ");
    sbWhere.append("replace(nom_norm, ' ', '') = :nomNormSquash)");
    String whereTokens = sbWhere.toString();

    MapSqlParameterSource params = new MapSqlParameterSource();
    for (int i = 0; i < tokensList.size(); i++) {
      params.addValue("t" + i, "%" + tokensList.get(i) + "%");
    }
    params.addValue("nomNormSquash", nomNormSquash);
    params.addValue("dept", dept, Types.VARCHAR);

    // salles.dept n'est écrit qu'à la création et n'est jamais mis à jour ensuite,
    // contrairement à concerts.dept (fiablement renseigné à chaque ingestion) — un simple
    // "dept IS NULL" désactivait le filtre pour la quasi-totalité des salles existantes. Repli
    // sur le département d'un concert déjà rattaché à la salle candidate quand salles.dept est
    // vide.
    List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT s.id AS id, s.nom_norm AS nom_norm FROM salles s"
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

    // Retient le candidat validé au Jaccard de tokens le plus élevé, pas le premier de la liste
    // (l'ordre SQL n'est pas garanti) : un nom générique de complexe multi-salles (ex. "La Seine
    // Musicale - Boulogne Billancourt") peut valider plusieurs salles physiquement distinctes du
    // même complexe via le seul critère pivot — sans ce score, le choix entre elles n'était pas
    // déterministe et pouvait rattacher au mauvais auditorium alors qu'une salle plus proche
    // textuellement existait dans le même lot de candidats.
    UUID meilleurUuid = null;
    String meilleurNomNorm = null;
    double meilleurScore = -1;
    for (var ligne : lignes) {
      UUID uuid = (UUID) ligne.get("id");
      String nomNormCandidat = (String) ligne.get("nom_norm");
      if (!estDoublonSalle(nomNorm, nomNormCandidat)) continue;
      double score = jaccardTokens(nomNorm, nomNormCandidat);
      if (score > meilleurScore) {
        meilleurScore = score;
        meilleurUuid = uuid;
        meilleurNomNorm = nomNormCandidat;
      }
    }
    if (meilleurUuid != null) {
      log.info(
          "salle doublon détecté par tokens : '{}' ≈ '{}' uuid={} score={}",
          nom,
          meilleurNomNorm,
          meilleurUuid,
          meilleurScore);
    }
    return meilleurUuid;
  }

  /// Recherche la salle d'un concert existant partageant exactement le même artiste, la même date,
  /// la même heure et le même département qu'un concert à résoudre — dernier repli avant création
  /// d'une salle, pour les cas où deux sources désignent le même évènement réel sous des noms de
  /// salle sans aucun rapport textuel (ex. Eventim "Ed&n" / TM "Espace Dollfus Noack" pour la même
  /// salle physique).
  ///
  /// <p>Deux sources indépendantes rapportant le même artiste à la minute et au département près ne
  /// peuvent structurellement pas désigner deux évènements réels distincts — restreint aux cas où
  /// l'heure et le département sont tous deux connus pour ne pas affaiblir la spécificité de la
  /// clé.
  ///
  /// @param c concert dont la salle reste à résoudre
  /// @return UUID de la salle du concert existant correspondant, ou {@code null} si aucun
  UUID chercherSalleParCleForte(ValeurConcert c) {
    if (c.heure() == null || c.dept() == null || c.artisteNorm() == null) return null;
    List<UUID> candidats =
        jdbc.query(
            "SELECT DISTINCT salle_id FROM concerts"
                + " WHERE artiste_norm = :artisteNorm AND date = :date AND heure = :heure"
                + " AND dept = :dept AND salle_id IS NOT NULL"
                + " ORDER BY salle_id"
                + " LIMIT 20",
            Map.of(
                "artisteNorm", c.artisteNorm(),
                "date", c.date(),
                "heure", c.heure(),
                "dept", c.dept()),
            (rs, _) -> rs.getObject("salle_id", UUID.class));
    if (candidats.isEmpty()) return null;

    UUID salleId = premierCandidatGeographiquementCoherent(candidats, c.dept());
    if (salleId == null) {
      log.warn(
          "clé forte (artiste+date+heure+dept) sans candidat géographiquement cohérent"
              + " artiste={} date={} candidats={}",
          c.artiste(),
          c.date(),
          candidats);
      return null;
    }

    log.info(
        "salle rattachée par clé forte (artiste+date+heure+dept) artiste={} date={} salle={}"
            + " uuid={}",
        c.artiste(),
        c.date(),
        c.salle(),
        salleId);
    return salleId;
  }

  /// Retient, parmi plusieurs salles candidates partageant la même clé forte, la première dont le
  /// département connu ne contredit pas celui du concert entrant.
  ///
  /// <p>Sans ce filtre, un concert déjà mal rattaché (département de sa salle incohérent avec le
  /// sien) pouvait servir de référence et reconfirmer son propre mauvais rattachement — la requête
  /// SQL source ne garantit qu'un ordre déterministe, pas la pertinence géographique du résultat.
  ///
  /// @param candidats identifiants de salle candidats, dans un ordre déterministe
  /// @param deptEntrant département du concert à résoudre
  /// @return premier candidat géographiquement cohérent, ou {@code null} si aucun ne l'est
  private UUID premierCandidatGeographiquementCoherent(List<UUID> candidats, String deptEntrant) {
    Map<UUID, String> depts = deptsConnus(new HashSet<>(candidats));
    for (UUID candidat : candidats) {
      String deptConnu = depts.get(candidat);
      if (deptConnu == null || deptConnu.equals(deptEntrant)) return candidat;
    }
    return null;
  }

  /// Rattache les concerts sans {@code salleId} à leur salle via correspondance exacte sur {@code
  /// salle_norm} / {@code nom_norm}.
  ///
  /// <p>(rattrapage) : une simple égalité de {@code nom_norm} ignorait le département, défaisant
  /// silencieusement le filtre dept de {@link #chercherSalleParTokens} pour deux salles homonymes
  /// au texte strictement identique (ex. "Casino - Barriere" Toulouse/Lille, {@code
  /// chercherSalleParTokens} rejette correctement le candidat en amont, mais ce repli par égalité
  /// exacte le retrouvait quand même en aval, sans filtre). Même repli sur le département d'un
  /// concert déjà rattaché que {@link #chercherSalleParTokens} quand {@code salles.dept} est vide.
  ///
  /// @param concerts concerts après déduplication
  /// @return liste enrichie avec les {@code salleId} résolus par nom_norm
  List<ValeurConcert> raccrocherSallesParNomNorm(List<ValeurConcert> concerts) {
    List<String> sallesNormsSansUuid =
        concerts.stream()
            .filter(c -> c.salleId() == null)
            .map(ValeurConcert::salleNorm)
            .distinct()
            .toList();
    if (sallesNormsSansUuid.isEmpty()) return concerts;

    Map<String, List<Map<String, Object>>> candidatsParNomNorm = new HashMap<>();
    jdbc.queryForList(
            "SELECT s.nom_norm AS nom_norm, s.id AS id,"
                + " COALESCE(s.dept, (SELECT c.dept FROM concerts c"
                + "                   WHERE c.salle_id = s.id AND c.dept IS NOT NULL LIMIT 1))"
                + "   AS dept_effectif"
                + " FROM salles s WHERE s.nom_norm IN (:normsNoms)",
            Map.of("normsNoms", sallesNormsSansUuid))
        .forEach(
            r ->
                candidatsParNomNorm
                    .computeIfAbsent((String) r.get("nom_norm"), _ -> new ArrayList<>())
                    .add(r));

    return concerts.stream()
        .map(
            c -> {
              if (c.salleId() != null) return c;
              List<Map<String, Object>> candidats = candidatsParNomNorm.get(c.salleNorm());
              if (candidats == null) return c;
              for (Map<String, Object> candidat : candidats) {
                String deptEffectif = (String) candidat.get("dept_effectif");
                if (c.dept() == null || deptEffectif == null || deptEffectif.equals(c.dept())) {
                  return c.avecSalleId((UUID) candidat.get("id"));
                }
              }
              return c;
            })
        .toList();
  }

  /// Charge en une seule requête le département connu de chaque salle donnée (colonne {@code dept},
  /// avec le même repli sur un concert déjà rattaché que {@link #chercherSalleParTokens}) — permet
  /// de valider la cohérence géographique d'un mapping {@code salle_sources} réutilisé sans une
  /// requête par concert.
  ///
  /// @param salleIds identifiants des salles à vérifier
  /// @return département connu par salle, absente si aucun département connu (ni {@code
  ///   salles.dept}, ni aucun concert déjà rattaché)
  Map<UUID, String> deptsConnus(Set<UUID> salleIds) {
    if (salleIds.isEmpty()) return Map.of();
    List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT s.id AS id, COALESCE(s.dept, (SELECT c.dept FROM concerts c"
                + " WHERE c.salle_id = s.id AND c.dept IS NOT NULL LIMIT 1)) AS dept"
                + " FROM salles s WHERE s.id IN (:ids)",
            Map.of("ids", salleIds));
    Map<UUID, String> resultat = new HashMap<>();
    for (var ligne : lignes) {
      String dept = (String) ligne.get("dept");
      if (dept != null) resultat.put((UUID) ligne.get("id"), dept);
    }
    return resultat;
  }

  /// Retente la résolution des concerts orphelins (`salle_id IS NULL`) déjà persistés.
  ///
  /// <p>Couvre le résidu structurel laissé par {@link #upsertSalleSource} : un concert sans {@code
  /// salleSourceId} (TM omet parfois {@code venue.id}) et sans salle suffisamment proche par nom au
  /// moment de son insertion reste orphelin indéfiniment, sans jamais être retenté — la salle
  /// correspondante peut pourtant apparaître plus tard via une autre source (un autre événement TM
  /// avec {@code venue.id} renseigné, Eventim, Digitick). Appelé une fois par nuit (voir {@code
  /// ReconciliationNocturneService}) plutôt qu'à chaque cycle de fetch : le volume concerné reste
  /// faible (quelques dizaines de concerts) et une résolution par nom n'a pas de raison de réussir
  /// plusieurs fois par jour si elle a échoué la veille.
  ///
  /// @return nombre de concerts orphelins résolus
  public int retenterSallesOrphelines() {
    List<Map<String, Object>> orphelins =
        jdbc.queryForList(
            "SELECT id, salle, dept FROM concerts"
                + " WHERE salle_id IS NULL AND date >= CURRENT_DATE",
            Map.of());
    if (orphelins.isEmpty()) return 0;

    // candidats chargés une seule fois (pas un chercherSalleParTokens/requête SQL par orphelin) —
    // le volume de salles reste compatible avec un score en mémoire, contrairement au N+1 SQL
    // que remplace cette méthode.
    List<CandidatSalleMatching> candidats = chargerCandidatsMatching();

    int resolus = 0;
    for (Map<String, Object> orphelin : orphelins) {
      String salle = (String) orphelin.get("salle");
      String dept = (String) orphelin.get("dept");
      UUID salleId = meilleurCandidatEnMemoire(salle, dept, candidats);
      if (salleId == null) continue;
      jdbc.update(
          "UPDATE concerts SET salle_id = CAST(:salleId AS uuid) WHERE id = :id",
          Map.of("salleId", salleId.toString(), "id", orphelin.get("id")));
      resolus++;
    }
    if (resolus > 0) {
      log.info(
          "réconciliation salles orphelines candidats={} résolus={}", orphelins.size(), resolus);
    }
    return resolus;
  }

  /// Salle candidate pour {@link #meilleurCandidatEnMemoire} — mêmes colonnes que {@link
  /// #chercherSalleParTokens}, {@code deptEffectif} incluant déjà le repli sur le département d'un
  /// concert déjà rattaché ({@code salles.dept} n'étant écrit qu'à la création).
  private record CandidatSalleMatching(UUID id, String nomNorm, String deptEffectif) {}

  /// Charge en une seule requête l'ensemble des salles utilisables comme candidats de rapprochement
  /// par nom, pour {@link #retenterSallesOrphelines}.
  ///
  /// @return candidats de toutes les salles connues
  private List<CandidatSalleMatching> chargerCandidatsMatching() {
    return jdbc.query(
        "SELECT s.id AS id, s.nom_norm AS nom_norm,"
            + " COALESCE(s.dept, (SELECT c.dept FROM concerts c"
            + "                   WHERE c.salle_id = s.id AND c.dept IS NOT NULL LIMIT 1))"
            + "   AS dept_effectif"
            + " FROM salles s",
        Map.of(),
        (rs, _) ->
            new CandidatSalleMatching(
                rs.getObject("id", UUID.class),
                rs.getString("nom_norm"),
                rs.getString("dept_effectif")));
  }

  /// Équivalent en mémoire de {@link #chercherSalleParTokens}, appliqué à des candidats déjà
  /// chargés — mêmes critères d'acceptation ({@link #estDoublonSalle}, filtre dept, meilleur score
  /// Jaccard), sans requête SQL par appel.
  ///
  /// @param nom nom brut de la salle à rapprocher
  /// @param dept département connu du concert (nullable — désactive le filtre si absent)
  /// @param candidats toutes les salles connues, préchargées
  /// @return UUID de la meilleure salle candidate, ou {@code null} si aucune ne correspond
  private UUID meilleurCandidatEnMemoire(
      String nom, String dept, List<CandidatSalleMatching> candidats) {
    String nomNorm = Normalisation.normaliserTexte(nom);
    UUID meilleurUuid = null;
    double meilleurScore = -1;
    for (CandidatSalleMatching candidat : candidats) {
      if (dept != null
          && candidat.deptEffectif() != null
          && !candidat.deptEffectif().equals(dept)) {
        continue;
      }
      if (!estDoublonSalle(nomNorm, candidat.nomNorm())) continue;
      double score = jaccardTokens(nomNorm, candidat.nomNorm());
      if (score > meilleurScore) {
        meilleurScore = score;
        meilleurUuid = candidat.id();
      }
    }
    return meilleurUuid;
  }
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Invariants objectifs des données ({@link ControleInvariantsDonnees}) sur base réelle.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class ControleInvariantsDonneesIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private SignalementMatchingService signalements;

  private ControleInvariantsDonnees controle;

  @BeforeEach
  void setUp() {
    controle = new ControleInvariantsDonnees(jdbc, signalements);
  }

  private UUID creerSalle() {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm, ville) VALUES ('l embarcadere',"
            + " 'L''Embarcadère', 'l embarcadere', 'Paris') RETURNING id",
        Map.of(),
        UUID.class);
  }

  private void evenement(final UUID salle, final String artiste, final String dept) {
    jdbc.update(
        "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id, dept)"
            + " VALUES (:artiste, CURRENT_DATE + 30, '20:00', 'l embarcadere',"
            + " CAST(:salle AS uuid), :dept)",
        Map.of("artiste", artiste, "salle", salle.toString(), "dept", dept));
  }

  private List<String> actifs() {
    return jdbc.queryForList(
        "SELECT type FROM signalements_matching WHERE resolu_le IS NULL ORDER BY type",
        Map.of(),
        String.class);
  }

  // L'Embarcadère : une seule source, concerts d'Aubervilliers (93) et de Boulogne-sur-Mer (62).
  @Test
  void verifier_salleSurDeuxDepartements_signaleEtRefermeUneFoisCorrigee() {
    final UUID salle = creerSalle();
    evenement(salle, "christophe willem", "62");
    evenement(salle, "autre artiste", "93");

    controle.verifier();
    assertEquals(List.of("salle_multi_departements"), actifs());

    jdbc.update("DELETE FROM evenements WHERE dept = '62'", Map.of());
    controle.verifier();
    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_salleContamineeSuivie_signaleLImpactUtilisateur() {
    final UUID salle = creerSalle();
    evenement(salle, "christophe willem", "62");
    evenement(salle, "autre artiste", "93");
    jdbc.update(
        "INSERT INTO users (id, username, email, must_change_credentials)"
            + " VALUES ('u1', 'u1', 'u1@test.fr', true)",
        Map.of());
    jdbc.update(
        "INSERT INTO user_salles (user_id, salle_id) VALUES ('u1', CAST(:id AS uuid))",
        Map.of("id", salle.toString()));

    controle.verifier();
    controle.verifier();

    assertEquals(List.of("salle_multi_departements", "salle_suivie_contaminee"), actifs());
  }

  private UUID evenementHeure(final UUID salle, final String artiste, final String heure) {
    return jdbc.queryForObject(
        "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id, dept)"
            + " VALUES (:artiste, CURRENT_DATE + 30, CAST(:heure AS time), 'x',"
            + " CAST(:salle AS uuid), '75') RETURNING id",
        Map.of("artiste", artiste, "heure", heure, "salle", salle.toString()),
        UUID.class);
  }

  private void observer(final UUID evenement, final String source) {
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), CAST(:source AS source_concert), :url, 'a', 's')",
        Map.of("id", evenement.toString(), "source", source, "url", "u-" + evenement + source));
  }

  @Test
  void verifier_memeArtisteMemeSoirDansDeuxSallesDeLaMemeCommune_signaleLaSeance() {
    final UUID alhambra = creerSalle();
    final UUID bataclan =
        jdbc.queryForObject(
            "INSERT INTO salles (salle_cle, nom, nom_norm, ville) VALUES ('bataclan', 'Bataclan',"
                + " 'bataclan', 'Paris') RETURNING id",
            Map.of(),
            UUID.class);
    observer(evenementHeure(alhambra, "dead poet society", "20:00"), "ticketmaster");
    observer(evenementHeure(bataclan, "dead poet society", "20:00"), "ticketmaster");

    controle.verifier();

    assertEquals(List.of("seance_dans_deux_salles"), actifs());
  }

  @Test
  void verifier_memeSalleDeuxHeuresProchesDeuxSources_signaleLaSeance() {
    final UUID salle = creerSalle();
    observer(evenementHeure(salle, "sting", "18:30"), "ticketmaster");
    observer(evenementHeure(salle, "sting", "16:00"), "eventim");

    controle.verifier();

    assertEquals(List.of("seance_dans_deux_salles"), actifs());
  }

  @Test
  void verifier_memeSalleDeuxSeancesDeLaMemeBilletterie_neSignaleRien() {
    final UUID salle = creerSalle();
    observer(evenementHeure(salle, "exposition", "10:00"), "eventim");
    observer(evenementHeure(salle, "exposition", "11:30"), "eventim");

    controle.verifier();

    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_matineeEtSoireeEloignees_neSignaleRien() {
    final UUID salle = creerSalle();
    observer(evenementHeure(salle, "spectacle", "14:00"), "ticketmaster");
    observer(evenementHeure(salle, "spectacle", "20:30"), "ticketmaster");

    controle.verifier();

    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_adresseDHommageSurFicheRattachee_signaleLHommage() {
    final UUID salle = creerSalle();
    evenement(salle, "ac dc", "75");
    final UUID artiste =
        jdbc.queryForObject(
            "INSERT INTO artistes (nom, nom_norm) VALUES ('AC/DC', 'ac dc') RETURNING id",
            Map.of(),
            UUID.class);
    jdbc.update(
        "UPDATE evenements SET artiste_id = CAST(:a AS uuid) WHERE artiste_norm = 'ac dc'",
        Map.of("a", artiste.toString()));
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut)"
            + " SELECT id, 'eventim', 'https://eventim.fr/ac-dc-live-tribute-1', 'a', 's'"
            + " FROM evenements",
        Map.of());

    controle.verifier();
    assertEquals(List.of("hommage_rattache_artiste"), actifs());

    jdbc.update("UPDATE evenements SET artiste_id = NULL", Map.of());
    controle.verifier();
    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_seanceAnnuleeParUneSourceEtVendueParLAutre_signaleLAnnulation() {
    final UUID salle = creerSalle();
    evenement(salle, "drag race", "34");
    final UUID evenement =
        jdbc.queryForObject(
            "SELECT id FROM evenements WHERE artiste_norm = 'drag race'", Map.of(), UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut, statut)"
            + " VALUES (CAST(:id AS uuid), 'eventim', 'u1', 'a', 's', 'cancelled'),"
            + " (CAST(:id AS uuid), 'ticketmaster', 'u2', 'a', 's', 'onsale')",
        Map.of("id", evenement.toString()));

    controle.verifier();
    assertEquals(List.of("annulation_une_billetterie"), actifs());

    jdbc.update("UPDATE observations SET statut = 'cancelled'", Map.of());
    controle.verifier();
    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_toutUnServicePushEnEchecDepuisDesJours_signaleLaPanne() {
    for (final String id : List.of("p1", "p2")) {
      jdbc.update(
          "INSERT INTO users (id, username, email, must_change_credentials)"
              + " VALUES (:id, :id, :id || '@test.fr', true)",
          Map.of("id", id));
      jdbc.update(
          "INSERT INTO push_abonnements (user_id, endpoint, p256dh, auth, echecs_consecutifs,"
              + " premier_echec_le) VALUES (:id, 'https://fcm.googleapis.com/' || :id, 'k', 'a',"
              + " 8, now() - interval '6 days')",
          Map.of("id", id));
    }

    controle.verifier();
    assertEquals(List.of("envois_push_en_panne"), actifs());

    jdbc.update(
        "UPDATE push_abonnements SET dernier_envoi_reussi_le = now() WHERE user_id = 'p1'",
        Map.of());
    controle.verifier();
    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_secretTotpEnClair_signaleLeCompteSeulement() {
    jdbc.update(
        "INSERT INTO users (id, username, email, must_change_credentials, two_factor_secret)"
            + " VALUES ('t1', 't1', 't1@test.fr', true, 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP'),"
            + " ('t2', 't2', 't2@test.fr', true, :chiffre)",
        Map.of("chiffre", "A".repeat(4) + "bcd".repeat(20)));

    controle.verifier();

    assertEquals(List.of("secret_totp_non_chiffre"), actifs());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM signalements_matching WHERE type = 'secret_totp_non_chiffre'"
                + " AND resolu_le IS NULL",
            Map.of(),
            Integer.class));
  }

  @Test
  void verifier_compteActiveSansIdentifiantDeConnexion_signaleUneIncoherence() {
    jdbc.update(
        "INSERT INTO users (id, username, email, must_change_credentials)"
            + " VALUES ('u2', 'u2', 'u2@test.fr', false)",
        Map.of());

    controle.verifier();

    assertEquals(List.of("comptes_incoherents"), actifs());
  }

  // Élysée Montmartre réparti hors du Galabru, puis recréé sur le Galabru par son lieu
  // Ticketmaster resté en place.
  @Test
  void verifier_concertRecreeSurUneSalleDeclareeDistincte_signaleLeConcertRecent() {
    final UUID galabru = creerSalle();
    final UUID elysee =
        jdbc.queryForObject(
            "INSERT INTO salles (salle_cle, nom, nom_norm, ville) VALUES ('elysee',"
                + " 'Élysée Montmartre', 'elysee montmartre', 'Paris') RETURNING id",
            Map.of(),
            UUID.class);
    jdbc.update(
        "INSERT INTO salles_paires_distinctes (salle_a, salle_b) SELECT"
            + " LEAST(CAST(:a AS uuid), CAST(:b AS uuid)), GREATEST(CAST(:a AS uuid), CAST(:b AS"
            + " uuid))",
        Map.of("a", galabru.toString(), "b", elysee.toString()));
    evenement(elysee, "gaz coombes", "75");
    evenement(galabru, "gaz coombes", "75");

    controle.verifier();

    assertEquals(List.of("concert_double_salles_distinctes"), actifs());
  }

  private void evenementAvecSource(
      final UUID salle, final String artiste, final String heure, final String source) {
    final UUID id =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id, dept)"
                + " VALUES (:artiste, CURRENT_DATE + 30, CAST(:heure AS time), 'madeleine',"
                + " CAST(:salle AS uuid), '75') RETURNING id",
            Map.of("artiste", artiste, "heure", heure, "salle", salle.toString()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut) VALUES"
            + " (CAST(:id AS uuid), CAST(:source AS source_concert), :url, :artiste, 'Madeleine')",
        Map.of(
            "id",
            id.toString(),
            "source",
            source,
            "url",
            "https://" + source + "/" + id,
            "artiste",
            artiste));
  }

  // L'église de la Madeleine : deux programmes Eventim pour un seul concert du soir.
  @Test
  void verifier_deuxFichesEventimAuMemeCreneau_signaleSansFusionner() {
    final UUID salle = creerSalle();
    evenementAvecSource(salle, "les 4 saisons de vivaldi", "20:00", "eventim");
    evenementAvecSource(salle, "vivaldi ave maria concerto adagios", "20:00", "eventim");

    controle.verifier();

    assertEquals(List.of("creneau_double_meme_source"), actifs());
    assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM evenements", Map.of(), Long.class));
  }

  // Deux fiches eventim au même créneau, résolues à la main puis revues le lendemain.
  @Test
  void verifier_creneauDoubleResoluParUnAdmin_neEstPlusSignaleTantQueLaPaireNeChangePas() {
    final UUID salle = creerSalle();
    evenementAvecSource(salle, "le presbytere", "20:30", "eventim");
    evenementAvecSource(salle, "marilyn live", "20:30", "eventim");

    controle.verifier();
    assertEquals(List.of("creneau_double_meme_source"), actifs());

    jdbc.update(
        "UPDATE signalements_matching SET resolu_le = now(), resolu_manuellement = true", Map.of());
    controle.verifier();

    assertEquals(List.of(), actifs());
  }

  // Un contrôle qui se referme de lui-même ne vaut pas décision : l'anomalie qui revient
  // est signalée de nouveau.
  @Test
  void verifier_creneauDoubleRefermeAutomatiquementPuisRevenu_estSignaleDeNouveau() {
    final UUID salle = creerSalle();
    evenementAvecSource(salle, "le presbytere", "20:30", "eventim");
    evenementAvecSource(salle, "marilyn live", "20:30", "eventim");
    controle.verifier();
    jdbc.update("UPDATE signalements_matching SET resolu_le = now()", Map.of());

    controle.verifier();

    assertEquals(List.of("creneau_double_meme_source"), actifs());
  }

  private void produitEventim(
      final UUID salle, final String artiste, final String heure, final String produit) {
    final UUID id =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id, dept)"
                + " VALUES (:artiste, CURRENT_DATE + 30, CAST(:heure AS time), 'madeleine',"
                + " CAST(:salle AS uuid), '75') RETURNING id",
            Map.of("artiste", artiste, "heure", heure, "salle", salle.toString()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut) VALUES"
            + " (CAST(:id AS uuid), 'eventim', :url, :artiste, 'Madeleine')",
        Map.of(
            "id",
            id.toString(),
            "url",
            "https://www.eventim.fr/event/" + artiste.replace(' ', '-') + "-" + produit,
            "artiste",
            artiste));
  }

  @Test
  void verifier_memeProduitEventimSurTroisFichesDHeuresVoisines_signaleSansFusionner() {
    final UUID salle = creerSalle();
    produitEventim(salle, "boleros", "20:00", "22000001");
    produitEventim(salle, "boleros soiree", "20:15", "22000001");
    produitEventim(salle, "boleros gala", "20:30", "22000001");

    controle.verifier();

    assertEquals(java.util.Set.of("produit_eventim_double"), java.util.Set.copyOf(actifs()));
    assertEquals(3L, jdbc.queryForObject("SELECT count(*) FROM evenements", Map.of(), Long.class));
  }

  // Flashdance à Pleyel : deux séances du même jour sous une seule adresse de produit.
  @Test
  void verifier_memeProduitEventimPourUneMatineeEtUneSoiree_neSignaleRien() {
    final UUID salle = creerSalle();
    produitEventim(salle, "flashdance", "13:30", "21040341");
    produitEventim(salle, "flashdance le musical", "17:30", "21040341");

    controle.verifier();

    assertEquals(List.of(), actifs());
  }

  private UUID creerSalleNommee(final String cle) {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm, ville) VALUES (:cle, :cle, :cle, 'Lyon')"
            + " RETURNING id",
        Map.of("cle", cle),
        UUID.class);
  }

  private void ficheObservee(final UUID salle, final String dept, final int joursSansVue) {
    final UUID id =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id, dept)"
                + " VALUES (:artiste, CURRENT_DATE + 30, '20:00', 'madeleine',"
                + " CAST(:salle AS uuid), :dept) RETURNING id",
            Map.of(
                "artiste", "fiche " + UUID.randomUUID(), "salle", salle.toString(), "dept", dept),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut,"
            + " updated_at) VALUES (CAST(:id AS uuid), 'ticketmaster', :url, 'a', 's',"
            + " now() - make_interval(days => :jours))",
        Map.of("id", id.toString(), "url", "https://tm/" + id, "jours", joursSansVue));
  }

  @Test
  void verifier_ficheDeDepartementSuiviSansObservationRecente_signaleLesFichesFigees() {
    final UUID salle = creerSalle();
    jdbc.update(
        "INSERT INTO users (id, username, email, must_change_credentials)"
            + " VALUES ('u3', 'u3', 'u3@test.fr', true)",
        Map.of());
    jdbc.update(
        "INSERT INTO user_config (user_id, departements) VALUES ('u3', ARRAY['75']::text[])",
        Map.of());
    ficheObservee(salle, "75", 20);

    controle.verifier();

    assertEquals(List.of("fiches_figees"), actifs());
  }

  @Test
  void verifier_ficheFigeeLaPlusTardiveDeSaSalle_estDistingueeCommeHorsHorizon() {
    final UUID salle = creerSalle();
    jdbc.update(
        "INSERT INTO users (id, username, email, must_change_credentials)"
            + " VALUES ('u3', 'u3', 'u3@test.fr', true)",
        Map.of());
    jdbc.update(
        "INSERT INTO user_config (user_id, departements) VALUES ('u3', ARRAY['75']::text[])",
        Map.of());
    ficheObservee(salle, "75", 20);
    jdbc.update("UPDATE evenements SET date = CURRENT_DATE + 400", Map.of());
    final UUID recente =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, salle_id, dept)"
                + " VALUES ('recente', CURRENT_DATE + 100, '20:00', 'x', CAST(:s AS uuid), '75')"
                + " RETURNING id",
            Map.of("s", salle.toString()),
            UUID.class);
    observer(recente, "eventim");

    controle.verifier();

    assertEquals(
        "1",
        jdbc.queryForObject(
            "SELECT details->>'horsHorizon' FROM signalements_matching"
                + " WHERE type = 'fiches_figees' AND resolu_le IS NULL",
            Map.of(),
            String.class));
  }

  @Test
  void verifier_ficheFigeeHorsPerimetreOuRecente_neSignaleRien() {
    final UUID salle = creerSalle();
    jdbc.update(
        "INSERT INTO users (id, username, email, must_change_credentials)"
            + " VALUES ('u3', 'u3', 'u3@test.fr', true)",
        Map.of());
    jdbc.update(
        "INSERT INTO user_config (user_id, departements) VALUES ('u3', ARRAY['75']::text[])",
        Map.of());
    ficheObservee(salle, "75", 3);
    ficheObservee(creerSalleNommee("lyon"), "69", 40);

    controle.verifier();

    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_salleSansVilleAvecConcertsAVenir_signaleEtRefermeUneFoisLaVilleRenseignee() {
    final UUID salle =
        jdbc.queryForObject(
            "INSERT INTO salles (salle_cle, nom, nom_norm, dept) VALUES ('casino du gosier',"
                + " 'Casino du Gosier', 'casino du gosier', '971') RETURNING id",
            Map.of(),
            UUID.class);
    evenement(salle, "artiste gosier", "971");

    controle.verifier();
    assertEquals(List.of("salle_sans_ville"), actifs());

    jdbc.update("UPDATE salles SET ville = 'Le Gosier'", Map.of());
    controle.verifier();
    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_deuxFichesDeSourcesDifferentesAuMemeCreneau_neSignalePasCeType() {
    final UUID salle = creerSalle();
    evenementAvecSource(salle, "spectacle a", "20:00", "eventim");
    evenementAvecSource(salle, "spectacle b", "20:00", "ticketmaster");

    controle.verifier();

    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_troisFichesAuMemeCreneau_neSignalePas() {
    final UUID salle = creerSalle();
    evenementAvecSource(salle, "a", "20:00", "eventim");
    evenementAvecSource(salle, "b", "20:00", "eventim");
    evenementAvecSource(salle, "c", "20:00", "eventim");

    controle.verifier();

    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_donneesSaines_aucunSignalement() {
    evenement(creerSalle(), "artiste", "75");

    assertEquals(0, controle.verifier());
    assertEquals(List.of(), actifs());
  }

  private void compteAvecMatch(
      final boolean emailActif, final String ageMatch, final String ageConcert) {
    jdbc.update(
        "INSERT INTO users (id, email, role, display_username, must_change_credentials)"
            + " VALUES ('u1', 'u1@test.fr', CAST('user' AS role_utilisateur), 'u1', true)",
        Map.of());
    jdbc.update(
        "INSERT INTO user_config (user_id, frequence_notifications, notif_artistes,"
            + " notif_artistes_push) VALUES ('u1', 'quotidien', :email, NOT :email)",
        Map.of("email", emailActif));
    final UUID artiste =
        jdbc.queryForObject(
            "INSERT INTO artistes (nom, nom_norm) VALUES ('A', 'a') RETURNING id",
            Map.of(),
            UUID.class);
    jdbc.update(
        "INSERT INTO user_artistes (user_id, artiste_affiche, artiste_id)"
            + " VALUES ('u1', 'A', CAST(:artiste AS uuid))",
        Map.of("artiste", artiste.toString()));
    final UUID concert =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, salle_norm, artiste_id, first_seen)"
                + " VALUES ('a', CURRENT_DATE + 30, 'salle', CAST(:artiste AS uuid),"
                + " now() - CAST(:age AS interval)) RETURNING id",
            Map.of("artiste", artiste.toString(), "age", ageConcert),
            UUID.class);
    jdbc.update(
        "INSERT INTO user_matches (user_id, concert_id, cree_le)"
            + " VALUES ('u1', CAST(:concert AS uuid), now() - CAST(:age AS interval))",
        Map.of("concert", concert.toString(), "age", ageMatch));
  }

  @Test
  void verifier_envoiEnAttenteAvecEmailActif_signaleAvecLaDateDuRapprochement() {
    compteAvecMatch(true, "3 days", "60 days");

    controle.verifier();

    assertEquals(List.of("notifications_non_envoyees"), actifs());
    assertEquals(
        LocalDate.now().minusDays(3).toString(),
        jdbc.queryForObject(
            "SELECT details->>'plusAncien' FROM signalements_matching"
                + " WHERE type = 'notifications_non_envoyees'",
            Map.of(),
            String.class));
  }

  // Concert connu de longue date, artiste suivi aujourd'hui : le délai n'est pas écoulé.
  @Test
  void verifier_artisteSuiviRecemment_aucunSignalement() {
    compteAvecMatch(true, "1 hour", "60 days");

    controle.verifier();

    assertEquals(List.of(), actifs());
  }

  // Compte sans canal joignable : seul l'utilisateur peut agir, déjà averti.
  @Test
  void verifier_pushSansAppareil_aucunSignalement() {
    compteAvecMatch(false, "3 days", "60 days");

    controle.verifier();

    assertEquals(List.of(), actifs());
  }

  private void artiste(final String nom, final String nomNorm) {
    jdbc.update(
        "INSERT INTO artistes (nom, nom_norm) VALUES (:nom, :norm)",
        Map.of("nom", nom, "norm", nomNorm));
  }

  @Test
  void verifier_deuxArtistesAuMemeNomSansEspaces_signaleEtRefermeUneFoisFusionnes() {
    jdbc.update("DELETE FROM artistes", Map.of());
    artiste("Krash Karma", "krash karma");
    artiste("Krashkarma", "krashkarma");

    controle.verifier();
    assertEquals(List.of("artistes_meme_nom_sans_espaces"), actifs());

    jdbc.update("DELETE FROM artistes WHERE nom_norm = 'krashkarma'", Map.of());
    controle.verifier();
    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_artistesDeNomsDistincts_neSignaleRien() {
    jdbc.update("DELETE FROM artistes", Map.of());
    artiste("Radiohead", "radiohead");
    artiste("Krashkarma", "krashkarma");

    controle.verifier();

    assertEquals(List.of(), actifs());
  }

  @Test
  void verifier_paireDejaExamineeParUnAdmin_neEstPlusSignalee() {
    jdbc.update("DELETE FROM artistes", Map.of());
    artiste("Krash Karma", "krash karma");
    artiste("Krashkarma", "krashkarma");
    controle.verifier();
    jdbc.update(
        "UPDATE signalements_matching SET resolu_le = now()"
            + " WHERE type = 'artistes_meme_nom_sans_espaces'",
        Map.of());

    controle.verifier();

    assertEquals(List.of(), actifs());
  }
}

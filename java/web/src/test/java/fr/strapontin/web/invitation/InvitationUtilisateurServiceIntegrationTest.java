/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.invitation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.AbstractWebIntegrationTest;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invitations envoyées par les utilisateurs, sur PostgreSQL réel : aucun compte avant
 * l'activation, plafond en attente, cadence, anti-énumération, parrain conservé.
 *
 * @since 3.1.4
 * @author Kwaite
 */
class InvitationUtilisateurServiceIntegrationTest extends AbstractWebIntegrationTest {

  private static final String BASE = "https://strapontin.test";

  private static NamedParameterJdbcTemplate jdbc;
  private static DataSourceTransactionManager transactions;

  private EmailService emailService;
  private LogAdminService logAdminService;
  private ConfigService configService;
  private InvitationUtilisateurService service;
  private String parrain;

  @BeforeAll
  static void migrerBase() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new NamedParameterJdbcTemplate(dataSource);
    transactions = new DataSourceTransactionManager(dataSource);
  }

  @BeforeEach
  void preparer() {
    jdbc.update("DELETE FROM envois_invitations", Map.of());
    jdbc.update("DELETE FROM invitations_utilisateurs", Map.of());
    jdbc.update("DELETE FROM users", Map.of());
    emailService = mock(EmailService.class);
    logAdminService = mock(LogAdminService.class);
    configService = mock(ConfigService.class);
    when(configService.lireConfig(anyString())).thenReturn(Optional.empty());
    service = construire(configService);
    parrain = creerUtilisateur("parrain@exemple.fr", "parrain");
  }

  private InvitationUtilisateurService construire(final ConfigService config) {
    return new InvitationUtilisateurService(
        new InvitationUtilisateurRepository(jdbc),
        new ReglagesInvitations(config),
        emailService,
        logAdminService,
        jdbc,
        new BCryptPasswordEncoder(4));
  }

  private String creerUtilisateur(final String email, final String identifiant) {
    final String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO users (id, email, email_verified, role, username, display_username,"
            + " must_change_credentials, created_at, updated_at) VALUES (:id, :email, true,"
            + " 'user', :ident, :ident, false, now(), now())",
        Map.of("id", id, "email", email, "ident", identifiant));
    return id;
  }

  private long compter(final String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Map.of(), Long.class);
  }

  private String dernierLien() {
    final ArgumentCaptor<String> lien = ArgumentCaptor.forClass(String.class);
    verify(emailService, atLeastOnce()).envoyerInvitation(any(), lien.capture());
    final List<String> liens = lien.getAllValues();
    return liens.get(liens.size() - 1);
  }

  @Test
  void inviter_adresseInconnue_creeUneInvitationSansCompteEtEnvoieLEmail() {
    service.inviter(parrain, "Ami@Exemple.fr", BASE);

    assertEquals(1L, compter("invitations_utilisateurs"));
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM users WHERE email = 'ami@exemple.fr'", Map.of(), Long.class));
    verify(emailService).envoyerInvitation(any(), any());
  }

  @Test
  void inviter_troisiemeInvitationEnAttente_estRefusee() {
    service.inviter(parrain, "a@exemple.fr", BASE);
    service.inviter(parrain, "b@exemple.fr", BASE);

    final ErreurMetier erreur =
        assertThrows(ErreurMetier.class, () -> service.inviter(parrain, "c@exemple.fr", BASE));

    assertEquals(409, erreur.getStatus());
    assertEquals(2L, compter("invitations_utilisateurs"));
  }

  @Test
  void inviter_adresseDejaInscrite_repondComme_unEnvoiReussiSansRienEnvoyer() {
    creerUtilisateur("inscrit@exemple.fr", "inscrit");

    service.inviter(parrain, "inscrit@exemple.fr", BASE);

    assertEquals(0L, compter("invitations_utilisateurs"));
    verify(emailService, never()).envoyerInvitation(any(), any());
    assertEquals(2, service.etat(parrain).restantes());
  }

  @Test
  void inviter_adresseDejaInvitee_repondCommeUnEnvoiReussiSansDeuxiemeEmail() {
    service.inviter(parrain, "ami@exemple.fr", BASE);
    final String autre = creerUtilisateur("autre@exemple.fr", "autre");

    service.inviter(autre, "ami@exemple.fr", BASE);

    assertEquals(1L, compter("invitations_utilisateurs"));
    verify(emailService, times(1)).envoyerInvitation(any(), any());
  }

  @Test
  void annuler_libereLeCreneauMaisLesEnvoisRestentComptesDansLaCadence() {
    for (int i = 1; i <= 5; i++) {
      service.inviter(parrain, "ami" + i + "@exemple.fr", BASE);
      final UUID id = service.etat(parrain).invitations().get(0).id();
      service.annuler(parrain, id);
    }

    final ErreurMetier erreur =
        assertThrows(ErreurMetier.class, () -> service.inviter(parrain, "ami6@exemple.fr", BASE));

    assertEquals(429, erreur.getStatus());
    verify(logAdminService).logAdmin(any(), any(), any(), any());
  }

  @Test
  void inviter_plafondQuotidienGlobalAtteint_refuseLesAutresUtilisateurs() {
    when(configService.lireConfig(ReglagesInvitations.CLE_ENVOIS_PAR_JOUR))
        .thenReturn(Optional.of("1"));
    final String autre = creerUtilisateur("autre@exemple.fr", "autre");
    service.inviter(parrain, "ami@exemple.fr", BASE);

    final ErreurMetier erreur =
        assertThrows(ErreurMetier.class, () -> service.inviter(autre, "ami2@exemple.fr", BASE));

    assertEquals(429, erreur.getStatus());
  }

  @Test
  void inviter_plafondPropreAZero_coupeLInvitation() {
    jdbc.update("UPDATE users SET plafond_invitations = 0 WHERE id = :id", Map.of("id", parrain));

    final ErreurMetier erreur =
        assertThrows(ErreurMetier.class, () -> service.inviter(parrain, "a@exemple.fr", BASE));

    assertEquals(409, erreur.getStatus());
    assertEquals(0, service.etat(parrain).plafond());
  }

  @Test
  void inviter_plafondParDefautModifie_sApplique() {
    when(configService.lireConfig(ReglagesInvitations.CLE_PLAFOND_DEFAUT))
        .thenReturn(Optional.of("3"));
    for (int i = 1; i <= 3; i++) {
      service.inviter(parrain, "ami" + i + "@exemple.fr", BASE);
    }

    assertEquals(3, service.etat(parrain).plafond());
    assertEquals(0, service.etat(parrain).restantes());
  }

  @Test
  void activer_lienValide_creeLeCompteUtilisateurAvecSonParrain() {
    service.inviter(parrain, "ami@exemple.fr", BASE);
    final String token = dernierLien().substring((BASE + "/reset-password?token=").length());

    assertTrue(service.estLienInvitation(token));
    final Optional<String> identifiant = service.activer(token, "nouvel-ami", "MotDePasse123!");

    assertEquals(Optional.of("nouvel-ami"), identifiant);
    final Map<String, Object> compte =
        jdbc.queryForMap(
            "SELECT role::text AS role, parrain_id, email_verified FROM users"
                + " WHERE username = 'nouvel-ami'",
            Map.of());
    assertEquals("user", compte.get("role"));
    assertEquals(parrain, compte.get("parrain_id"));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM accounts a JOIN users u ON u.id = a.user_id"
                + " WHERE u.username = 'nouvel-ami'",
            Map.of(),
            Long.class));
    assertFalse(service.estLienInvitation(token));
    assertEquals(Optional.empty(), service.activer(token, "autre", "MotDePasse123!"));
    assertEquals(2, service.etat(parrain).restantes());
  }

  @Test
  void activer_parrainSupprimeEnsuite_laisseLeCompteInviteEnPlace() {
    service.inviter(parrain, "ami@exemple.fr", BASE);
    final String token = dernierLien().substring((BASE + "/reset-password?token=").length());
    service.activer(token, "nouvel-ami", "MotDePasse123!");

    jdbc.update("DELETE FROM users WHERE id = :id", Map.of("id", parrain));

    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM users WHERE username = 'nouvel-ami'", Map.of(), Long.class));
  }

  @Test
  void activer_identifiantDejaPris_estRefuseEtLeLienResteValable() {
    creerUtilisateur("autre@exemple.fr", "pris");
    service.inviter(parrain, "ami@exemple.fr", BASE);
    final String token = dernierLien().substring((BASE + "/reset-password?token=").length());

    assertThrows(ErreurMetier.class, () -> service.activer(token, "pris", "MotDePasse123!"));

    assertTrue(service.estLienInvitation(token));
  }

  @Test
  void renvoyer_invitationEnAttente_changeLeLienEtCompteLEnvoi() {
    service.inviter(parrain, "ami@exemple.fr", BASE);
    final String ancien = dernierLien();
    final UUID id = service.etat(parrain).invitations().get(0).id();

    service.renvoyer(parrain, id, BASE);

    assertFalse(ancien.equals(dernierLien()));
    assertEquals(2L, compter("envois_invitations"));
  }

  @Test
  void annuler_invitationDUnAutreUtilisateur_estIntrouvable() {
    service.inviter(parrain, "ami@exemple.fr", BASE);
    final UUID id = service.etat(parrain).invitations().get(0).id();
    final String autre = creerUtilisateur("autre@exemple.fr", "autre");

    final ErreurMetier erreur = assertThrows(ErreurMetier.class, () -> service.annuler(autre, id));

    assertEquals(404, erreur.getStatus());
  }

  // Deux demandes simultanées d'un utilisateur au plafond de 1 : une seule passe.
  @Test
  void inviter_deuxDemandesSimultanees_neDepassentPasLePlafond() throws Exception {
    jdbc.update("UPDATE users SET plafond_invitations = 1 WHERE id = :id", Map.of("id", parrain));
    final TransactionTemplate modele = new TransactionTemplate(transactions);
    final ExecutorService executeur = Executors.newFixedThreadPool(2);
    try {
      final List<Callable<Boolean>> demandes = new ArrayList<>();
      for (int i = 1; i <= 2; i++) {
        final String adresse = "ami" + i + "@exemple.fr";
        demandes.add(
            () ->
                modele.execute(
                    _ -> {
                      try {
                        service.inviter(parrain, adresse, BASE);
                        return true;
                      } catch (ErreurMetier e) {
                        return false;
                      }
                    }));
      }
      int reussies = 0;
      for (final Future<Boolean> f : executeur.invokeAll(demandes)) {
        if (f.get()) {
          reussies++;
        }
      }
      assertEquals(1, reussies);
      assertEquals(1L, compter("invitations_utilisateurs"));
    } finally {
      executeur.shutdownNow();
    }
  }
}

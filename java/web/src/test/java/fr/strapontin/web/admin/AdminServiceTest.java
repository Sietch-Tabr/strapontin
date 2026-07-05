/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.VerificationRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.client.RestClient;

/// Tests unitaires pour {@link AdminService} (logique métier admin, dépendances mockées).
///
/// @since 2.2.3
/// @author Kwaite
class AdminServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private ConfigService configService;
  private EmailService emailService;
  private LogAdminService logAdminService;
  private VerificationRepository verificationRepository;
  private AdminService adminService;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    configService = mock(ConfigService.class);
    emailService = mock(EmailService.class);
    logAdminService = mock(LogAdminService.class);
    verificationRepository = mock(VerificationRepository.class);
    adminService =
        new AdminService(
            jdbc,
            configService,
            emailService,
            logAdminService,
            new ObjectMapper(),
            verificationRepository,
            mock(RestClient.class),
            mock(WorkerProxyService.class),
            mock(FindByIndexNameSessionRepository.class));
    adminService.setSelf(adminService);
  }

  @Test
  void creerUtilisateur_emailLibre_creeCompteEtEnvoieInvitation() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Integer.class))).thenReturn(List.of());

    Optional<ResultatCreation> resultat =
        adminService.creerUtilisateur("alice@exemple.fr", "admin-1", "https://strapontin.fr");

    assertTrue(resultat.isPresent());
    assertEquals("alice@exemple.fr", resultat.get().email());
    verify(emailService).envoyerInvitation(eq("alice@exemple.fr"), anyString());
    verify(verificationRepository)
        .inserer(anyString(), eq("invite:alice@exemple.fr"), anyString(), any());
  }

  @Test
  void creerUtilisateur_emailExistant_retourneEmpty() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Integer.class))).thenReturn(List.of(1));

    Optional<ResultatCreation> resultat =
        adminService.creerUtilisateur("bob@exemple.fr", "admin-1", "https://strapontin.fr");

    assertTrue(resultat.isEmpty());
    verify(emailService, never()).envoyerInvitation(anyString(), anyString());
    verify(jdbc, never()).update(anyString(), anyMap());
  }

  @Test
  void modifierUtilisateur_aucunChamp_leveErreurMetier() {
    RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur(null, "", "");
    assertThrows(
        ErreurMetier.class, () -> adminService.modifierUtilisateur("user-1", requete, "admin-1"));
  }

  @Test
  void modifierUtilisateur_emailInvalide_leveErreurMetier() {
    RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur(null, "pas-un-email", "");
    assertThrows(
        ErreurMetier.class, () -> adminService.modifierUtilisateur("user-1", requete, "admin-1"));
  }

  @Test
  void modifierUtilisateur_roleInvalide_leveErreurMetier() {
    RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur(null, "", "superadmin");
    assertThrows(
        ErreurMetier.class, () -> adminService.modifierUtilisateur("user-1", requete, "admin-1"));
  }

  @Test
  void modifierUtilisateur_emailDejaPris_leveErreurMetier() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Integer.class))).thenReturn(List.of(1));
    RequeteModifierUtilisateur requete =
        new RequeteModifierUtilisateur(null, "pris@exemple.fr", "");
    assertThrows(
        ErreurMetier.class, () -> adminService.modifierUtilisateur("user-1", requete, "admin-1"));
  }

  @Test
  void modifierUtilisateur_nomSeul_miseAJour() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur("Alice", null, null);

    adminService.modifierUtilisateur("user-1", requete, "admin-1");

    verify(jdbc).update(anyString(), anyMap());
    verify(logAdminService).logAdmin(eq("info"), eq("admin"), eq("nom modifié"), anyMap());
    verify(emailService, never()).envoyerPromotionAdmin(anyString());
  }

  @Test
  void modifierUtilisateur_promotionAdmin_envoieEmail() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Integer.class))).thenReturn(List.of());
    Map<String, Object> ligne = new HashMap<>();
    ligne.put("email", "promo@exemple.fr");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur(null, null, "admin");
    adminService.modifierUtilisateur("user-1", requete, "admin-1");

    verify(emailService).envoyerPromotionAdmin("promo@exemple.fr");
    verify(logAdminService).logAdmin(eq("warn"), eq("admin"), eq("rôle modifié"), anyMap());
  }

  @Test
  void modifierUtilisateur_changeRoleUserDejaUser_nenvoiePasEmail() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Integer.class))).thenReturn(List.of());
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(Map.of("role", "user")));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur(null, null, "user");
    adminService.modifierUtilisateur("user-1", requete, "admin-1");

    verify(emailService, never()).envoyerPromotionAdmin(anyString());
    verify(emailService, never()).envoyerPreavisRevocationAdmin(anyString(), anyString());
  }

  // #807 : PATCH modifierUtilisateur ne rétrograde plus jamais un admin immédiatement — délègue
  // à initierRevocationAdmin (même délai différé 7 jours, même garde dernier admin) plutôt que
  // d'exécuter un UPDATE role='user' direct.
  @Test
  void modifierUtilisateur_retrogradeAdminActuel_delegueAInitierRevocationAdmin() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Integer.class))).thenReturn(List.of());
    Map<String, Object> ligneAdminCible = new HashMap<>();
    ligneAdminCible.put("role", "admin");
    ligneAdminCible.put("email", "admin-cible@exemple.fr");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneAdminCible));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(2);
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur(null, null, "user");
    adminService.modifierUtilisateur("admin-cible", requete, "admin-1");

    verify(emailService).envoyerPreavisRevocationAdmin(eq("admin-cible@exemple.fr"), anyString());
    verify(logAdminService)
        .logAdmin(eq("warn"), eq("admin"), eq("révocation admin initiée"), anyMap());
    verify(logAdminService, never())
        .logAdmin(eq("warn"), eq("admin"), eq("rôle modifié"), anyMap());
    verify(emailService, never()).envoyerPromotionAdmin(anyString());
  }

  // #807 : le seul admin restant ne peut pas être rétrogradé via PATCH, même combiné à un
  // changement de nom dans la même requête.
  @Test
  void modifierUtilisateur_retrogradeSeulAdmin_leveErreurMetier409() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Integer.class))).thenReturn(List.of());
    Map<String, Object> ligneAdminCible = new HashMap<>();
    ligneAdminCible.put("role", "admin");
    ligneAdminCible.put("email", "admin-cible@exemple.fr");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneAdminCible));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(1);

    RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur(null, null, "user");
    ErreurMetier ex =
        assertThrows(
            ErreurMetier.class,
            () -> adminService.modifierUtilisateur("admin-cible", requete, "admin-1"));

    assertEquals(409, ex.getStatus());
  }

  @Test
  void initierRevocationAdmin_utilisateurInexistant_leveErreurMetier() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    assertThrows(
        ErreurMetier.class, () -> adminService.initierRevocationAdmin("inconnu", "admin-1"));
    verify(emailService, never()).envoyerPreavisRevocationAdmin(anyString(), anyString());
  }

  @Test
  void initierRevocationAdmin_pasAdmin_leveErreurMetier() {
    Map<String, Object> ligne = new HashMap<>();
    ligne.put("email", "user@exemple.fr");
    ligne.put("role", "user");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));

    assertThrows(
        ErreurMetier.class, () -> adminService.initierRevocationAdmin("user-1", "admin-1"));
    verify(emailService, never()).envoyerPreavisRevocationAdmin(anyString(), anyString());
  }

  @Test
  void initierRevocationAdmin_seulAdmin_leveErreurMetier409() {
    Map<String, Object> ligne = new HashMap<>();
    ligne.put("email", "admin@exemple.fr");
    ligne.put("role", "admin");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(1);

    ErreurMetier ex =
        assertThrows(
            ErreurMetier.class, () -> adminService.initierRevocationAdmin("admin-1", "admin-2"));
    assertEquals(409, ex.getStatus());
    verify(emailService, never()).envoyerPreavisRevocationAdmin(anyString(), anyString());
  }

  @Test
  void initierRevocationAdmin_adminCible_initieRevocationEtEnvoieEmail() {
    Map<String, Object> ligne = new HashMap<>();
    ligne.put("email", "admin@exemple.fr");
    ligne.put("role", "admin");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(2);
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    adminService.initierRevocationAdmin("admin-1", "admin-2");

    verify(jdbc).update(anyString(), anyMap());
    verify(emailService).envoyerPreavisRevocationAdmin(eq("admin@exemple.fr"), anyString());
    verify(logAdminService)
        .logAdmin(eq("warn"), eq("admin"), eq("révocation admin initiée"), anyMap());
  }

  // #811 : deux révocations concurrentes peuvent lever un conflit de sérialisation PostgreSQL
  // (isolation SERIALIZABLE) — la première tentative échoue, la seconde réussit.
  @Test
  void initierRevocationAdmin_conflitSerialisationPuisSucces_reessaieEtReussit() {
    Map<String, Object> ligne = new HashMap<>();
    ligne.put("email", "admin@exemple.fr");
    ligne.put("role", "admin");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(2);
    when(jdbc.update(anyString(), anyMap()))
        .thenThrow(new CannotSerializeTransactionException("conflit"))
        .thenReturn(1);

    adminService.initierRevocationAdmin("admin-1", "admin-2");

    verify(jdbc, times(2)).update(anyString(), anyMap());
    verify(emailService).envoyerPreavisRevocationAdmin(eq("admin@exemple.fr"), anyString());
  }

  @Test
  void initierRevocationAdmin_conflitSerialisationPersistant_leveApresMaxTentatives() {
    Map<String, Object> ligne = new HashMap<>();
    ligne.put("email", "admin@exemple.fr");
    ligne.put("role", "admin");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(2);
    when(jdbc.update(anyString(), anyMap()))
        .thenThrow(new CannotSerializeTransactionException("conflit"));

    assertThrows(
        CannotSerializeTransactionException.class,
        () -> adminService.initierRevocationAdmin("admin-1", "admin-2"));
    verify(jdbc, times(3)).update(anyString(), anyMap());
  }

  @Test
  void annulerRevocationAdmin_revocationExistante_annule() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    adminService.annulerRevocationAdmin("admin-1", "admin-2");

    verify(logAdminService)
        .logAdmin(eq("info"), eq("admin"), eq("révocation admin annulée"), anyMap());
  }

  @Test
  void annulerRevocationAdmin_aucuneRevocation_leveErreurMetier() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    assertThrows(
        ErreurMetier.class, () -> adminService.annulerRevocationAdmin("admin-1", "admin-2"));
  }

  @Test
  void sauvegarderConfig_cleNonAutorisee_leveErreurMetier() {
    assertThrows(
        ErreurMetier.class,
        () -> adminService.sauvegarderConfig("CLE_INCONNUE", "valeur", "admin-1"));
  }

  @Test
  void sauvegarderConfig_valeurVidePourCleObligatoire_leveErreurMetier() {
    assertThrows(
        ErreurMetier.class, () -> adminService.sauvegarderConfig("CONTACT_EMAIL", "", "admin-1"));
  }

  @Test
  void sauvegarderConfig_formatTicketmasterInvalide_leveErreurMetier() {
    assertThrows(
        ErreurMetier.class,
        () -> adminService.sauvegarderConfig("TICKETMASTER_API_KEY", "trop court", "admin-1"));
  }

  @Test
  void sauvegarderConfig_valeurValide_sauvegardeEtLogue() {
    adminService.sauvegarderConfig("CONTACT_EMAIL", "contact@strapontin.fr", "admin-1");
    verify(configService).sauvegarderConfig("CONTACT_EMAIL", "contact@strapontin.fr");
    verify(logAdminService).logAdmin(eq("info"), eq("admin"), anyString(), anyMap());
  }

  @Test
  void reinitialiserMdp_utilisateurInexistant_retourneEmpty() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    Optional<ResultatReset> resultat =
        adminService.reinitialiserMdp("user-1", "admin-1", "https://strapontin.fr");

    assertTrue(resultat.isEmpty());
    verify(emailService, never()).envoyerResetMotDePasse(anyString(), anyString());
  }

  @Test
  void reinitialiserMdp_utilisateurExistant_envoieResetEtRetourneEmailEtLien() {
    Map<String, Object> ligne = new HashMap<>();
    ligne.put("id", "user-1");
    ligne.put("email", "carol@exemple.fr");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));

    Optional<ResultatReset> resultat =
        adminService.reinitialiserMdp("user-1", "admin-1", "https://strapontin.fr");

    assertTrue(resultat.isPresent());
    assertEquals("carol@exemple.fr", resultat.get().email());
    assertTrue(
        resultat.get().lienReset().startsWith("https://strapontin.fr/reset-password?token="));
    verify(emailService).envoyerResetMotDePasse(eq("carol@exemple.fr"), anyString());
    verify(verificationRepository).supprimerParIdentifiant("reset:carol@exemple.fr");
  }

  @Test
  void reinitialiserTotp_utilisateurExistant_effaceSecretEtNotifie() {
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(Map.of("email", "user@exemple.fr")));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    boolean resultat = adminService.reinitialiserTotp("user-1", "admin-1");

    assertTrue(resultat);
    verify(emailService).envoyer2faReinitialisee("user@exemple.fr");
    verify(logAdminService).logAdmin(eq("warn"), eq("admin"), eq("TOTP réinitialisé"), anyMap());
  }

  @Test
  void reinitialiserTotp_utilisateurInexistant_retourneFalse() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    boolean resultat = adminService.reinitialiserTotp("inconnu", "admin-1");

    assertFalse(resultat);
    verify(emailService, never()).envoyer2faReinitialisee(anyString());
    verify(logAdminService, never()).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void completerAvecJobsJamaisExecutes_jobPlanifieSansHistorique_ajouteEntreeSynthetique() {
    List<StatJob> statsConnus =
        List.of(new StatJob("purger:concerts", java.time.Instant.EPOCH, 10, 1, 2, null, null));
    Map<String, java.time.Instant> prochainesExec =
        Map.of(
            "purger:concerts", java.time.Instant.EPOCH,
            "reconciliation:nocturne", java.time.Instant.MAX);

    List<StatJob> resultat =
        AdminService.completerAvecJobsJamaisExecutes(statsConnus, prochainesExec);

    assertEquals(2, resultat.size());
    StatJob jamaisExecute =
        resultat.stream()
            .filter(s -> s.typeJob().equals("reconciliation:nocturne"))
            .findFirst()
            .orElseThrow();
    assertEquals(null, jamaisExecute.derniereExec());
    assertEquals(java.time.Instant.MAX, jamaisExecute.prochaineExec());
  }

  @Test
  void completerAvecJobsJamaisExecutes_tousLesJobsConnus_neDupliquePas() {
    List<StatJob> statsConnus =
        List.of(new StatJob("purger:concerts", java.time.Instant.EPOCH, 10, 1, 2, null, null));
    Map<String, java.time.Instant> prochainesExec =
        Map.of("purger:concerts", java.time.Instant.EPOCH);

    List<StatJob> resultat =
        AdminService.completerAvecJobsJamaisExecutes(statsConnus, prochainesExec);

    assertEquals(1, resultat.size());
  }
}

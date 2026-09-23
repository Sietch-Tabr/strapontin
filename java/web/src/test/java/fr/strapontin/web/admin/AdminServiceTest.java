/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.email.EmailServiceAdmin;
import fr.strapontin.web.email.EmailServiceSecurite;
import fr.strapontin.web.securite.VerificationRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link AdminService} (logique métier de gestion des utilisateurs,
 * dépendances mockées).
 *
 * <p>Depuis, la configuration applicative et les statistiques de jobs sont testées dans {@code
 * ConfigAdminServiceTest}/{@code JobsAdminServiceTest}.
 *
 * @since 2.2.3
 * @author Kwaite
 */
class AdminServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private EmailService emailService;
  private EmailServiceAdmin emailServiceAdmin;
  private EmailServiceSecurite emailServiceSecurite;
  private AdminUtilisateursListingService adminUtilisateursListingService;
  private LogAdminService logAdminService;
  private VerificationRepository verificationRepository;
  private AdminService adminService;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    emailService = mock(EmailService.class);
    emailServiceAdmin = mock(EmailServiceAdmin.class);
    emailServiceSecurite = mock(EmailServiceSecurite.class);
    adminUtilisateursListingService = mock(AdminUtilisateursListingService.class);
    logAdminService = mock(LogAdminService.class);
    verificationRepository = mock(VerificationRepository.class);
    adminService =
        new AdminService(
            jdbc,
            emailService,
            emailServiceAdmin,
            emailServiceSecurite,
            adminUtilisateursListingService,
            mock(AdminModificationUtilisateurService.class),
            logAdminService,
            verificationRepository);
  }

  @Test
  void creerUtilisateur_emailLibre_creeCompteEtEnvoieInvitation() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Integer.class))).thenReturn(List.of());

    final Optional<ResultatCreation> resultat =
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

    final Optional<ResultatCreation> resultat =
        adminService.creerUtilisateur("bob@exemple.fr", "admin-1", "https://strapontin.fr");

    assertTrue(resultat.isEmpty());
    verify(emailService, never()).envoyerInvitation(anyString(), anyString());
    verify(jdbc, never()).update(anyString(), anyMap());
  }

  // la logique de modifierUtilisateur (validation, retrogradation, notifications) est testée
  // directement dans AdminModificationUtilisateurServiceTest — ici, seule la délégation compte.
  @Test
  void modifierUtilisateur_nominal_delegueAuServiceDeModification() {
    final AdminModificationUtilisateurService modificationService =
        mock(AdminModificationUtilisateurService.class);
    adminService =
        new AdminService(
            jdbc,
            emailService,
            emailServiceAdmin,
            emailServiceSecurite,
            adminUtilisateursListingService,
            modificationService,
            logAdminService,
            verificationRepository);
    final RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur("Alice", null, null);

    adminService.modifierUtilisateur("user-1", requete, "admin-1");

    verify(modificationService).modifierUtilisateur("user-1", requete, "admin-1");
  }

  @Test
  void reinitialiserMdp_utilisateurInexistant_retourneEmpty() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    final Optional<ResultatReset> resultat =
        adminService.reinitialiserMdp("user-1", "admin-1", "https://strapontin.fr");

    assertTrue(resultat.isEmpty());
    verify(emailService, never()).envoyerResetMotDePasse(anyString(), anyString());
  }

  @Test
  void reinitialiserMdp_utilisateurExistant_envoieResetEtRetourneEmailEtLien() {
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("id", "user-1");
    ligne.put("email", "carol@exemple.fr");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));

    final Optional<ResultatReset> resultat =
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

    final boolean resultat = adminService.reinitialiserTotp("user-1", "admin-1");

    assertTrue(resultat);
    verify(emailService).envoyer2faReinitialisee("user@exemple.fr");
    verify(logAdminService).logAdmin(eq("warn"), eq("admin"), eq("TOTP réinitialisé"), anyMap());
  }

  @Test
  void reinitialiserTotp_utilisateurInexistant_retourneFalse() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    final boolean resultat = adminService.reinitialiserTotp("inconnu", "admin-1");

    assertFalse(resultat);
    verify(emailService, never()).envoyer2faReinitialisee(anyString());
    verify(logAdminService, never()).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void revoquerToutesSessions_utilisateurExistant_supprimeSesSessionsEtLogue() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(Map.of("username", "user1")));
    when(jdbc.update(anyString(), anyMap())).thenReturn(2);

    final boolean resultat = adminService.revoquerToutesSessions("user-1", "admin-1");

    assertTrue(resultat);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("DELETE FROM SPRING_SESSION"),
            eq(Map.of("username", "user1")));
    verify(logAdminService).logAdmin(eq("warn"), eq("admin"), eq("sessions révoquées"), anyMap());
  }

  @Test
  void revoquerToutesSessions_utilisateurInexistant_retourneFalse() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    final boolean resultat = adminService.revoquerToutesSessions("inconnu", "admin-1");

    assertFalse(resultat);
    verify(jdbc, never()).update(org.mockito.ArgumentMatchers.contains("SPRING_SESSION"), anyMap());
  }

  @Test
  void verrouillerCompte_utilisateurExistant_verrouilleRevoqueEtNotifie() {
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(Map.of("username", "user1", "email", "user1@test.fr")));

    final boolean resultat = adminService.verrouillerCompte("user-1", "admin-1");

    assertTrue(resultat);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("SET verrouille = true"),
            eq(Map.of("id", "user-1")));
    verify(jdbc)
        .update(org.mockito.ArgumentMatchers.contains("DELETE FROM SPRING_SESSION"), anyMap());
    verify(emailServiceSecurite).envoyerCompteVerrouille("user1@test.fr");
    verify(logAdminService).logAdmin(eq("warn"), eq("admin"), eq("compte verrouillé"), anyMap());
  }

  @Test
  void verrouillerCompte_utilisateurInexistant_retourneFalse() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    final boolean resultat = adminService.verrouillerCompte("inconnu", "admin-1");

    assertFalse(resultat);
    verify(emailServiceSecurite, never()).envoyerCompteVerrouille(anyString());
  }

  @Test
  void deverrouillerCompte_utilisateurExistant_deverrouilleEtLogue() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(Map.of("id", "user-1")));

    final boolean resultat = adminService.deverrouillerCompte("user-1", "admin-1");

    assertTrue(resultat);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("SET verrouille = false"),
            eq(Map.of("id", "user-1")));
    verify(logAdminService).logAdmin(eq("warn"), eq("admin"), eq("compte déverrouillé"), anyMap());
  }

  @Test
  void deverrouillerCompte_utilisateurInexistant_retourneFalse() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    final boolean resultat = adminService.deverrouillerCompte("inconnu", "admin-1");

    assertFalse(resultat);
  }

  // la pagination elle-même (COUNT(*) OVER(), repli sur un second COUNT) est testée dans
  // AdminUtilisateursListingServiceTest — ici, seule la délégation compte.
  @Test
  void listerUtilisateurs_nominal_delegueAuServiceDeListage() {
    final ReponseUtilisateursAdmin attendue = new ReponseUtilisateursAdmin(List.of(), 0, 1, 0L);
    when(adminUtilisateursListingService.lister(0)).thenReturn(attendue);

    final ReponseUtilisateursAdmin reponse = adminService.listerUtilisateurs(0);

    assertEquals(attendue, reponse);
  }
}

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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
            mock(WorkerProxyService.class));
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
    RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur("", "");
    assertThrows(
        ErreurMetier.class, () -> adminService.modifierUtilisateur("user-1", requete, "admin-1"));
  }

  @Test
  void modifierUtilisateur_emailInvalide_leveErreurMetier() {
    RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur("pas-un-email", "");
    assertThrows(
        ErreurMetier.class, () -> adminService.modifierUtilisateur("user-1", requete, "admin-1"));
  }

  @Test
  void modifierUtilisateur_roleInvalide_leveErreurMetier() {
    RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur("", "superadmin");
    assertThrows(
        ErreurMetier.class, () -> adminService.modifierUtilisateur("user-1", requete, "admin-1"));
  }

  @Test
  void modifierUtilisateur_emailDejaPris_leveErreurMetier() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Integer.class))).thenReturn(List.of(1));
    RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur("pris@exemple.fr", "");
    assertThrows(
        ErreurMetier.class, () -> adminService.modifierUtilisateur("user-1", requete, "admin-1"));
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

    Optional<String> resultat =
        adminService.reinitialiserMdp("user-1", "admin-1", "https://strapontin.fr");

    assertTrue(resultat.isEmpty());
    verify(emailService, never()).envoyerResetMotDePasse(anyString(), anyString());
  }

  @Test
  void reinitialiserMdp_utilisateurExistant_envoieResetEtRetourneEmail() {
    Map<String, Object> ligne = new HashMap<>();
    ligne.put("id", "user-1");
    ligne.put("email", "carol@exemple.fr");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));

    Optional<String> resultat =
        adminService.reinitialiserMdp("user-1", "admin-1", "https://strapontin.fr");

    assertTrue(resultat.isPresent());
    assertEquals("carol@exemple.fr", resultat.get());
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
}

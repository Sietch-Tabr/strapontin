/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.email.EmailServiceAdmin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link AdminModificationUtilisateurService} (dépendances mockées).
 *
 * @since 2.9.1
 * @author Kwaite
 */
class AdminModificationUtilisateurServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private LogAdminService logAdminService;
  private AdminRevocationService adminRevocationService;
  private EmailServiceAdmin emailServiceAdmin;
  private AdminModificationUtilisateurService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    logAdminService = mock(LogAdminService.class);
    adminRevocationService = mock(AdminRevocationService.class);
    emailServiceAdmin = mock(EmailServiceAdmin.class);
    service =
        new AdminModificationUtilisateurService(
            jdbc, logAdminService, adminRevocationService, emailServiceAdmin);
  }

  @Test
  void modifierUtilisateur_aucunChamp_leveErreurMetier() {
    final RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur(null, "", "");
    assertThrows(
        ErreurMetier.class, () -> service.modifierUtilisateur("user-1", requete, "admin-1"));
  }

  @Test
  void modifierUtilisateur_emailInvalide_leveErreurMetier() {
    final RequeteModifierUtilisateur requete =
        new RequeteModifierUtilisateur(null, "pas-un-email", "");
    assertThrows(
        ErreurMetier.class, () -> service.modifierUtilisateur("user-1", requete, "admin-1"));
  }

  @Test
  void modifierUtilisateur_roleInvalide_leveErreurMetier() {
    final RequeteModifierUtilisateur requete =
        new RequeteModifierUtilisateur(null, "", "superadmin");
    assertThrows(
        ErreurMetier.class, () -> service.modifierUtilisateur("user-1", requete, "admin-1"));
  }

  @Test
  void modifierUtilisateur_emailDejaPris_leveErreurMetier() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Integer.class))).thenReturn(List.of(1));
    final RequeteModifierUtilisateur requete =
        new RequeteModifierUtilisateur(null, "pris@exemple.fr", "");
    assertThrows(
        ErreurMetier.class, () -> service.modifierUtilisateur("user-1", requete, "admin-1"));
  }

  @Test
  void modifierUtilisateur_nomSeul_miseAJour() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    final RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur("Alice", null, null);

    service.modifierUtilisateur("user-1", requete, "admin-1");

    verify(jdbc).update(anyString(), anyMap());
    verify(logAdminService).logAdmin(eq("info"), eq("admin"), eq("nom modifié"), anyMap());
    verify(emailServiceAdmin, never()).envoyerPromotionAdmin(anyString());
  }

  @Test
  void modifierUtilisateur_promotionAdmin_envoieEmail() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Integer.class))).thenReturn(List.of());
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("email", "promo@exemple.fr");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    final RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur(null, null, "admin");
    service.modifierUtilisateur("user-1", requete, "admin-1");

    verify(emailServiceAdmin).envoyerPromotionAdmin("promo@exemple.fr");
    verify(logAdminService).logAdmin(eq("warn"), eq("admin"), eq("rôle modifié"), anyMap());
  }

  @Test
  void modifierUtilisateur_changeRoleUserDejaUser_nenvoiePasEmail() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Integer.class))).thenReturn(List.of());
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(Map.of("role", "user")));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    final RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur(null, null, "user");
    service.modifierUtilisateur("user-1", requete, "admin-1");

    verify(emailServiceAdmin, never()).envoyerPromotionAdmin(anyString());
    verify(emailServiceAdmin, never()).envoyerPreavisRevocationAdmin(anyString(), anyString());
  }

  // PATCH modifierUtilisateur ne rétrograde plus jamais un admin immédiatement — délègue à
  // AdminRevocationService.initierRevocationAdmin (même délai différé 7 jours, même garde dernier
  // admin, testés directement dans AdminRevocationServiceTest) plutôt que d'exécuter un UPDATE
  // role='user' direct.
  @Test
  void modifierUtilisateur_retrogradeAdminActuel_delegueAInitierRevocationAdmin() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(Integer.class))).thenReturn(List.of());
    final Map<String, Object> ligneAdminCible = new HashMap<>();
    ligneAdminCible.put("role", "admin");
    ligneAdminCible.put("email", "admin-cible@exemple.fr");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneAdminCible));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    final RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur(null, null, "user");
    service.modifierUtilisateur("admin-cible", requete, "admin-1");

    verify(adminRevocationService).initierRevocationAdmin("admin-cible", "admin-1");
    verify(logAdminService, never())
        .logAdmin(eq("warn"), eq("admin"), eq("rôle modifié"), anyMap());
    verify(emailServiceAdmin, never()).envoyerPromotionAdmin(anyString());
  }

  // combiner une rétrogradation admin avec nom/email en un seul PATCH imbriquerait un
  // appel REQUIRES_NEW+SERIALIZABLE (initierRevocationAdminTransactionnel) dans la transaction
  // déjà ouverte de modifierUtilisateur — risque d'épuisement du pool HikariCP (incident v2.6.4).
  @Test
  void modifierUtilisateur_nomEtRetrogradationCombines_leveErreurMetierSansAppelerJdbcUpdate() {
    final Map<String, Object> ligneAdminCible = new HashMap<>();
    ligneAdminCible.put("role", "admin");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneAdminCible));

    final RequeteModifierUtilisateur requete =
        new RequeteModifierUtilisateur("Nouveau Nom", null, "user");
    final ErreurMetier ex =
        assertThrows(
            ErreurMetier.class,
            () -> service.modifierUtilisateur("admin-cible", requete, "admin-1"));

    assertEquals(400, ex.getStatus());
    verify(jdbc, never()).update(anyString(), anyMap());
  }
}

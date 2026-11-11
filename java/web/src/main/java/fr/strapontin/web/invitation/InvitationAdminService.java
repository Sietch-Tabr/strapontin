/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.invitation;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Réglages d'administration des invitations : plafond par défaut, plafond
 * propre à un utilisateur (de 0, invitation coupée, à {@link
 * ReglagesInvitations#PLAFOND_MAX}). Chaque modification est consignée dans
 * {@code logs_admin}.
 * @since 3.1.4
 * @author Kwaite
 */
@Service
public class InvitationAdminService {

  private static final String SOURCE_ADMIN = "admin";
  private static final String CLE_PAR_ADMIN = "parAdmin";
  private static final String CLE_USER_ID = "userId";

  private final NamedParameterJdbcTemplate jdbc;
  private final ReglagesInvitations reglagesInvitations;
  private final LogAdminService logAdminService;

  /**
   * @param jdbc accès JDBC
   * @param reglages valeurs réglables des invitations
   * @param logAdminService trace des modifications
   */
  public InvitationAdminService(
      final NamedParameterJdbcTemplate jdbc,
      final ReglagesInvitations reglages,
      final LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.reglagesInvitations = reglages;
    this.logAdminService = logAdminService;
  }

  /**
   * Valeurs en vigueur.
   * @param plafondDefaut plafond d'invitations en attente sans réglage propre
   * @param envoisParHeure envois autorisés par heure et par utilisateur
   * @param envoisParJour envois autorisés par jour, tous utilisateurs confondus
   */
  public record Reglages(int plafondDefaut, int envoisParHeure, int envoisParJour) {}

  /**
   * @return plafond par défaut et limites de cadence en vigueur
   */
  public Reglages reglages() {
    return new Reglages(
        reglagesInvitations.plafondDefaut(),
        reglagesInvitations.envoisParHeure(),
        reglagesInvitations.envoisParJour());
  }

  /**
   * @param valeur nouveau plafond par défaut
   * @param adminId administrateur qui le modifie
   * @throws ErreurMetier valeur hors bornes
   */
  public void definirPlafondDefaut(final int valeur, final String adminId) {
    verifierBornes(valeur);
    final int ancien = reglagesInvitations.plafondDefaut();
    reglagesInvitations.definirPlafondDefaut(valeur);
    logAdminService.logAdmin(
        "info",
        SOURCE_ADMIN,
        "plafond d'invitations par défaut modifié",
        Map.of("ancien", ancien, "nouveau", valeur, CLE_PAR_ADMIN, adminId));
  }

  /**
   * @param userId utilisateur concerné
   * @param plafond plafond propre, {@code null} pour revenir au plafond par
   *     défaut
   * @param adminId administrateur qui le modifie
   * @throws ErreurMetier valeur hors bornes ou utilisateur introuvable (404)
   */
  @Transactional
  public void definirPlafondUtilisateur(
      final String userId, final Integer plafond, final String adminId) {
    if (plafond != null) {
      verifierBornes(plafond);
    }
    final int modifies =
        jdbc.update(
            "UPDATE users SET plafond_invitations = :plafond WHERE id = :id",
            new MapSqlParameterSource().addValue("plafond", plafond).addValue("id", userId));
    if (modifies == 0) {
      throw new ErreurMetier("Utilisateur introuvable.", 404);
    }
    final Map<String, Object> meta = new HashMap<>();
    meta.put(CLE_USER_ID, userId);
    meta.put("plafond", plafond == null ? "défaut" : plafond);
    meta.put(CLE_PAR_ADMIN, adminId);
    logAdminService.logAdmin("info", SOURCE_ADMIN, "plafond d'invitations d'un utilisateur", meta);
  }

  /**
   * Efface le journal des envois d'invitations pour repartir d'une cadence vide (profil {@code
   * dev} uniquement, appelé par le global-setup des tests E2E).
   */
  public void reinitialiserEnvois() {
    jdbc.update("DELETE FROM envois_invitations", Map.of());
  }

  private static void verifierBornes(final int valeur) {
    if (valeur < 0 || valeur > ReglagesInvitations.PLAFOND_MAX) {
      throw new ErreurMetier(
          "Le plafond doit être compris entre 0 et " + ReglagesInvitations.PLAFOND_MAX + ".");
    }
  }
}

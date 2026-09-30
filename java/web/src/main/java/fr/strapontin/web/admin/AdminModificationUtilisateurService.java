/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.email.EmailServiceAdmin;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Modification des champs d'un compte utilisateur (nom, email, rôle)
 * par un admin — extrait d'{@link AdminService} pour rester sous le
 * seuil de complexité cyclomatique de classe (50).
 * @since 2.9.1
 * @author Kwaite
 */
@Service
class AdminModificationUtilisateurService {

  private static final Pattern FORMAT_EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final String ROLE_ADMIN = "admin";
  private static final String SOURCE_ADMIN = "admin";
  private static final String CLE_EMAIL = "email";
  private static final String CLE_USER_ID = "userId";
  private static final String CLE_PAR_ADMIN = "parAdmin";
  private static final String NIVEAU_WARN = "warn";
  private static final Set<String> ROLES_VALIDES = Set.of("user", ROLE_ADMIN);

  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;
  private final EmailServiceAdmin emailServiceAdmin;

  private record ChampsModification(String nom, String email, String role) {}

  /**
   * @param jdbc accès JDBC
   * @param logAdminService journalisation des modifications
   * @param emailServiceAdmin notification email de promotion admin
   */
  /* package-private : instancié uniquement par AdminService (même package). */
  AdminModificationUtilisateurService(
      final NamedParameterJdbcTemplate jdbc,
      final LogAdminService logAdminService,
      final EmailServiceAdmin emailServiceAdmin) {
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
    this.emailServiceAdmin = emailServiceAdmin;
  }

  /**
   * Modifie le nom, l'email et/ou le rôle. Une rétrogradation d'admin est
   * toujours refusée : elle passe par la révocation différée dédiée ({@link
   * AdminRevocationService#initierRevocationAdmin}), qui ouvre sa propre
   * transaction {@code REQUIRES_NEW} + {@code SERIALIZABLE} — appelée depuis
   * cette méthode transactionnelle, elle attendrait le verrou posé ici sur la
   * même ligne {@code users}.
   * @param userId identifiant de l'utilisateur cible
   * @param requete champs à modifier (nom, email, role — tous optionnels)
   * @param adminId identifiant de l'admin demandeur
   * @throws ErreurMetier si invalide, email pris, ou rétrogradation d'admin
   */
  @Transactional
  /* package-private : appelé par AdminService (même package). */
  void modifierUtilisateur(
      final String userId, final RequeteModifierUtilisateur requete, final String adminId) {
    final ChampsModification champs = validerEtNormaliser(requete, userId);
    if (estRetrogradationAdmin(userId, champs.role())) {
      throw new ErreurMetier(
          "La rétrogradation d'un administrateur passe par l'action « Révoquer admin », avec"
              + " préavis.");
    }

    appliquerModification(userId, champs);
    notifierModification(userId, adminId, champs);
  }

  private ChampsModification validerEtNormaliser(
      final RequeteModifierUtilisateur requete, final String userId) {
    final String nom = requete.nom() == null ? "" : requete.nom().strip();
    final String email =
        requete.email() == null ? "" : requete.email().strip().toLowerCase(Locale.ROOT);
    final String role =
        requete.role() == null ? "" : requete.role().strip().toLowerCase(Locale.ROOT);

    validerChampsPresents(nom, email, role);
    validerFormatEmail(email);
    validerRole(role);
    if (!email.isEmpty() && emailDejaPris(email, userId)) {
      throw new ErreurMetier("Cette adresse email est déjà utilisée.");
    }
    return new ChampsModification(nom, email, role);
  }

  private static void validerChampsPresents(
      final String nom, final String email, final String role) {
    if (nom.isEmpty() && email.isEmpty() && role.isEmpty()) {
      throw new ErreurMetier("Au moins un champ à modifier est requis.");
    }
  }

  private static void validerFormatEmail(final String email) {
    if (!email.isEmpty() && !FORMAT_EMAIL.matcher(email).matches()) {
      throw new ErreurMetier("Format d'email invalide.");
    }
  }

  private static void validerRole(final String role) {
    if (!role.isEmpty() && !ROLES_VALIDES.contains(role)) {
      throw new ErreurMetier("Rôle invalide. Valeurs acceptées : user, admin.");
    }
  }

  private boolean emailDejaPris(final String email, final String userId) {
    return !jdbc.queryForList(
            "SELECT 1 FROM users WHERE email = :email AND id != :id",
            Map.of(CLE_EMAIL, email, "id", userId),
            Integer.class)
        .isEmpty();
  }

  private boolean estRetrogradationAdmin(final String userId, final String role) {
    if (role.isEmpty()) {
      return false;
    }
    final List<Map<String, Object>> roleActuelRows =
        jdbc.queryForList(
            "SELECT role::text AS role FROM users WHERE id = :id", Map.of("id", userId));
    final String roleActuel =
        roleActuelRows.isEmpty() ? null : (String) roleActuelRows.get(0).get("role");
    return "user".equals(role) && ROLE_ADMIN.equals(roleActuel);
  }

  private void appliquerModification(final String userId, final ChampsModification champs) {
    final Map<String, Object> miseAJour = new HashMap<>();
    miseAJour.put("updatedAt", Timestamp.from(Instant.now()));
    miseAJour.put(CLE_USER_ID, userId);
    if (!champs.nom().isEmpty()) {
      miseAJour.put("nom", champs.nom());
    }
    if (!champs.email().isEmpty()) {
      miseAJour.put(CLE_EMAIL, champs.email());
    }
    if (!champs.role().isEmpty()) {
      miseAJour.put("role", champs.role());
    }

    final StringBuilder setClauses = new StringBuilder(150).append("updated_at = :updatedAt");
    if (!champs.nom().isEmpty()) {
      setClauses.append(", display_username = :nom");
    }
    if (!champs.email().isEmpty()) {
      setClauses.append(", email = :email");
    }
    if (!champs.role().isEmpty()) {
      setClauses.append(", role = CAST(:role AS role_utilisateur)");
    }
    jdbc.update("UPDATE users SET " + setClauses + " WHERE id = :userId", miseAJour);
  }

  private void notifierModification(
      final String userId,
      final String adminId,
      final ChampsModification champs) {
    if (!champs.nom().isEmpty()) {
      logAdminService.logAdmin(
          "info", SOURCE_ADMIN, "nom modifié", Map.of(CLE_USER_ID, userId, CLE_PAR_ADMIN, adminId));
    }
    if (!champs.email().isEmpty()) {
      logAdminService.logAdmin(
          NIVEAU_WARN,
          SOURCE_ADMIN,
          "email modifié",
          Map.of(CLE_USER_ID, userId, "nouvelEmail", champs.email(), CLE_PAR_ADMIN, adminId));
    }
    if (champs.role().isEmpty()) {
      return;
    }
    logAdminService.logAdmin(
        NIVEAU_WARN,
        SOURCE_ADMIN,
        "rôle modifié",
        Map.of(CLE_USER_ID, userId, "nouveauRole", champs.role(), CLE_PAR_ADMIN, adminId));
    if (ROLE_ADMIN.equals(champs.role())) {
      final List<Map<String, Object>> cible =
          jdbc.queryForList("SELECT email FROM users WHERE id = :id", Map.of("id", userId));
      if (!cible.isEmpty()) {
        emailServiceAdmin.envoyerPromotionAdmin((String) cible.get(0).get(CLE_EMAIL));
      }
    }
  }
}

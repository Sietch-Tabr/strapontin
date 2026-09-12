/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.core.RetrySerialisable;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.email.EmailServiceAdmin;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Révocation différée (préavis 7 jours) du rôle administrateur — extrait d'{@link AdminService}
 * pour rester sous la limite de 500 lignes par fichier.
 *
 * @since 2.9.1
 * @author Kwaite
 */
@Service
public class AdminRevocationService {

  private static final Logger LOG = LoggerFactory.getLogger(AdminRevocationService.class);
  private static final int MAX_TENTATIVES_REVOCATION = 3;

  private final NamedParameterJdbcTemplate jdbc;
  private final EmailServiceAdmin emailServiceAdmin;
  private final LogAdminService logAdminService;
  private AdminRevocationService self;

  /**
   * @param jdbc accès JDBC
   * @param emailServiceAdmin envoi du préavis de révocation
   * @param logAdminService service de logs admin
   */
  public AdminRevocationService(
      final NamedParameterJdbcTemplate jdbc,
      final EmailServiceAdmin emailServiceAdmin,
      final LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.emailServiceAdmin = emailServiceAdmin;
    this.logAdminService = logAdminService;
  }

  /**
   * Injection par setter (plutôt que constructeur) de la référence proxifiée de ce bean, pour
   * éviter le problème d'auto-référence à la construction — l'appel à {@link
   * #initierRevocationAdminTransactionnel} passe ainsi par le proxy AOP Spring et bénéficie de
   * l'isolation {@code SERIALIZABLE}. En dehors d'un contexte Spring (tests unitaires), {@code
   * self} peut être positionné manuellement sur l'instance elle-même.
   *
   * @param self référence proxifiée de ce bean ({@code @Lazy})
   */
  @Autowired
  public void setSelf(@Lazy final AdminRevocationService self) {
    this.self = self;
  }

  /**
   * Initie une révocation différée du rôle admin (effective dans 7 jours).
   *
   * <p>Réessaie jusqu'à {@value MAX_TENTATIVES_REVOCATION} fois en cas d'échec de sérialisation
   * (deux révocations concurrentes détectées par PostgreSQL) — sans cela, deux admins se révoquant
   * mutuellement au même instant pourraient tous deux passer la garde « dernier admin » avant
   * qu'aucun des deux {@code UPDATE} ne soit committé.
   *
   * @param userId identifiant de l'administrateur à révoquer
   * @param adminId identifiant de l'admin demandeur
   */
  public void initierRevocationAdmin(final String userId, final String adminId) {
    RetrySerialisable.executer(
        MAX_TENTATIVES_REVOCATION,
        LOG,
        "la révocation admin",
        () -> self.initierRevocationAdminTransactionnel(userId, adminId));
  }

  /**
   * Partie transactionnelle et isolée de {@link #initierRevocationAdmin}.
   *
   * <p>Envoie un email de préavis à l'administrateur concerné et loggue l'action. {@code
   * REQUIRES_NEW} garantit une transaction dédiée même si appelée depuis une transaction déjà
   * ouverte (ex. {@code AdminService#modifierUtilisateur}) — sans quoi l'isolation SERIALIZABLE
   * serait ignorée en cas d'imbrication dans une transaction moins stricte.
   *
   * @param userId identifiant de l'administrateur à révoquer
   * @param adminId identifiant de l'admin demandeur
   * @throws ErreurMetier si l'utilisateur est le seul admin restant (409) ou si l'utilisateur n'est
   *     pas admin (400)
   */
  @Transactional(isolation = Isolation.SERIALIZABLE, propagation = Propagation.REQUIRES_NEW)
  void initierRevocationAdminTransactionnel(final String userId, final String adminId) {
    final List<Map<String, Object>> cible =
        jdbc.queryForList(
            "SELECT email, role::text AS role FROM users WHERE id = :id", Map.of("id", userId));
    if (cible.isEmpty()) {
      throw new ErreurMetier("Utilisateur introuvable.", 404);
    }
    if (!"admin".equals(cible.get(0).get("role"))) {
      throw new ErreurMetier("Cet utilisateur n'est pas administrateur.");
    }

    final int nbAdmins =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE role = 'admin' AND revocation_admin_le IS NULL",
            Map.of(),
            Integer.class);
    if (nbAdmins <= 1) {
      throw new ErreurMetier("Impossible de révoquer le seul administrateur restant.", 409);
    }

    final Instant dateEffective = Instant.now().plus(7, ChronoUnit.DAYS);
    jdbc.update(
        "UPDATE users SET revocation_admin_le = :date WHERE id = :id",
        Map.of("date", Timestamp.from(dateEffective), "id", userId));

    final String dateFormatee =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .withLocale(Locale.FRANCE)
            .format(LocalDate.ofInstant(dateEffective, ZoneId.of("Europe/Paris")));

    emailServiceAdmin.envoyerPreavisRevocationAdmin(
        (String) cible.get(0).get("email"), dateFormatee);
    logAdminService.logAdmin(
        "warn",
        "admin",
        "révocation admin initiée",
        Map.of("userId", userId, "dateEffective", dateFormatee, "parAdmin", adminId));
  }

  /**
   * Annule une révocation admin en cours (remet revocation_admin_le à NULL).
   *
   * @param userId identifiant de l'administrateur concerné
   * @param adminId identifiant de l'admin demandeur
   * @throws ErreurMetier si l'utilisateur n'existe pas ou n'a pas de révocation en cours
   */
  public void annulerRevocationAdmin(final String userId, final String adminId) {
    final int miseAJour =
        jdbc.update(
            "UPDATE users SET revocation_admin_le = NULL WHERE id = :id"
                + " AND revocation_admin_le IS NOT NULL",
            Map.of("id", userId));
    if (miseAJour == 0) {
      throw new ErreurMetier("Aucune révocation en cours pour cet utilisateur.");
    }
    logAdminService.logAdmin(
        "info", "admin", "révocation admin annulée", Map.of("userId", userId, "parAdmin", adminId));
  }
}

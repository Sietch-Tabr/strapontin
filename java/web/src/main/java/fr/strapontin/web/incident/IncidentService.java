/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.incident;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.email.EmailServiceSecurite;
import fr.strapontin.web.push.NotificationPushService;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Communication d'un incident de sécurité aux utilisateurs : bannière visible
 * de tous (y compris non connectés), email à chaque compte, push à tous les
 * abonnements, changement de mot de passe exigé — chaque canal optionnel.
 * @since 3.0.1
 * @author Kwaite
 */
@Service
public class IncidentService {

  private final NamedParameterJdbcTemplate jdbc;
  private final EmailServiceSecurite emailService;
  private final NotificationPushService pushService;
  private final LogAdminService logAdminService;

  /**
   * @param jdbc accès JDBC
   * @param emailService envoi des emails de communication
   * @param pushService envoi des notifications push
   * @param logAdminService journalisation de la publication
   */
  public IncidentService(
      final NamedParameterJdbcTemplate jdbc,
      final EmailServiceSecurite emailService,
      final NotificationPushService pushService,
      final LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.emailService = emailService;
    this.pushService = pushService;
    this.logAdminService = logAdminService;
  }

  /**
   * @return la communication d'incident active, s'il y en a une
   */
  public Optional<CommunicationIncident> lireActive() {
    return jdbc
        .query(
            """
            SELECT id, titre, message, publiee_le, nb_emails, nb_push, changement_mdp_force
            FROM communications_incident WHERE terminee_le IS NULL
            """,
            Map.of(),
            (rs, _) ->
                new CommunicationIncident(
                    rs.getLong("id"),
                    rs.getString("titre"),
                    rs.getString("message"),
                    rs.getTimestamp("publiee_le").toInstant(),
                    rs.getInt("nb_emails"),
                    rs.getInt("nb_push"),
                    rs.getBoolean("changement_mdp_force")))
        .stream()
        .findFirst();
  }

  /**
   * @return nombre de comptes qu'un envoi par email toucherait
   */
  public int compterComptes() {
    final Integer nb =
        jdbc.queryForObject(
            "SELECT count(*) FROM users WHERE email IS NOT NULL", Map.of(), Integer.class);
    return nb != null ? nb : 0;
  }

  /**
   * @return emails encore envoyables avant blocage par le quota Resend
   */
  public int envoisEmailRestants() {
    return emailService.envoisEmailRestants();
  }

  /**
   * Publie une communication d'incident (remplace la précédente) et la
   * diffuse sur les canaux demandés. Si un nouveau mot de passe est exigé,
   * toutes les sessions sont révoquées sauf celle de l'admin qui publie, et
   * la connexion est refusée jusqu'à réinitialisation par email ({@code
   * VerificationMotDePasseExige}).
   * @param requete contenu et canaux de diffusion
   * @param adminId administrateur à l'origine de la publication
   * @param sessionConservee identifiant de la session de l'admin qui publie,
   *     null pour révoquer toutes les sessions
   * @return la communication publiée, avec le nombre d'envois effectifs
   * @throws ErreurMetier 409 si le quota email ne couvre pas tous les comptes
   */
  public CommunicationIncident publier(
      final RequetePublicationIncident requete,
      final String adminId,
      final String sessionConservee) {
    final List<String> destinataires = destinatairesEmail(requete);
    final long id = enregistrer(requete);
    final int sessionsRevoquees =
        requete.changementMdpForce() ? revoquerSessions(sessionConservee) : 0;
    final int nbEmails =
        destinataires.isEmpty()
            ? 0
            : emailService.envoyerCommunicationIncident(
                destinataires,
                requete.titre().strip(),
                requete.message().strip(),
                requete.changementMdpForce());
    final int nbPush =
        requete.push()
            ? pushService.envoyerATous(requete.titre().strip(), requete.message().strip())
            : 0;
    jdbc.update(
        "UPDATE communications_incident SET nb_emails = :emails, nb_push = :push WHERE id = :id",
        Map.of("emails", nbEmails, "push", nbPush, "id", id));

    logAdminService.logAdmin(
        "warn",
        "admin",
        "communication d'incident publiée",
        Map.of(
            "adminId", adminId,
            "emails", nbEmails,
            "push", nbPush,
            "changementMdpForce", requete.changementMdpForce(),
            "sessionsRevoquees", sessionsRevoquees));
    return new CommunicationIncident(
        id,
        requete.titre().strip(),
        requete.message().strip(),
        Instant.now(),
        nbEmails,
        nbPush,
        requete.changementMdpForce());
  }

  // Envoi partiel refusé : il laisserait des comptes sans nouvelles sans qu'on
  // sache lesquels.
  private List<String> destinatairesEmail(final RequetePublicationIncident requete) {
    if (!requete.email()) {
      return List.of();
    }
    final List<String> destinataires =
        jdbc.queryForList(
            "SELECT email FROM users WHERE email IS NOT NULL", Map.of(), String.class);
    final int restants = emailService.envoisEmailRestants();
    if (destinataires.size() > restants) {
      throw new ErreurMetier(
          "Quota d'emails insuffisant : "
              + destinataires.size()
              + " comptes à prévenir, "
              + restants
              + " envois restants. Réessayez demain ou décochez l'email.",
          409);
    }
    return destinataires;
  }

  // Le mot de passe a pu fuiter : une session ouverte avec lui ne doit plus servir.
  private int revoquerSessions(final String sessionConservee) {
    final MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("conservee", sessionConservee, Types.VARCHAR);
    return jdbc.update(
        "DELETE FROM SPRING_SESSION WHERE CAST(:conservee AS text) IS NULL"
            + " OR SESSION_ID <> CAST(:conservee AS text)",
        params);
  }

  // Remplace l'éventuelle communication active, exige le nouveau mot de passe.
  private long enregistrer(final RequetePublicationIncident requete) {
    jdbc.update(
        "UPDATE communications_incident SET terminee_le = now() WHERE terminee_le IS NULL",
        Map.of());
    final Long id =
        jdbc.queryForObject(
            """
            INSERT INTO communications_incident (titre, message, changement_mdp_force)
            VALUES (:titre, :message, :mdp) RETURNING id
            """,
            Map.of(
                "titre", requete.titre().strip(),
                "message", requete.message().strip(),
                "mdp", requete.changementMdpForce()),
            Long.class);
    if (requete.changementMdpForce()) {
      jdbc.update("UPDATE users SET changement_mdp_force_le = now()", Map.of());
    }
    return id;
  }

  /**
   * Clôt la communication active : la bannière disparaît pour tous. Le
   * changement de mot de passe déjà exigé reste dû.
   * @return {@code true} si une communication active a été close
   */
  public boolean terminer() {
    final int clos =
        jdbc.update(
            "UPDATE communications_incident SET terminee_le = now() WHERE terminee_le IS NULL",
            Map.of());
    if (clos > 0) {
      logAdminService.logAdmin("info", "admin", "communication d'incident close");
    }
    return clos > 0;
  }
}

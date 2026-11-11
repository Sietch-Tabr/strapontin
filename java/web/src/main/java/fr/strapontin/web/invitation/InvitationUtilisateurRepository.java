/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.invitation;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Accès SQL aux invitations envoyées par les utilisateurs et à leur journal
 * d'envois.
 * @since 3.1.4
 * @author Kwaite
 */
@Repository
class InvitationUtilisateurRepository {

  private static final String CLE_PARRAIN = "parrain";
  private static final String CLE_ID = "id";
  private static final String CLE_EMAIL = "email";
  private static final String EN_ATTENTE =
      "active_le IS NULL AND annulee_le IS NULL AND expire_le > now()";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * @param jdbc accès JDBC
   */
  /* package-private : appelé par InvitationUtilisateurService (même package). */
  InvitationUtilisateurRepository(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Invitation telle que l'affiche la page Paramètres. */
  /* package-private : appelé par InvitationUtilisateurService (même package). */
  record Ligne(
      UUID id,
      String email,
      Instant creeLe,
      Instant expireLe,
      Instant activeLe,
      Instant annuleeLe) {

    /**
     * @param maintenant instant de référence
     * @return {@code activee}, {@code annulee}, {@code expiree} ou {@code
     *     en_attente}
     */
    /* package-private : appelé par InvitationUtilisateurService (même package). */
    String statut(final Instant maintenant) {
      if (activeLe != null) {
        return "activee";
      }
      if (annuleeLe != null) {
        return "annulee";
      }
      return expireLe.isAfter(maintenant) ? "en_attente" : "expiree";
    }
  }

  /**
   * Verrouille la ligne du parrain pour sérialiser deux demandes simultanées.
   * @param parrainId identifiant du parrain
   * @return {@code true} si le parrain existe
   */
  /* package-private : appelé par InvitationUtilisateurService (même package). */
  boolean verrouillerParrain(final String parrainId) {
    return !jdbc.queryForList(
            "SELECT 1 FROM users WHERE id = :id FOR UPDATE",
            Map.of(CLE_ID, parrainId),
            Integer.class)
        .isEmpty();
  }

  /**
   * @param parrainId identifiant du parrain
   * @return plafond propre au parrain, vide s'il suit le plafond par défaut ou
   *     n'existe pas
   */
  /* package-private : appelé par InvitationUtilisateurService (même package). */
  Optional<Integer> plafondPropre(final String parrainId) {
    return jdbc
        .queryForList(
            "SELECT plafond_invitations FROM users WHERE id = :id",
            Map.of(CLE_ID, parrainId),
            Integer.class)
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  /* package-private : appelé par InvitationUtilisateurService (même package). */
  int compterEnAttente(final String parrainId) {
    return nombre(
        "SELECT count(*) FROM invitations_utilisateurs WHERE parrain_id = :parrain AND "
            + EN_ATTENTE,
        Map.of(CLE_PARRAIN, parrainId));
  }

  /* package-private : appelé par InvitationUtilisateurService (même package). */
  int compterEnvoisDepuis(final String parrainId, final Instant depuis) {
    return nombre(
        "SELECT count(*) FROM envois_invitations WHERE parrain_id = :parrain"
            + " AND envoye_le > CAST(:depuis AS timestamptz)",
        new MapSqlParameterSource(CLE_PARRAIN, parrainId).addValue("depuis", depuis.toString()));
  }

  /* package-private : appelé par InvitationUtilisateurService (même package). */
  int compterTousEnvoisDepuis(final Instant depuis) {
    return nombre(
        "SELECT count(*) FROM envois_invitations WHERE envoye_le > CAST(:depuis AS timestamptz)",
        new MapSqlParameterSource("depuis", depuis.toString()));
  }

  /* package-private : appelé par InvitationUtilisateurService (même package). */
  void enregistrerEnvoi(final String parrainId) {
    jdbc.update(
        "INSERT INTO envois_invitations (parrain_id) VALUES (:parrain)",
        Map.of(CLE_PARRAIN, parrainId));
    jdbc.update(
        "DELETE FROM envois_invitations WHERE envoye_le < now() - interval '7 days'", Map.of());
  }

  /* package-private : appelé par InvitationUtilisateurService (même package). */
  boolean compteExiste(final String email) {
    return nombre(
            "SELECT count(*) FROM users WHERE lower(email) = :email", Map.of(CLE_EMAIL, email))
        > 0;
  }

  /* package-private : appelé par InvitationUtilisateurService (même package). */
  boolean invitationEnAttentePour(final String email) {
    return nombre(
            "SELECT count(*) FROM invitations_utilisateurs WHERE lower(email) = :email AND "
                + EN_ATTENTE,
            Map.of(CLE_EMAIL, email))
        > 0;
  }

  /* package-private : appelé par InvitationUtilisateurService (même package). */
  void creer(
      final String parrainId, final String email, final String tokenHash, final Instant expireLe) {
    jdbc.update(
        """
        INSERT INTO invitations_utilisateurs (parrain_id, email, token_hash, expire_le)
        VALUES (:parrain, :email, :tokenHash, CAST(:expireLe AS timestamptz))
        """,
        new MapSqlParameterSource(CLE_PARRAIN, parrainId)
            .addValue(CLE_EMAIL, email)
            .addValue("tokenHash", tokenHash)
            .addValue("expireLe", expireLe.toString()));
  }

  /* package-private : appelé par InvitationUtilisateurService (même package). */
  List<Ligne> lister(final String parrainId) {
    return jdbc.query(
        """
        SELECT id, email, cree_le, expire_le, active_le, annulee_le
        FROM invitations_utilisateurs WHERE parrain_id = :parrain
        ORDER BY cree_le DESC LIMIT 50
        """,
        Map.of(CLE_PARRAIN, parrainId),
        (rs, _) ->
            new Ligne(
                rs.getObject(CLE_ID, UUID.class),
                rs.getString(CLE_EMAIL),
                rs.getObject("cree_le", OffsetDateTime.class).toInstant(),
                rs.getObject("expire_le", OffsetDateTime.class).toInstant(),
                instant(rs.getObject("active_le", OffsetDateTime.class)),
                instant(rs.getObject("annulee_le", OffsetDateTime.class))));
  }

  /**
   * @return l'adresse d'une invitation en attente de ce parrain, vide sinon
   */
  /* package-private : appelé par InvitationUtilisateurService (même package). */
  Optional<String> emailEnAttente(final String parrainId, final UUID id) {
    return jdbc
        .queryForList(
            "SELECT email FROM invitations_utilisateurs WHERE id = CAST(:id AS uuid)"
                + " AND parrain_id = :parrain AND "
                + EN_ATTENTE,
            Map.of(CLE_ID, id.toString(), CLE_PARRAIN, parrainId),
            String.class)
        .stream()
        .findFirst();
  }

  /* package-private : appelé par InvitationUtilisateurService (même package). */
  void renouveler(final UUID id, final String tokenHash, final Instant expireLe) {
    jdbc.update(
        """
        UPDATE invitations_utilisateurs
        SET token_hash = :tokenHash, expire_le = CAST(:expireLe AS timestamptz)
        WHERE id = CAST(:id AS uuid)
        """,
        Map.of(CLE_ID, id.toString(), "tokenHash", tokenHash, "expireLe", expireLe.toString()));
  }

  /* package-private : appelé par InvitationUtilisateurService (même package). */
  boolean annuler(final String parrainId, final UUID id) {
    return jdbc.update(
            "UPDATE invitations_utilisateurs SET annulee_le = now()"
                + " WHERE id = CAST(:id AS uuid) AND parrain_id = :parrain AND "
                + EN_ATTENTE,
            Map.of(CLE_ID, id.toString(), CLE_PARRAIN, parrainId))
        > 0;
  }

  /** Invitation en attente retrouvée par son lien. */
  /* package-private : appelé par InvitationUtilisateurService (même package). */
  record Active(UUID id, String parrainId, String email) {}

  /* package-private : appelé par InvitationUtilisateurService (même package). */
  Optional<Active> trouverEnAttenteParToken(final String tokenHash) {
    return jdbc
        .query(
            "SELECT id, parrain_id, email FROM invitations_utilisateurs"
                + " WHERE token_hash = :tokenHash AND "
                + EN_ATTENTE,
            Map.of("tokenHash", tokenHash),
            (rs, _) ->
                new Active(
                    rs.getObject(CLE_ID, UUID.class),
                    rs.getString("parrain_id"),
                    rs.getString(CLE_EMAIL)))
        .stream()
        .findFirst();
  }

  /* package-private : appelé par InvitationUtilisateurService (même package). */
  void marquerActivee(final UUID id) {
    jdbc.update(
        "UPDATE invitations_utilisateurs SET active_le = now() WHERE id = CAST(:id AS uuid)",
        Map.of(CLE_ID, id.toString()));
  }

  private int nombre(final String sql, final Map<String, ?> params) {
    final Integer nombre = jdbc.queryForObject(sql, params, Integer.class);
    return nombre == null ? 0 : nombre;
  }

  private int nombre(final String sql, final MapSqlParameterSource params) {
    final Integer nombre = jdbc.queryForObject(sql, params, Integer.class);
    return nombre == null ? 0 : nombre;
  }

  private static Instant instant(final OffsetDateTime horodatage) {
    return horodatage == null ? null : horodatage.toInstant();
  }
}

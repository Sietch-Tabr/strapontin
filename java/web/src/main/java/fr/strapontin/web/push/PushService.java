/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import fr.strapontin.core.EchecPush;
import fr.strapontin.core.EndpointPush;
import fr.strapontin.core.RetrySerialisable;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de gestion des abonnements Web Push — limite à 10 abonnements par
 * utilisateur, vérifie la clé VAPID avant d'abonner.
 * @since 2.1.0
 * @author Kwaite
 */
@Service
public class PushService {

  private static final Logger LOG = LoggerFactory.getLogger(PushService.class);
  private static final int CAP_ABONNEMENTS = 10;
  private static final int MAX_TENTATIVES_ABONNEMENT = 3;
  private static final String CLE_USER_ID = "userId";

  private final ConfigService configService;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;
  private PushService self;

  /**
   * @param configService lecture de la clé VAPID publique
   * @param jdbc accès JDBC
   * @param logAdminService service de logs admin
   */
  public PushService(
      final ConfigService configService,
      final NamedParameterJdbcTemplate jdbc,
      final LogAdminService logAdminService) {
    this.configService = configService;
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /**
   * Injection par setter (plutôt que constructeur) de la référence proxifiée
   * de ce bean, pour éviter l'auto-référence à la construction — l'appel à
   * {@link #abonnerTransactionnel} passe par le proxy AOP Spring et
   * bénéficie de l'isolation {@code SERIALIZABLE} (même pattern que {@code
   * AdminService}). Hors contexte Spring (tests), positionnable à la main.
   * @param self référence proxifiée de ce bean ({@code @Lazy})
   */
  @Autowired
  public void setSelf(@Lazy final PushService self) {
    this.self = self;
  }

  /**
   * Retourne la clé VAPID publique si configurée.
   *
   * @return réponse avec clé ou {@code disponible: false}
   */
  public ReponseVapid obtenirCleVapid() {
    final String clePublique = configService.lireConfig("VAPID_PUBLIC_KEY").orElse(null);
    return clePublique != null
        ? new ReponseVapid(true, clePublique)
        : new ReponseVapid(false, null);
  }

  /**
   * Enregistre un nouvel abonnement push — COUNT+INSERT atomique via
   * transaction {@code SERIALIZABLE} avec retry, sans quoi deux abonnements
   * concurrents pouvaient tous deux dépasser le cap avant commit.
   * @param userId identifiant de l'utilisateur
   * @param requete données de l'abonnement
   * @throws ErreurMetier 400 si invalide, 429 si cap atteint
   */
  public void abonner(final String userId, final RequeteAbonnement requete) {
    abonner(userId, requete, null);
  }

  /**
   * Enregistre un abonnement push en gardant l'appareil qui l'a créé, pour que
   * l'utilisateur puisse le reconnaître dans sa liste.
   * @param userId identifiant de l'utilisateur
   * @param requete données de l'abonnement
   * @param appareil navigateur et système de l'appareil, null si inconnus
   * @throws ErreurMetier 400 si invalide, 429 si cap atteint
   */
  public void abonner(final String userId, final RequeteAbonnement requete, final String appareil) {
    validerAbonnementComplet(requete);
    validerTailleAbonnement(requete);

    RetrySerialisable.executer(
        MAX_TENTATIVES_ABONNEMENT,
        LOG,
        "l'abonnement push",
        () -> self.abonnerTransactionnel(userId, requete, appareil));
  }

  private static void validerAbonnementComplet(final RequeteAbonnement requete) {
    if (requete.endpoint() == null
        || requete.keys() == null
        || requete.keys().p256dh() == null
        || requete.keys().auth() == null) {
      throw new ErreurMetier("Abonnement incomplet.");
    }
  }

  private static void validerTailleAbonnement(final RequeteAbonnement requete) {
    if (requete.endpoint().length() > 2048
        || requete.keys().p256dh().length() > 256
        || requete.keys().auth().length() > 256) {
      throw new ErreurMetier("Données d'abonnement invalides.");
    }
    if (!EndpointPush.estAutorise(requete.endpoint())) {
      throw new ErreurMetier("Service de notification du navigateur non reconnu.");
    }
  }

  /**
   * Partie transactionnelle et isolée de {@link #abonner} ({@code
   * REQUIRES_NEW} : transaction dédiée même depuis une transaction ouverte).
   * @param userId identifiant de l'utilisateur
   * @param requete données de l'abonnement (déjà validées)
   * @param appareil navigateur et système, null si inconnus
   * @throws ErreurMetier 429 si le cap est atteint
   */
  @Transactional(isolation = Isolation.SERIALIZABLE, propagation = Propagation.REQUIRES_NEW)
  /* package-private : requis pour l'auto-invocation via le proxy self
   * (@Transactional/SERIALIZABLE). */
  void abonnerTransactionnel(
      final String userId, final RequeteAbonnement requete, final String appareil) {
    final int total =
        Objects.requireNonNullElse(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM push_abonnements WHERE user_id = :userId",
                Map.of(CLE_USER_ID, userId),
                Integer.class),
            0);
    if (total >= CAP_ABONNEMENTS) {
      throw new ErreurMetier("Trop d'abonnements push pour cet utilisateur.", 429);
    }

    jdbc.update(
        """
        INSERT INTO push_abonnements (user_id, endpoint, p256dh, auth, appareil)
        VALUES (:userId, :endpoint, :p256dh, :auth, :appareil)
        ON CONFLICT (user_id, endpoint)
        DO UPDATE SET appareil = COALESCE(EXCLUDED.appareil, push_abonnements.appareil)
        """,
        new MapSqlParameterSource()
            .addValue(CLE_USER_ID, userId)
            .addValue("endpoint", requete.endpoint())
            .addValue("p256dh", requete.keys().p256dh())
            .addValue("auth", requete.keys().auth())
            .addValue("appareil", appareil));
    jdbc.update(EchecPush.SQL_EFFACER_RETRAIT, Map.of(CLE_USER_ID, userId));

    logAdminService.logAdmin("info", "push", "abonnement push créé", Map.of(CLE_USER_ID, userId));
  }

  /**
   * Supprime un abonnement push.
   *
   * @param userId identifiant de l'utilisateur
   * @param endpoint URL de l'endpoint push à supprimer
   */
  public void desabonner(final String userId, final String endpoint) {
    jdbc.update(
        "DELETE FROM push_abonnements WHERE user_id = :userId AND endpoint = :endpoint",
        Map.of(CLE_USER_ID, userId, "endpoint", endpoint));
    // Retrait voulu par l'utilisateur : ses choix push tombent sans avertissement.
    jdbc.update(EchecPush.SQL_DECOCHER_PUSH_SANS_APPAREIL, Map.of(CLE_USER_ID, userId));
    jdbc.update(EchecPush.SQL_EFFACER_RETRAIT, Map.of(CLE_USER_ID, userId));
    logAdminService.logAdmin(
        "info", "push", "abonnement push révoqué", Map.of(CLE_USER_ID, userId));
  }

  /**
   * Liste les abonnements push de l'utilisateur, pour affichage dans
   * /parametres.
   *
   * @param userId identifiant de l'utilisateur
   * @return les abonnements actifs, le plus récent en premier
   */
  public List<AbonnementDto> listerAbonnements(final String userId) {
    return jdbc.query(
        "SELECT endpoint, cree_le, appareil, dernier_echec_le, dernier_echec_statut"
            + " FROM push_abonnements WHERE user_id = :userId ORDER BY cree_le DESC",
        Map.of(CLE_USER_ID, userId),
        (rs, _) -> {
          final OffsetDateTime dernierEchec =
              rs.getObject("dernier_echec_le", OffsetDateTime.class);
          final int statut = rs.getInt("dernier_echec_statut");
          return new AbonnementDto(
              rs.getString("endpoint"),
              rs.getObject("cree_le", OffsetDateTime.class).toInstant(),
              rs.getString("appareil"),
              dernierEchec != null ? dernierEchec.toInstant() : null,
              dernierEchec == null ? null : EchecPush.causeLisible(rs.wasNull() ? null : statut));
        });
  }

  /**
   * Abonnement push exposé côté client ; {@code appareil} est null si inconnu,
   * {@code enEchecDepuis} et {@code cause} (dernier refus) tant que les envois
   * aboutissent.
   * @param endpoint URL de l'endpoint push (identifiant de révocation)
   * @param creeLe date de création
   */
  public record AbonnementDto(
      String endpoint, Instant creeLe, String appareil, Instant enEchecDepuis, String cause) {}
}

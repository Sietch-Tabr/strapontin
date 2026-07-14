/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/// Service de gestion des abonnements Web Push.
///
/// <p>Limite à 10 abonnements par utilisateur. Vérifie la clé VAPID avant d'abonner.
///
/// @since 2.1.0
/// @author Kwaite
@Service
public class PushService {

  private static final Logger log = LoggerFactory.getLogger(PushService.class);
  private static final int CAP_ABONNEMENTS = 10;
  private static final int MAX_TENTATIVES_ABONNEMENT = 3;
  private static final String CLE_USER_ID = "userId";

  private final ConfigService configService;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;
  private PushService self;

  /// @param configService   lecture de la clé VAPID publique
  /// @param jdbc            accès JDBC
  /// @param logAdminService service de logs admin
  public PushService(
      ConfigService configService,
      NamedParameterJdbcTemplate jdbc,
      LogAdminService logAdminService) {
    this.configService = configService;
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /// Injection par setter (plutôt que constructeur) de la référence proxifiée de ce bean, pour
  /// éviter le problème d'auto-référence à la construction — l'appel à
  /// {@link #abonnerTransactionnel} passe ainsi par le proxy AOP Spring et bénéficie de
  /// l'isolation {@code SERIALIZABLE} (même pattern que {@code AdminService}, v2.6.3). En dehors
  /// d'un contexte Spring (tests unitaires), {@code self} peut être positionné manuellement.
  ///
  /// @param self référence proxifiée de ce bean ({@code @Lazy})
  @Autowired
  public void setSelf(@Lazy PushService self) {
    this.self = self;
  }

  /// Retourne la clé VAPID publique si configurée.
  ///
  /// @return réponse avec clé ou {@code disponible: false}
  public ReponseVapid obtenirCleVapid() {
    String clePublique = configService.lireConfig("VAPID_PUBLIC_KEY").orElse(null);
    return clePublique != null
        ? new ReponseVapid(true, clePublique)
        : new ReponseVapid(false, null);
  }

  /// Enregistre un nouvel abonnement push.
  ///
  /// <p>Le COUNT+INSERT est rendu atomique via une transaction {@code SERIALIZABLE} avec retry
  /// (#827, même pattern que {@code AdminService.initierRevocationAdmin}) — sans cela, deux
  /// abonnements concurrents lisant tous deux {@code count = 9 < 10} avant qu'aucun des deux
  /// {@code INSERT} ne soit committé pouvaient tous deux réussir, dépassant le cap documenté
  /// comme une limite dure.
  ///
  /// @param userId  identifiant de l'utilisateur
  /// @param requete données de l'abonnement
  /// @throws ErreurMetier 400 si données incomplètes/invalides, 429 si cap atteint
  public void abonner(String userId, RequeteAbonnement requete) {
    if (requete.endpoint() == null
        || requete.keys() == null
        || requete.keys().p256dh() == null
        || requete.keys().auth() == null) {
      throw new ErreurMetier("Abonnement incomplet.");
    }
    if (requete.endpoint().length() > 2048
        || requete.keys().p256dh().length() > 256
        || requete.keys().auth().length() > 256) {
      throw new ErreurMetier("Données d'abonnement invalides.");
    }

    for (int tentative = 1; ; tentative++) {
      try {
        self.abonnerTransactionnel(userId, requete);
        return;
      } catch (PessimisticLockingFailureException e) {
        if (tentative >= MAX_TENTATIVES_ABONNEMENT) throw e;
        log.warn(
            "Conflit de sérialisation lors de l'abonnement push — nouvelle tentative ({}/{})",
            tentative,
            MAX_TENTATIVES_ABONNEMENT);
      }
    }
  }

  /// Partie transactionnelle et isolée de {@link #abonner}. {@code REQUIRES_NEW} garantit une
  /// transaction dédiée même si appelée depuis une transaction déjà ouverte.
  ///
  /// @param userId  identifiant de l'utilisateur
  /// @param requete données de l'abonnement (déjà validées)
  /// @throws ErreurMetier 429 si le cap est atteint
  @Transactional(isolation = Isolation.SERIALIZABLE, propagation = Propagation.REQUIRES_NEW)
  void abonnerTransactionnel(String userId, RequeteAbonnement requete) {
    int total =
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
        INSERT INTO push_abonnements (user_id, endpoint, p256dh, auth)
        VALUES (:userId, :endpoint, :p256dh, :auth)
        ON CONFLICT (user_id, endpoint) DO NOTHING
        """,
        Map.of(
            CLE_USER_ID,
            userId,
            "endpoint",
            requete.endpoint(),
            "p256dh",
            requete.keys().p256dh(),
            "auth",
            requete.keys().auth()));

    logAdminService.logAdmin("info", "push", "abonnement push créé", Map.of(CLE_USER_ID, userId));
  }

  /// Supprime un abonnement push.
  ///
  /// @param userId   identifiant de l'utilisateur
  /// @param endpoint URL de l'endpoint push à supprimer
  public void desabonner(String userId, String endpoint) {
    jdbc.update(
        "DELETE FROM push_abonnements WHERE user_id = :userId AND endpoint = :endpoint",
        Map.of(CLE_USER_ID, userId, "endpoint", endpoint));
    logAdminService.logAdmin(
        "info", "push", "abonnement push révoqué", Map.of(CLE_USER_ID, userId));
  }
}

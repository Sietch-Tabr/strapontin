/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.web.VapidKeyGenerator;
import fr.strapontin.web.config.ConfigService;
import java.security.GeneralSecurityException;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Service de gestion des clés VAPID pour les notifications push PWA.
 *
 * <p>Génère une paire de clés EC P-256 (secp256r1) encodées en Base64 URL-safe, les persiste dans
 * {@code app_config} et invalide tous les abonnements push existants.
 *
 * @since 2.2.0
 * @author Kwaite
 */
@Service
public class VapidService {

  private final ConfigService configService;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  /**
   * @param configService lecture/écriture de la configuration applicative
   * @param jdbc accès JDBC pour supprimer les abonnements push
   * @param logAdminService audit des actions admin
   */
  public VapidService(
      final ConfigService configService,
      final NamedParameterJdbcTemplate jdbc,
      final LogAdminService logAdminService) {
    this.configService = configService;
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /**
   * Génère une paire de clés VAPID EC P-256, les persiste et supprime les
   * abonnements push.
   *
   * @return paire de clés encodées en Base64 URL-safe et nombre d'abonnements
   *     supprimés
   * @throws GeneralSecurityException si la génération de clés échoue
   */
  public ResultatVapid genererVapid() throws GeneralSecurityException {
    final VapidKeyGenerator.PaireVapid paire = VapidKeyGenerator.genererPaire();

    configService.sauvegarderConfig("VAPID_PUBLIC_KEY", paire.clePublique());
    configService.sauvegarderConfig("VAPID_PRIVATE_KEY", paire.clePrivee());

    final int nbSupprimes = jdbc.update("DELETE FROM push_abonnements", Map.of());
    logAdminService.logAdmin(
        "warn",
        "admin",
        "Clés VAPID régénérées — " + nbSupprimes + " abonnement(s) supprimé(s)",
        Map.of("nbAbonnements", nbSupprimes));

    return new ResultatVapid(paire.clePublique(), nbSupprimes);
  }

  /**
   * Résultat de la génération des clés VAPID.
   *
   * @param clePublique clé publique EC P-256 en Base64 URL-safe
   * @param nbAbonnementsSupprimes nombre d'abonnements push invalidés
   */
  public record ResultatVapid(String clePublique, int nbAbonnementsSupprimes) {}
}

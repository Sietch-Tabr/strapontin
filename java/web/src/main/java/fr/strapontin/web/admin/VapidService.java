/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.VapidKeyGenerator;
import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailServiceSecurite;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Service de gestion des clés VAPID pour les notifications push PWA —
 * génère une paire EC P-256 (secp256r1) en Base64 URL-safe, les
 * persiste dans {@code app_config} et invalide les abonnements push.
 * @since 2.2.0
 * @author Kwaite
 */
@Service
public class VapidService {

  private final ConfigService configService;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;
  private final EmailServiceSecurite emailServiceSecurite;

  /**
   * @param configService lecture/écriture de la configuration applicative
   * @param jdbc accès JDBC pour supprimer les abonnements push
   * @param logAdminService audit des actions admin
   * @param emailServiceSecurite information des comptes qui perdent leurs appareils
   */
  public VapidService(
      final ConfigService configService,
      final NamedParameterJdbcTemplate jdbc,
      final LogAdminService logAdminService,
      final EmailServiceSecurite emailServiceSecurite) {
    this.configService = configService;
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
    this.emailServiceSecurite = emailServiceSecurite;
  }

  /**
   * Génère une nouvelle paire de clés VAPID EC P-256, les persiste, retire
   * tous les abonnements push et prévient par email chaque compte concerné.
   * @param motif raison du renouvellement (obligatoire, donnée aux comptes)
   * @return clé publique, abonnements supprimés, comptes prévenus ou non
   * @throws GeneralSecurityException si la génération de clés échoue
   * @throws ErreurMetier si le motif est vide
   */
  public ResultatVapid genererVapid(final String motif) throws GeneralSecurityException {
    if (motif == null || motif.isBlank()) {
      throw new ErreurMetier("Le motif du renouvellement est obligatoire.");
    }
    final String motifPropre = motif.strip();
    final Map<String, String> comptes = comptesAvecAppareil();
    final VapidKeyGenerator.PaireVapid paire = VapidKeyGenerator.genererPaire();

    configService.sauvegarderConfig("VAPID_PUBLIC_KEY", paire.clePublique());
    configService.sauvegarderConfig("VAPID_PRIVATE_KEY", paire.clePrivee());

    // La cause est celle de ce renouvellement, jamais celle d'un retrait antérieur.
    jdbc.update(
        "UPDATE user_config SET dernier_appareil_retire_le = now(),"
            + " cause_dernier_retrait_appareil = :cause"
            + " WHERE user_id IN (SELECT user_id FROM push_abonnements)",
        Map.of("cause", causeRenouvellement(motifPropre)));
    final int nbSupprimes = jdbc.update("DELETE FROM push_abonnements", Map.of());

    final List<String> prevenus =
        emailServiceSecurite.envoyerPushReinitialise(List.copyOf(comptes.values()), motifPropre);
    final List<String> idsNonPrevenus = new ArrayList<>();
    final List<String> idsPrevenus = new ArrayList<>();
    comptes.forEach(
        (userId, email) -> (prevenus.contains(email) ? idsPrevenus : idsNonPrevenus).add(userId));
    // Prévenus par ce message : l'avertissement générique de canal perdu ferait
    // doublon.
    if (!idsPrevenus.isEmpty()) {
      jdbc.update(
          "UPDATE user_config SET avertissement_canal_perdu_le = now()"
              + " WHERE user_id IN (:ids)",
          Map.of("ids", idsPrevenus));
    }
    logAdminService.logAdmin(
        "warn",
        "admin",
        "Clés VAPID régénérées — "
            + nbSupprimes
            + " abonnement(s) supprimé(s), "
            + prevenus.size()
            + "/"
            + comptes.size()
            + " compte(s) prévenu(s) — motif : "
            + motifPropre,
        Map.of(
            "nbAbonnements", nbSupprimes,
            "motif", motifPropre,
            "comptesPrevenus", idsPrevenus.size(),
            "comptesNonPrevenus", idsNonPrevenus));

    return new ResultatVapid(
        paire.clePublique(), nbSupprimes, idsPrevenus.size(), idsNonPrevenus.size());
  }

  private Map<String, String> comptesAvecAppareil() {
    final Map<String, String> comptes = new LinkedHashMap<>();
    jdbc.query(
        "SELECT DISTINCT u.id, u.email FROM push_abonnements pa JOIN users u ON u.id = pa.user_id"
            + " WHERE u.email IS NOT NULL ORDER BY u.id",
        Map.of(),
        rs -> {
          comptes.put(rs.getString("id"), rs.getString("email"));
        });
    return comptes;
  }

  /**
   * @param motif raison du renouvellement
   * @return la cause lisible enregistrée sur chaque compte qui perd ses appareils
   */
  /* package-private : partagé avec les tests (même package). */
  static String causeRenouvellement(final String motif) {
    return "les clés de notification de l'application ont été renouvelées (" + motif + ")";
  }

  /**
   * Résultat de la génération des clés VAPID : clé publique EC P-256 en
   * Base64 URL-safe, abonnements invalidés, comptes dont l'email est parti ou
   * non (quota atteint, envoi en échec).
   * @param clePublique clé publique
   */
  public record ResultatVapid(
      String clePublique,
      int nbAbonnementsSupprimes,
      int nbComptesPrevenus,
      int nbComptesNonPrevenus) {}
}

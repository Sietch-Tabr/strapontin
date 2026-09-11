/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.setup;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.RessourceDejaExistante;
import fr.strapontin.web.VapidKeyGenerator;
import fr.strapontin.web.config.ConfigService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de configuration initiale de l'application.
 *
 * <p>Crée le premier administrateur et initialise les clés VAPID. L'opération {@link
 * #initialiserTransactionnel} est atomique et isolée ({@code @Transactional(isolation =
 * SERIALIZABLE)}) : sans cela, deux appels concurrents à {@code POST /api/setup} passent tous les
 * deux la garde {@link #estDejaConfiguree} avant que l'un ou l'autre ne committe, créant deux
 * comptes admin distincts.
 *
 * @since 2.1.0
 * @author Kwaite
 */
@Service
public class SetupService {

  private static final Logger log = LoggerFactory.getLogger(SetupService.class);
  private static final int MAX_TENTATIVES = 3;

  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigService configService;
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
  private SetupService self;

  /**
   * @param jdbc accès JDBC
   * @param configService lecture/écriture de la configuration applicative
   */
  public SetupService(NamedParameterJdbcTemplate jdbc, ConfigService configService) {
    this.jdbc = jdbc;
    this.configService = configService;
  }

  /**
   * Injection par setter (plutôt que constructeur) de la référence proxifiée de ce bean, pour
   * éviter le problème d'auto-référence à la construction — l'appel à {@link
   * #initialiserTransactionnel} passe ainsi par le proxy AOP Spring et bénéficie de l'isolation
   * {@code SERIALIZABLE}. En dehors d'un contexte Spring (tests unitaires), {@code self} peut être
   * positionné manuellement sur l'instance elle-même.
   *
   * @param self référence proxifiée de ce bean ({@code @Lazy})
   */
  @Autowired
  public void setSelf(@Lazy SetupService self) {
    this.self = self;
  }

  /**
   * Vérifie si l'application est déjà configurée.
   *
   * @return {@code true} si au moins un utilisateur existe
   */
  public boolean estDejaConfiguree() {
    Integer nb = jdbc.queryForObject("SELECT COUNT(*) FROM users", Map.of(), Integer.class);
    return nb != null && nb > 0;
  }

  /**
   * Crée le premier administrateur et initialise la configuration.
   *
   * <p>Réessaie jusqu'à {@value MAX_TENTATIVES} fois en cas d'échec de sérialisation (deux appels
   * concurrents détectés par PostgreSQL) — seul le perdant de la course réessaie et échoue alors
   * normalement sur {@link RessourceDejaExistante} puisque l'autre a déjà committé.
   *
   * @param requete données d'initialisation
   * @throws RessourceDejaExistante si l'application est déjà configurée
   * @throws ErreurMetier si les données sont invalides
   */
  public void initialiser(RequeteSetup requete) {
    for (int tentative = 1; ; tentative++) {
      try {
        self.initialiserTransactionnel(requete);
        return;
      } catch (PessimisticLockingFailureException e) {
        if (tentative >= MAX_TENTATIVES) throw e;
        log.warn(
            "Conflit de sérialisation lors du setup — nouvelle tentative ({}/{})",
            tentative,
            MAX_TENTATIVES);
      }
    }
  }

  /**
   * Partie transactionnelle et isolée de {@link #initialiser}.
   *
   * @param requete données d'initialisation
   * @throws RessourceDejaExistante si l'application est déjà configurée
   * @throws ErreurMetier si les données sont invalides
   */
  @Transactional(isolation = Isolation.SERIALIZABLE)
  void initialiserTransactionnel(RequeteSetup requete) {
    if (estDejaConfiguree()) {
      throw new RessourceDejaExistante("L'application est déjà configurée.");
    }

    String identifiant =
        requete.identifiant() == null ? "" : requete.identifiant().strip().toLowerCase();
    String email = requete.email() == null ? "" : requete.email().strip().toLowerCase();
    String motDePasse = requete.motDePasse() == null ? "" : requete.motDePasse();

    if (identifiant.length() < 3 || !identifiant.matches("[a-z0-9._-]+")) {
      throw new ErreurMetier(
          "L'identifiant doit contenir au moins 3 caractères"
              + " (lettres minuscules, chiffres, points, tirets, _).");
    }
    if (email.isEmpty() || !email.contains("@"))
      throw new ErreurMetier("L'adresse email est invalide.");
    if (motDePasse.length() < 12)
      throw new ErreurMetier("Le mot de passe doit contenir au moins 12 caractères.");

    String userId = UUID.randomUUID().toString();
    String hashMdp = bcrypt.encode(motDePasse);

    Map<String, Object> paramsUser = new HashMap<>();
    paramsUser.put("id", userId);
    paramsUser.put("email", email);
    paramsUser.put("username", identifiant);
    paramsUser.put("displayUsername", identifiant);
    try {
      jdbc.update(
          """
          INSERT INTO users (id, email, email_verified, role, username, display_username,
                             must_change_credentials, created_at, updated_at)
          VALUES (:id, :email, true, 'admin', :username, :displayUsername,
                  false, now(), now())
          """,
          paramsUser);
    } catch (DataIntegrityViolationException e) {
      throw new RessourceDejaExistante("L'application est déjà configurée.");
    }

    String accountId = UUID.randomUUID().toString();
    jdbc.update(
        """
        INSERT INTO accounts (id, account_id, provider_id, user_id, password, created_at, updated_at)
        VALUES (:id, :accountId, 'credential', :userId, :password, now(), now())
        """,
        Map.of("id", accountId, "accountId", userId, "userId", userId, "password", hashMdp));

    String baseUrl = requete.baseUrl() == null ? "" : requete.baseUrl().strip();
    String contactEmail = requete.contactEmail() == null ? "" : requete.contactEmail().strip();
    if (contactEmail.isEmpty()) contactEmail = email;
    if (!baseUrl.isEmpty()) configService.sauvegarderConfig("PUBLIC_BASE_URL", baseUrl);
    configService.sauvegarderConfig("CONTACT_EMAIL", contactEmail);

    genererVapid();
    log.info("Premier administrateur créé : {}", identifiant);
  }

  private void genererVapid() {
    try {
      VapidKeyGenerator.PaireVapid paire = VapidKeyGenerator.genererPaire();
      configService.sauvegarderConfig("VAPID_PUBLIC_KEY", paire.clePublique());
      configService.sauvegarderConfig("VAPID_PRIVATE_KEY", paire.clePrivee());
    } catch (Exception e) {
      log.warn("Impossible de générer les clés VAPID lors du setup", e);
    }
  }
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.setup;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.RessourceDejaExistante;
import fr.strapontin.web.VapidKeyGenerator;
import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.securite.RegleIdentifiant;
import java.util.HashMap;
import java.util.Locale;
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
 * Service de configuration initiale — crée le premier administrateur et
 * initialise les clés VAPID. {@link #initialiserTransactionnel} est
 * atomique et isolée ({@code SERIALIZABLE}), sans quoi deux appels
 * concurrents passent tous deux la garde {@link #estDejaConfiguree}.
 * @since 2.1.0
 * @author Kwaite
 */
@Service
public class SetupService {

  private static final Logger LOG = LoggerFactory.getLogger(SetupService.class);
  private static final int MAX_TENTATIVES = 3;
  private static final int LONGUEUR_MIN_MOT_DE_PASSE = 12;

  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigService configService;
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
  private SetupService self;

  /**
   * @param jdbc accès JDBC
   * @param configService lecture/écriture de la configuration applicative
   */
  public SetupService(final NamedParameterJdbcTemplate jdbc, final ConfigService configService) {
    this.jdbc = jdbc;
    this.configService = configService;
  }

  /**
   * Injection par setter (plutôt que constructeur) de la référence
   * proxifiée de ce bean, pour éviter l'auto-référence à la construction —
   * l'appel à {@link #initialiserTransactionnel} passe ainsi par le proxy
   * AOP Spring (isolation {@code SERIALIZABLE}). Hors contexte Spring
   * (tests), positionnable à la main.
   * @param self référence proxifiée de ce bean ({@code @Lazy})
   */
  @Autowired
  public void setSelf(@Lazy final SetupService self) {
    this.self = self;
  }

  /**
   * Vérifie si l'application est déjà configurée.
   *
   * @return {@code true} si au moins un utilisateur existe
   */
  public boolean estDejaConfiguree() {
    final Integer nb = jdbc.queryForObject("SELECT COUNT(*) FROM users", Map.of(), Integer.class);
    return nb != null && nb > 0;
  }

  /**
   * Crée le premier administrateur — réessaie jusqu'à {@value
   * MAX_TENTATIVES} fois en cas d'échec de sérialisation entre appels
   * concurrents.
   * @param requete données d'initialisation
   * @throws RessourceDejaExistante si l'application est déjà configurée
   * @throws ErreurMetier si les données sont invalides
   */
  public void initialiser(final RequeteSetup requete) {
    for (int tentative = 1; ; tentative++) {
      try {
        self.initialiserTransactionnel(requete);
        return;
      } catch (PessimisticLockingFailureException e) {
        if (tentative >= MAX_TENTATIVES) {
          throw e;
        }
        LOG.warn(
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
  /* package-private : requis pour l'auto-invocation via le proxy self
   * (@Transactional/SERIALIZABLE). */
  void initialiserTransactionnel(final RequeteSetup requete) {
    if (estDejaConfiguree()) {
      throw new RessourceDejaExistante("L'application est déjà configurée.");
    }

    final String identifiant = RegleIdentifiant.valider(requete.identifiant());
    final String email =
        requete.email() == null ? "" : requete.email().strip().toLowerCase(Locale.ROOT);
    final String motDePasse = requete.motDePasse() == null ? "" : requete.motDePasse();
    validerRequeteSetup(email, motDePasse);

    final String userId = UUID.randomUUID().toString();
    creerCompteAdmin(userId, email, identifiant);
    creerCredentials(userId, bcrypt.encode(motDePasse));
    configurerContactEtBaseUrl(requete, email);

    genererVapid();
    LOG.info("Premier administrateur créé : {}", identifiant);
  }

  private static void validerRequeteSetup(final String email, final String motDePasse) {
    if (email.isEmpty() || !email.contains("@")) {
      throw new ErreurMetier("L'adresse email est invalide.");
    }
    if (motDePasse.length() < LONGUEUR_MIN_MOT_DE_PASSE) {
      throw new ErreurMetier(
          "Le mot de passe doit contenir au moins " + LONGUEUR_MIN_MOT_DE_PASSE + " caractères.");
    }
  }

  private void creerCompteAdmin(final String userId, final String email, final String identifiant) {
    final Map<String, Object> paramsUser = new HashMap<>();
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
      jdbc.update(
          "INSERT INTO user_config (user_id) VALUES (:id) ON CONFLICT DO NOTHING",
          Map.of("id", userId));
    } catch (DataIntegrityViolationException e) {
      final RessourceDejaExistante erreur =
          new RessourceDejaExistante("L'application est déjà configurée.");
      erreur.initCause(e);
      throw erreur;
    }
  }

  private void creerCredentials(final String userId, final String hashMdp) {
    final String accountId = UUID.randomUUID().toString();
    jdbc.update(
        """
        INSERT INTO accounts (id, account_id, provider_id, user_id, password, created_at, updated_at)
        VALUES (:id, :accountId, 'credential', :userId, :password, now(), now())
        """,
        Map.of("id", accountId, "accountId", userId, "userId", userId, "password", hashMdp));
  }

  private void configurerContactEtBaseUrl(final RequeteSetup requete, final String email) {
    final String baseUrl = requete.baseUrl() == null ? "" : requete.baseUrl().strip();
    String contactEmail = requete.contactEmail() == null ? "" : requete.contactEmail().strip();
    if (contactEmail.isEmpty()) {
      contactEmail = email;
    }
    if (!baseUrl.isEmpty()) {
      configService.sauvegarderConfig("PUBLIC_BASE_URL", baseUrl);
    }
    configService.sauvegarderConfig("CONTACT_EMAIL", contactEmail);
  }

  private void genererVapid() {
    try {
      final VapidKeyGenerator.PaireVapid paire = VapidKeyGenerator.genererPaire();
      configService.sauvegarderConfig("VAPID_PUBLIC_KEY", paire.clePublique());
      configService.sauvegarderConfig("VAPID_PRIVATE_KEY", paire.clePrivee());
    } catch (Exception e) {
      LOG.warn("Impossible de générer les clés VAPID lors du setup", e);
    }
  }
}

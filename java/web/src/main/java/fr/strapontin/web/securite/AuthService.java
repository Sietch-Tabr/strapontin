/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.email.EmailService;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service d'authentification et de réinitialisation de mot de passe.
 *
 * <p>Orchestration atomique des flux mot de passe oublié et réinitialisation, via {@link
 * VerificationRepository} et {@link EmailService}.
 *
 * @since 2.1.0
 * @author Kwaite
 */
@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  private static final SecureRandom rng = new SecureRandom();

  private final VerificationRepository verificationRepository;
  private final EmailService emailService;
  private final PasswordEncoder passwordEncoder;
  private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

  /**
   * @param verificationRepository accès aux tokens de vérification
   * @param emailService service d'envoi d'emails
   * @param passwordEncoder encodeur bcrypt
   * @param sessionRepository dépôt de sessions JDBC (révocation après reset de mdp)
   */
  public AuthService(
      final VerificationRepository verificationRepository,
      final EmailService emailService,
      final PasswordEncoder passwordEncoder,
      final FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
    this.verificationRepository = verificationRepository;
    this.emailService = emailService;
    this.passwordEncoder = passwordEncoder;
    this.sessionRepository = sessionRepository;
  }

  /**
   * Déclenche un email de réinitialisation de mot de passe.
   *
   * <p>Ne fait rien (sans erreur) si l'email est inconnu — prévention de l'énumération de comptes.
   *
   * @param email adresse email (déjà normalisée)
   * @param baseUrl URL de base de l'application pour construire le lien de reset
   */
  @Transactional
  public void motDePasseOublie(final String email, final String baseUrl) {
    if (!verificationRepository.utilisateurExistePourEmail(email)) {
      return;
    }

    final String prefixe = "reset:" + email;
    verificationRepository.supprimerParIdentifiant(prefixe);

    final String token = genererToken(32);
    final String tokenId = UUID.randomUUID().toString();
    final Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);

    verificationRepository.inserer(tokenId, prefixe, GenerateurSecrets.sha256Hex(token), expiresAt);

    final String lienReset = baseUrl + "/reset-password?token=" + token;
    emailService.envoyerResetMotDePasse(email, lienReset);
    log.debug("Email reset MDP envoyé pour email hashé {}", email.hashCode());
  }

  /**
   * Indique si un token est de type invitation, sans le consommer.
   *
   * @param token token brut (non hashé)
   * @return {@code true} si le token est de type {@code invite:}
   */
  public boolean estTokenInvite(final String token) {
    return verificationRepository
        .lireIdentifiantParTokenHash(GenerateurSecrets.sha256Hex(token))
        .map(s -> s.startsWith("invite:"))
        .orElse(false);
  }

  /**
   * Active un compte invité : définit identifiant et mot de passe en une seule opération.
   *
   * @param token token brut (non hashé), doit être de type {@code invite:}
   * @param identifiant identifiant choisi (déjà normalisé en minuscules, format validé)
   * @param motDePasse mot de passe en clair (longueur validée)
   * @return l'identifiant normalisé, pour l'auto-login en aval
   * @throws ErreurMetier si le token est invalide, expiré, non-invite ou l'identifiant pris
   */
  @Transactional
  public String activerCompteInvite(final String token, final String identifiant, final String motDePasse) {
    final String tokenHash = GenerateurSecrets.sha256Hex(token);
    final String fullIdentifiant =
        verificationRepository
            .lireIdentifiantParTokenHash(tokenHash)
            .orElseThrow(
                () ->
                    new ErreurMetier("Lien invalide ou expiré. Recommencez depuis le formulaire."));

    if (!fullIdentifiant.startsWith("invite:")) {
      throw new ErreurMetier("Ce lien n'est pas un lien d'invitation.");
    }

    final String email = fullIdentifiant.split(":", 2)[1];

    if (verificationRepository.identifiantDejaUtiliseParEmail(identifiant, email)) {
      throw new ErreurMetier("Cet identifiant est déjà utilisé.");
    }

    log.info("Activation compte invitation : emailHash={}", email.hashCode());
    verificationRepository.majMotDePasseParEmail(email, passwordEncoder.encode(motDePasse));
    try {
      verificationRepository.majUsernameEtActiverParEmail(email, identifiant);
    } catch (DataIntegrityViolationException e) {
      // Race entre le contrôle ci-dessus et cet UPDATE : deux invitations concurrentes ont
      // pu choisir le même identifiant entre-temps.
      throw new ErreurMetier("Cet identifiant est déjà utilisé.");
    }
    verificationRepository.supprimerParTokenHash(tokenHash);

    return identifiant;
  }

  /**
   * Réinitialise le mot de passe via un token de vérification.
   *
   * @param token token brut (non hashé)
   * @param motDePasse nouveau mot de passe en clair
   * @throws ErreurMetier si le token est invalide ou expiré
   */
  @Transactional
  public void reinitialiserMotDePasse(final String token, final String motDePasse) {
    final String tokenHash = GenerateurSecrets.sha256Hex(token);
    final String identifiant =
        verificationRepository
            .lireIdentifiantParTokenHash(tokenHash)
            .orElseThrow(
                () ->
                    new ErreurMetier("Lien invalide ou expiré. Recommencez depuis le formulaire."));

    final String email = identifiant.contains(":") ? identifiant.split(":", 2)[1] : identifiant;
    final boolean estInvitation = identifiant.startsWith("invite:");
    log.info(
        "Réinitialisation mot de passe : type={}, emailHash={}",
        estInvitation ? "invite" : "reset",
        email.hashCode());
    verificationRepository.majMotDePasseParEmail(email, passwordEncoder.encode(motDePasse));
    if (!estInvitation) {
      verificationRepository.majMustChangeFalseParEmail(email);
    }
    verificationRepository.supprimerParTokenHash(tokenHash);
    revoquerSessionsUtilisateur(email);
  }

  private void revoquerSessionsUtilisateur(final String email) {
    verificationRepository
        .lireUsernameParEmail(email)
        .ifPresent(
            username ->
                sessionRepository
                    .findByPrincipalName(username)
                    .keySet()
                    .forEach(sessionRepository::deleteById));
  }

  private static String genererToken(final int nbOctets) {
    final byte[] octets = new byte[nbOctets];
    rng.nextBytes(octets);
    final StringBuilder sb = new StringBuilder();
    for (byte b : octets) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}

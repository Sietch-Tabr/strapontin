/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.email.EmailService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/// Service d'authentification et de réinitialisation de mot de passe.
///
/// <p>Orchestration atomique des flux mot de passe oublié et réinitialisation,
/// via {@link VerificationRepository} et {@link EmailService}.
///
/// @since 2.1.0
/// @author Kwaite
@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  private static final SecureRandom rng = new SecureRandom();

  private final VerificationRepository verificationRepository;
  private final EmailService emailService;
  private final PasswordEncoder passwordEncoder;

  /// @param verificationRepository accès aux tokens de vérification
  /// @param emailService           service d'envoi d'emails
  /// @param passwordEncoder        encodeur bcrypt
  public AuthService(
      VerificationRepository verificationRepository,
      EmailService emailService,
      PasswordEncoder passwordEncoder) {
    this.verificationRepository = verificationRepository;
    this.emailService = emailService;
    this.passwordEncoder = passwordEncoder;
  }

  /// Déclenche un email de réinitialisation de mot de passe.
  ///
  /// <p>Ne fait rien (sans erreur) si l'email est inconnu — prévention de l'énumération de comptes.
  ///
  /// @param email   adresse email (déjà normalisée)
  /// @param baseUrl URL de base de l'application pour construire le lien de reset
  @Transactional
  public void motDePasseOublie(String email, String baseUrl) {
    if (!verificationRepository.utilisateurExistePourEmail(email)) {
      return;
    }

    String prefixe = "reset:" + email;
    verificationRepository.supprimerParIdentifiant(prefixe);

    String token = genererToken(32);
    String tokenId = UUID.randomUUID().toString();
    Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);

    verificationRepository.inserer(tokenId, prefixe, sha256Hex(token), expiresAt);

    String lienReset = baseUrl + "/reset-password?token=" + token;
    emailService.envoyerResetMotDePasse(email, lienReset);
    log.debug("Email reset MDP envoyé pour email hashé {}", email.hashCode());
  }

  /// Réinitialise le mot de passe via un token de vérification.
  ///
  /// @param token      token brut (non hashé)
  /// @param motDePasse nouveau mot de passe en clair
  /// @throws ErreurMetier si le token est invalide ou expiré
  @Transactional
  public void reinitialiserMotDePasse(String token, String motDePasse) {
    String tokenHash = sha256Hex(token);
    String identifiant =
        verificationRepository
            .lireIdentifiantParTokenHash(tokenHash)
            .orElseThrow(
                () ->
                    new ErreurMetier("Lien invalide ou expiré. Recommencez depuis le formulaire."));

    String email = identifiant.contains(":") ? identifiant.split(":", 2)[1] : identifiant;
    boolean estInvitation = identifiant.startsWith("invite:");
    log.info("Réinitialisation mot de passe : type={}, emailHash={}", estInvitation ? "invite" : "reset", email.hashCode());
    verificationRepository.majMotDePasseParEmail(email, passwordEncoder.encode(motDePasse));
    if (!estInvitation) {
      verificationRepository.majMustChangeFalseParEmail(email);
    }
    verificationRepository.supprimerParTokenHash(tokenHash);
  }

  private static String genererToken(int nbOctets) {
    byte[] octets = new byte[nbOctets];
    rng.nextBytes(octets);
    StringBuilder sb = new StringBuilder();
    for (byte b : octets) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 non disponible", e);
    }
  }
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.invitation;

import fr.strapontin.core.GenerateurSecrets;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.email.EmailService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitations envoyées par un utilisateur : aucun compte n'existe avant l'activation du lien, le
 * nombre d'invitations en attente est plafonné, la cadence d'envoi aussi (envois comptés en base,
 * l'annulation d'une invitation ne les efface pas).
 * @since 3.1.4
 * @author Kwaite
 */
@Service
public class InvitationUtilisateurService {

  private static final String SOURCE_LOG = "invitations";
  private static final String CLE_PARRAIN_ID = "parrainId";
  private static final String CLE_ID = "id";
  private static final String CLE_EMAIL = "email";
  private static final String CLE_USER_ID = "userId";
  private static final String MESSAGE_LIEN_INVALIDE = "Lien invalide ou expiré.";
  private static final Duration VALIDITE = Duration.ofDays(7);

  private final InvitationUtilisateurRepository depot;
  private final ReglagesInvitations reglages;
  private final EmailService emailService;
  private final LogAdminService logAdminService;
  private final NamedParameterJdbcTemplate jdbc;
  private final PasswordEncoder encodeur;

  /**
   * @param depot accès SQL
   * @param reglages plafond et cadence réglables
   * @param emailService envoi de l'invitation
   * @param logAdminService trace des dépassements
   * @param jdbc accès JDBC (création du compte à l'activation)
   * @param encodeur hachage du mot de passe choisi
   */
  public InvitationUtilisateurService(
      final InvitationUtilisateurRepository depot,
      final ReglagesInvitations reglages,
      final EmailService emailService,
      final LogAdminService logAdminService,
      final NamedParameterJdbcTemplate jdbc,
      final PasswordEncoder encodeur) {
    this.depot = depot;
    this.reglages = reglages;
    this.emailService = emailService;
    this.logAdminService = logAdminService;
    this.jdbc = jdbc;
    this.encodeur = encodeur;
  }

  /**
   * État des invitations d'un utilisateur.
   * @param plafond nombre d'invitations en attente autorisé
   * @param enAttente invitations en attente
   * @param restantes invitations qu'il peut encore envoyer
   * @param invitations ses invitations, de la plus récente à la plus ancienne
   */
  public record Etat(int plafond, int enAttente, int restantes, List<InvitationDto> invitations) {}

  /**
   * Une invitation telle que la page Paramètres l'affiche.
   * @param id identifiant
   * @param email adresse invitée
   * @param statut {@code en_attente}, {@code activee}, {@code expiree} ou {@code annulee}
   * @param creeLe date d'envoi
   * @param expireLe date d'expiration du lien
   */
  public record InvitationDto(
      UUID id, String email, String statut, Instant creeLe, Instant expireLe) {}

  /**
   * @param parrainId utilisateur connecté
   * @return son plafond, ses invitations restantes et la liste de ses invitations
   */
  @Transactional(readOnly = true)
  public Etat etat(final String parrainId) {
    final int plafond = plafond(parrainId);
    final int enAttente = depot.compterEnAttente(parrainId);
    final Instant maintenant = Instant.now();
    final List<InvitationDto> invitations =
        depot.lister(parrainId).stream()
            .map(
                l ->
                    new InvitationDto(
                        l.id(), l.email(), l.statut(maintenant), l.creeLe(), l.expireLe()))
            .toList();
    return new Etat(plafond, enAttente, Math.max(0, plafond - enAttente), invitations);
  }

  /**
   * Invite une personne par son adresse. Une adresse déjà inscrite ou déjà invitée reçoit la même
   * réponse qu'un envoi réussi, sans rien envoyer.
   * @param parrainId utilisateur connecté
   * @param emailBrut adresse saisie
   * @param baseUrl URL publique de l'application
   * @throws ErreurMetier plafond atteint (409) ou cadence dépassée (429)
   */
  @Transactional(noRollbackFor = ErreurMetier.class)
  public void inviter(final String parrainId, final String emailBrut, final String baseUrl) {
    final String email = emailBrut.strip().toLowerCase(Locale.ROOT);
    verrouillerEtVerifier(parrainId);
    if (depot.compterEnAttente(parrainId) >= plafond(parrainId)) {
      throw new ErreurMetier(
          "Vous avez atteint le nombre maximal d'invitations en attente. Annulez-en une ou"
              + " attendez qu'elle soit activée ou expire.",
          409);
    }
    depot.enregistrerEnvoi(parrainId);
    if (depot.compteExiste(email) || depot.invitationEnAttentePour(email)) {
      return;
    }
    final String token = GenerateurSecrets.genererToken(32);
    depot.creer(parrainId, email, GenerateurSecrets.sha256Hex(token), Instant.now().plus(VALIDITE));
    emailService.envoyerInvitation(email, baseUrl + "/reset-password?token=" + token);
  }

  /**
   * Renvoie le lien d'une invitation en attente (nouveau lien, nouveau délai de sept jours).
   * @param parrainId utilisateur connecté
   * @param id invitation à renvoyer
   * @param baseUrl URL publique de l'application
   * @throws ErreurMetier invitation introuvable ou plus en attente (404), cadence dépassée (429)
   */
  @Transactional(noRollbackFor = ErreurMetier.class)
  public void renvoyer(final String parrainId, final UUID id, final String baseUrl) {
    verrouillerEtVerifier(parrainId);
    final String email =
        depot
            .emailEnAttente(parrainId, id)
            .orElseThrow(() -> new ErreurMetier("Invitation introuvable.", 404));
    final String token = GenerateurSecrets.genererToken(32);
    depot.renouveler(id, GenerateurSecrets.sha256Hex(token), Instant.now().plus(VALIDITE));
    depot.enregistrerEnvoi(parrainId);
    emailService.envoyerInvitation(email, baseUrl + "/reset-password?token=" + token);
  }

  /**
   * Annule une invitation en attente ; ses envois restent comptés dans la cadence.
   * @param parrainId utilisateur connecté
   * @param id invitation à annuler
   * @throws ErreurMetier invitation introuvable ou plus en attente (404)
   */
  @Transactional
  public void annuler(final String parrainId, final UUID id) {
    if (!depot.annuler(parrainId, id)) {
      throw new ErreurMetier("Invitation introuvable.", 404);
    }
  }

  /**
   * Indique si un lien est celui d'une invitation d'utilisateur en attente.
   * @param token lien brut
   * @return {@code true} si une invitation en attente porte ce lien
   */
  @Transactional(readOnly = true)
  public boolean estLienInvitation(final String token) {
    return depot.trouverEnAttenteParToken(GenerateurSecrets.sha256Hex(token)).isPresent();
  }

  /**
   * Crée le compte invité à l'activation du lien : rôle utilisateur, parrain enregistré.
   * @param token lien brut
   * @param identifiant identifiant choisi, déjà validé
   * @param motDePasse mot de passe choisi
   * @return l'identifiant du nouveau compte, ou vide si le lien n'est pas celui d'une invitation
   *     d'utilisateur
   * @throws ErreurMetier identifiant pris, ou adresse devenue celle d'un compte
   */
  @Transactional
  public Optional<String> activer(
      final String token, final String identifiant, final String motDePasse) {
    final var invitation = depot.trouverEnAttenteParToken(GenerateurSecrets.sha256Hex(token));
    if (invitation.isEmpty()) {
      return Optional.empty();
    }
    final String email = invitation.get().email();
    if (depot.compteExiste(email)) {
      throw new ErreurMetier(MESSAGE_LIEN_INVALIDE);
    }
    final boolean identifiantPris =
        !jdbc.queryForList(
                "SELECT 1 FROM users WHERE lower(username) = :identifiant",
                Map.of("identifiant", identifiant),
                Integer.class)
            .isEmpty();
    if (identifiantPris) {
      throw new ErreurMetier("Cet identifiant est déjà utilisé.");
    }
    final String userId = UUID.randomUUID().toString();
    jdbc.update(
        """
        INSERT INTO users (id, email, email_verified, role, username, display_username,
                           must_change_credentials, parrain_id, created_at, updated_at)
        VALUES (:id, :email, true, 'user', :identifiant, :identifiant, false, :parrain,
                now(), now())
        """,
        Map.of(
            CLE_ID,
            userId,
            CLE_EMAIL,
            email,
            "identifiant",
            identifiant,
            "parrain",
            invitation.get().parrainId()));
    jdbc.update(
        "INSERT INTO user_config (user_id) VALUES (:id) ON CONFLICT DO NOTHING",
        Map.of(CLE_ID, userId));
    jdbc.update(
        """
        INSERT INTO accounts (id, account_id, provider_id, user_id, password, created_at, updated_at)
        VALUES (:id, :accountId, 'credential', :userId, :motDePasse, now(), now())
        """,
        Map.of(
            CLE_ID,
            UUID.randomUUID().toString(),
            "accountId",
            userId,
            CLE_USER_ID,
            userId,
            "motDePasse",
            encodeur.encode(motDePasse)));
    depot.marquerActivee(invitation.get().id());
    return Optional.of(identifiant);
  }

  private void verrouillerEtVerifier(final String parrainId) {
    if (!depot.verrouillerParrain(parrainId)) {
      throw new ErreurMetier("Utilisateur introuvable.", 404);
    }
    final Instant maintenant = Instant.now();
    if (depot.compterEnvoisDepuis(parrainId, maintenant.minus(1, ChronoUnit.HOURS))
        >= reglages.envoisParHeure()) {
      signalerDepassement(parrainId, "heure");
      throw new ErreurMetier(
          "Vous avez envoyé trop d'invitations récemment. Réessayez dans un moment.", 429);
    }
    if (depot.compterTousEnvoisDepuis(maintenant.minus(1, ChronoUnit.DAYS))
        >= reglages.envoisParJour()) {
      signalerDepassement(parrainId, "jour");
      throw new ErreurMetier(
          "Le nombre maximal d'invitations de la journée est atteint. Réessayez demain.", 429);
    }
  }

  // Aucune adresse ni nom dans la trace : l'identifiant du parrain suffit à un administrateur.
  private void signalerDepassement(final String parrainId, final String motif) {
    logAdminService.logAdmin(
        "warn",
        SOURCE_LOG,
        "Limite d'invitations atteinte (" + motif + ")",
        Map.of(CLE_PARRAIN_ID, parrainId, "limite", motif));
  }

  private int plafond(final String parrainId) {
    return depot.plafondPropre(parrainId).orElseGet(reglages::plafondDefaut);
  }
}

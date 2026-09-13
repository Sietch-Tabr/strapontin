/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static fr.strapontin.web.ControllerUtils.erreur;

import fr.strapontin.web.email.EmailServiceAdmin;
import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST d'administration système : configuration applicative, VAPID, bannière, logs, jobs
 * Quartz.
 *
 * <p>Extrait d'{@link AdminController} (qui reste dédié aux comptes utilisateurs) pour rester sous
 * le seuil de couplage entre objets (20). HTTP pur — délègue toute la logique métier.
 *
 * @since 2.9.1
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/admin")
public class SystemeAdminController {

  private static final Logger LOG = LoggerFactory.getLogger(SystemeAdminController.class);
  private static final String CLE_SUCCES = "succes";
  private static final String CLE_ACTION = "action";

  private final AdminService adminService;
  private final ConfigAdminService configAdminService;
  private final JobsAdminService jobsAdminService;
  private final BanniereService banniereService;
  private final WorkerProxyService workerProxyService;
  private final EmailServiceAdmin emailServiceAdmin;
  private final VapidService vapidService;

  /**
   * @param adminService service d'administration (lecture des logs admin)
   * @param configAdminService service de configuration applicative
   * @param jobsAdminService service de statistiques des jobs Quartz
   * @param banniereService service de gestion de la bannière système
   * @param workerProxyService proxy HTTP vers le worker (relance des fetches)
   * @param emailServiceAdmin emails déclenchés par une action d'administration
   * @param vapidService service de gestion des clés VAPID
   */
  public SystemeAdminController(
      final AdminService adminService,
      final ConfigAdminService configAdminService,
      final JobsAdminService jobsAdminService,
      final BanniereService banniereService,
      final WorkerProxyService workerProxyService,
      final EmailServiceAdmin emailServiceAdmin,
      final VapidService vapidService) {
    this.adminService = adminService;
    this.configAdminService = configAdminService;
    this.jobsAdminService = jobsAdminService;
    this.banniereService = banniereService;
    this.workerProxyService = workerProxyService;
    this.emailServiceAdmin = emailServiceAdmin;
    this.vapidService = vapidService;
  }

  /**
   * Retourne le statut des clés de configuration.
   *
   * @return la liste des statuts de configuration
   */
  @GetMapping("/config")
  public ResponseEntity<List<ConfigStatut>> lireConfigStatuts() {
    return ResponseEntity.ok(configAdminService.lireConfigStatuts());
  }

  /**
   * Sauvegarde une valeur de configuration applicative.
   *
   * @param requete corps JSON avec {@code cle} et {@code valeur}
   * @param auth contexte d'authentification admin
   * @return 200 si sauvegardé — 400 si invalide (via
   *     {@link GlobalExceptionHandler} )
   */
  @PatchMapping("/config")
  public ResponseEntity<Map<String, Object>> sauvegarderConfig(
      @Valid @RequestBody final RequeteConfig requete, final Authentication auth) {
    final String adminId = utilisateur(auth).id();
    final String cle = requete.cle() == null ? "" : requete.cle().strip();
    final String valeur = requete.valeur() == null ? "" : requete.valeur().strip();

    configAdminService.sauvegarderConfig(cle, valeur, adminId);
    return ResponseEntity.ok(Map.of(CLE_SUCCES, true, CLE_ACTION, "sauvegarderConfig", "cle", cle));
  }

  /**
   * Génère une nouvelle paire de clés VAPID EC P-256.
   *
   * @return 200 avec la clé publique générée et le nombre d'abonnements push
   *     invalidés
   */
  @PostMapping("/vapid/generer")
  public ResponseEntity<Map<String, Object>> genererVapid() {
    try {
      final VapidService.ResultatVapid resultat = vapidService.genererVapid();
      return ResponseEntity.ok(
          Map.of(
              CLE_SUCCES,
              true,
              CLE_ACTION,
              "genererVapid",
              "clePublique",
              resultat.clePublique(),
              "nbAbonnementsSupprimes",
              resultat.nbAbonnementsSupprimes()));
    } catch (Exception e) {
      LOG.error("Erreur génération clés VAPID", e);
      return erreur(500, "Erreur lors de la génération des clés VAPID.");
    }
  }

  /**
   * Teste la validité de la clé Ticketmaster API via un appel minimal.
   *
   * @return 200 si la clé est valide, 400 sinon
   */
  @PostMapping("/config/tester-tm")
  public ResponseEntity<Map<String, Object>> testerApiTm() {
    final boolean valide = configAdminService.testerApiTm();
    return valide
        ? ResponseEntity.ok(Map.of("valide", true))
        : erreur(400, "Clé Ticketmaster invalide ou absente.");
  }

  /**
   * Envoie un email de test pour vérifier la configuration Resend.
   *
   * @param auth contexte d'authentification admin
   * @return 200 si l'email a été envoyé, 400 sinon
   */
  @PostMapping("/config/tester-email")
  public ResponseEntity<Map<String, Object>> testerEmail(final Authentication auth) {
    final String emailAdmin = utilisateur(auth).email();
    final boolean succes = emailServiceAdmin.envoyerEmailTest(emailAdmin);
    return succes
        ? ResponseEntity.ok(Map.of(CLE_SUCCES, true))
        : erreur(400, "Envoi impossible — vérifiez RESEND_API_KEY et CONTACT_EMAIL.");
  }

  /**
   * Publie un message de bannière système.
   *
   * @param requete corps JSON avec {@code message} et {@code type}
   * @return 200 si publié
   */
  @PostMapping("/banniere")
  public ResponseEntity<Map<String, Object>> publierBanniere(
      @Valid @RequestBody final RequeteBanniere requete) {
    final String type =
        Set.of("info", "warning", "error").contains(requete.type()) ? requete.type() : "info";
    banniereService.publierBanniere(requete.message().strip(), type);
    return ResponseEntity.ok(Map.of(CLE_SUCCES, true, CLE_ACTION, "publierBanniere"));
  }

  /**
   * Efface la bannière système.
   *
   * @return 200 si effacée
   */
  @DeleteMapping("/banniere")
  public ResponseEntity<Map<String, Object>> effacerBanniere() {
    banniereService.effacerBanniere();
    return ResponseEntity.ok(Map.of(CLE_SUCCES, true, CLE_ACTION, "effacerBanniere"));
  }

  /**
   * Retourne les 200 derniers logs admin.
   *
   * @return la liste des entrées de log
   */
  @GetMapping("/logs")
  public ResponseEntity<List<LogAdminService.EntreeLogAdmin>> lireLogs() {
    return ResponseEntity.ok(adminService.lireLogsAdmin());
  }

  /**
   * Retourne les statistiques des jobs Quartz et le quota TM.
   *
   * @return les stats de jobs et le quota TM
   */
  @GetMapping("/jobs")
  public ResponseEntity<Map<String, Object>> lireJobs() {
    return ResponseEntity.ok(jobsAdminService.lireJobs());
  }

  /**
   * Déclenche immédiatement les fetches via le worker.
   *
   * @return 200 si déclenché, 502 en cas d'erreur interne worker, 503 si
   *     injoignable
   */
  @PostMapping("/relancer")
  public ResponseEntity<Map<String, Object>> relancerFetches() {
    final ResultatRelance resultat = workerProxyService.relancerFetches();
    if (resultat.succes()) {
      return ResponseEntity.ok(Map.of("statut", "déclenché"));
    }
    return ResponseEntity.status(resultat.statutHttp())
        .body(Map.of("erreur", resultat.messageErreur()));
  }

  /**
   * Déclenche immédiatement un job de maintenance identifié par son {@code typeJob} lisible
   * (v2.6.0) — bouton « Relancer » par job dans l'onglet admin Tableau de bord.
   *
   * <p>Le code HTTP renvoyé distingue désormais le job inconnu (400) de l'erreur interne worker
   * (502) et du worker injoignable (503) — auparavant les trois étaient masqués sous un 503
   * générique, ce qui égarait le diagnostic d'un admin.
   *
   * @param typeJob identifiant lisible du job (ex. {@code "purger:salles"})
   * @return 200 si déclenché, 400 si le job est inconnu, 502/503 selon la cause de l'échec
   */
  @PostMapping("/jobs/relancer")
  public ResponseEntity<Map<String, Object>> relancerJob(@RequestParam final String typeJob) {
    final ResultatRelance resultat = workerProxyService.relancerJob(typeJob);
    if (resultat.succes()) {
      return ResponseEntity.ok(Map.of("statut", "déclenché"));
    }
    return ResponseEntity.status(resultat.statutHttp())
        .body(Map.of("erreur", resultat.messageErreur()));
  }

  /**
   * Retourne les informations système non liées à la configuration chiffrée.
   *
   * @return map avec {@code appConfigSecretPresente}
   */
  @GetMapping("/info")
  public ResponseEntity<Map<String, Object>> lireInfo() {
    return ResponseEntity.ok(
        Map.of("appConfigSecretPresente", System.getenv("APP_CONFIG_SECRET") != null));
  }

  private static UtilisateurDetails utilisateur(final Authentication auth) {
    if (!(auth.getPrincipal() instanceof UtilisateurDetails ud)) {
      throw new IllegalStateException("Principal inattendu");
    }
    return ud;
  }
}

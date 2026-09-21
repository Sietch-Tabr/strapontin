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
import org.springframework.boot.info.BuildProperties;
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
 * Contrôleur REST d'administration système : configuration applicative,
 * VAPID, bannière, logs, jobs Quartz — extrait d'{@link AdminController}
 * pour rester sous le seuil de couplage entre objets (20).
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
  private final BuildProperties buildProperties;

  /**
   * Injection Spring : services admin (comptes, config, jobs, bannière,
   * proxy worker, emails, VAPID) + métadonnées de build — paramètres
   * homonymes des champs.
   */
  // 8 collaborateurs Spring à responsabilité unique, chacun déjà justifié
  // individuellement (contrôleur admin déjà extrait pour rester sous le
  // seuil de couplage entre objets) — même traitement que
  // WorkerStatsService/AdminService.
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public SystemeAdminController(
      final AdminService adminService,
      final ConfigAdminService configAdminService,
      final JobsAdminService jobsAdminService,
      final BanniereService banniereService,
      final WorkerProxyService workerProxyService,
      final EmailServiceAdmin emailServiceAdmin,
      final VapidService vapidService,
      final BuildProperties buildProperties) {
    this.adminService = adminService;
    this.configAdminService = configAdminService;
    this.jobsAdminService = jobsAdminService;
    this.banniereService = banniereService;
    this.workerProxyService = workerProxyService;
    this.emailServiceAdmin = emailServiceAdmin;
    this.vapidService = vapidService;
    this.buildProperties = buildProperties;
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
   * Déclenche un job de maintenance par son {@code typeJob} lisible
   * (bouton « Relancer » du tableau de bord) — distingue job inconnu
   * (400), erreur interne worker (502), worker injoignable (503).
   * @param typeJob identifiant lisible du job (ex. "purger:salles")
   * @return 200 si déclenché, 400 job inconnu, 502/503 selon l'échec
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
   * Déclenche l'envoi push "nouvelle version" (version courante) à tous
   * les utilisateurs opt-in — action manuelle, à n'utiliser qu'après avoir
   * vérifié qu'une entrée existe pour cette version dans
   * {@code changelogVersions.js} (aucune vérification automatique côté
   * serveur, ce fichier n'est pas accessible depuis le backend).
   * @return 200 avec la version notifiée, 502/503 selon l'échec
   */
  @PostMapping("/notifier-version")
  public ResponseEntity<Map<String, Object>> notifierNouvelleVersion() {
    final String version = buildProperties.getVersion();
    final ResultatRelance resultat = workerProxyService.notifierNouvelleVersion(version);
    if (resultat.succes()) {
      return ResponseEntity.ok(Map.of("statut", "déclenché", "version", version));
    }
    return ResponseEntity.status(resultat.statutHttp())
        .body(Map.of("erreur", resultat.messageErreur()));
  }

  /**
   * Retourne les informations système non liées à la configuration chiffrée.
   *
   * @return map avec {@code appConfigSecretPresente} et {@code version}
   */
  @GetMapping("/info")
  public ResponseEntity<Map<String, Object>> lireInfo() {
    return ResponseEntity.ok(
        Map.of(
            "appConfigSecretPresente",
            System.getenv("APP_CONFIG_SECRET") != null,
            "version",
            buildProperties.getVersion()));
  }

  private static UtilisateurDetails utilisateur(final Authentication auth) {
    if (!(auth.getPrincipal() instanceof UtilisateurDetails ud)) {
      throw new IllegalStateException("Principal inattendu");
    }
    return ud;
  }
}

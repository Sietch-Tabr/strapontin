/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * Proxy HTTP vers le worker (port 8081) pour les opérations admin —
 * centralise les appels internes (jobs en cours, prochaines exécutions,
 * relance des fetches) via le {@link RestClient} partagé plutôt qu'un
 * {@code HttpClient} jetable par appel.
 * @since 2.2.3
 * @author Kwaite
 */
@Service
public class WorkerProxyService {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerProxyService.class);
  private static final String WORKER_BASE_URL = "http://localhost:8081";
  private static final String CLE_MDC_CORRELATION_ID = "correlationId";
  private static final String HEADER_CORRELATION_ID = "X-Correlation-ID";

  /** Message affiché quand le service de récupération des concerts ne répond pas. */
  public static final String MESSAGE_INJOIGNABLE =
      "Le service de récupération des concerts ne répond pas. Réessayez dans quelques minutes.";

  /** Message affiché quand ce service répond en erreur (détail technique au journal). */
  public static final String MESSAGE_EN_ERREUR =
      "Le service de récupération des concerts a rencontré une erreur."
          + " Réessayez dans quelques minutes.";
  private static final String NIVEAU_INFO = "info";
  private static final String SOURCE_ADMIN = "admin";

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final LogAdminService logAdminService;

  /**
   * @param restClient client HTTP partagé
   * @param objectMapper désérialiseur JSON des réponses worker
   * @param logAdminService service de logs admin
   */
  public WorkerProxyService(
      final RestClient restClient,
      final ObjectMapper objectMapper,
      final LogAdminService logAdminService) {
    this.restClient = restClient;
    this.objectMapper = objectMapper;
    this.logAdminService = logAdminService;
  }

  /**
   * Ajoute le header {@code X-Correlation-ID} porté par le MDC courant —
   * posé par {@code CorrelationIdFilter}, absent hors contexte HTTP
   * (ex. thread Quartz).
   * @param requete requête en cours de construction
   * @return la même requête, header ajouté si un correlationId existe
   */
  private RestClient.RequestHeadersSpec<?> ajouterCorrelationId(
      final RestClient.RequestHeadersSpec<?> requete) {
    final String correlationId = MDC.get(CLE_MDC_CORRELATION_ID);
    return correlationId != null ? requete.header(HEADER_CORRELATION_ID, correlationId) : requete;
  }

  /**
   * Retourne les identifiants des jobs Quartz actuellement en cours
   * d'exécution.
   *
   * @return la liste des jobs actifs, ou une liste vide si le worker est
   *     inaccessible
   */
  public List<String> obtenirJobsEnCours() {
    try {
      final String corps =
          ajouterCorrelationId(restClient.get().uri(WORKER_BASE_URL + "/trigger/actif"))
              .retrieve()
              .body(String.class);
      @SuppressWarnings("unchecked")
      final Map<String, Object> data = objectMapper.readValue(corps, Map.class);
      final Object actifs = data.get("actifs");
      if (actifs instanceof List<?> liste) {
        return liste.stream().map(Object::toString).toList();
      }
    } catch (Exception e) {
      LOG.debug("Worker inaccessible pour jobs actifs");
    }
    return List.of();
  }

  /**
   * Retourne les prochaines dates d'exécution planifiées par type de job.
   *
   * @return map type de job vers prochaine exécution, vide si le worker est
   *     inaccessible
   */
  public Map<String, Instant> obtenirProchainesExec() {
    try {
      final String corps =
          ajouterCorrelationId(restClient.get().uri(WORKER_BASE_URL + "/trigger/prochaines-exec"))
              .retrieve()
              .body(String.class);
      @SuppressWarnings("unchecked")
      final Map<String, Object> data = objectMapper.readValue(corps, Map.class);
      final Map<String, Instant> resultat = new HashMap<>();
      data.forEach(
          (k, v) -> {
            if (v instanceof Number nombre) {
              resultat.put(k, Instant.ofEpochMilli(nombre.longValue()));
            }
          });
      return resultat;
    } catch (Exception e) {
      LOG.debug("Worker inaccessible pour prochaines exécutions");
    }
    return Map.of();
  }

  /**
   * Déclenche un {@code FetchSalleJob} immédiat pour les identifiants donnés.
   *
   * @param identifiants identifiants préfixés par source (ex.
   *     {@code "tm:rZFSnyZdFvA"} )
   * @return {@code true} si planifié, {@code false} si le worker est
   *     inaccessible
   */
  public boolean relancerSalle(final String identifiants) {
    try {
      ajouterCorrelationId(
              restClient
                  .post()
                  .uri(WORKER_BASE_URL + "/trigger/salle?identifiants={ids}", identifiants))
          .retrieve()
          .toBodilessEntity();
      logAdminService.logAdmin(
          NIVEAU_INFO, SOURCE_ADMIN, "Fetch salle déclenché", Map.of("identifiants", identifiants));
      return true;
    } catch (Exception e) {
      LOG.warn("Erreur déclenchement salle identifiants={} : {}", identifiants, e.getMessage());
      return false;
    }
  }

  /**
   * Déclenche immédiatement tous les fetches via le worker.
   *
   * @return le résultat métier (succès, ou échec avec message et code HTTP
   *     adapté à la cause — )
   */
  public ResultatRelance relancerFetches() {
    try {
      ajouterCorrelationId(restClient.post().uri(WORKER_BASE_URL + "/trigger/tout"))
          .retrieve()
          .toBodilessEntity();
      logAdminService.logAdmin(NIVEAU_INFO, SOURCE_ADMIN, "Fetches relancés manuellement");
      return new ResultatRelance(true, null, 200);
    } catch (RestClientResponseException e) {
      return new ResultatRelance(
          false, MESSAGE_EN_ERREUR, 502);
    } catch (Exception e) {
      LOG.warn("Erreur appel worker trigger : {}", e.getMessage());
      return new ResultatRelance(false, MESSAGE_INJOIGNABLE, 503);
    }
  }

  /**
   * Déclenche un job de maintenance par son {@code typeJob} lisible (ex.
   * "purger:salles") via l'endpoint générique du worker — distingue 400
   * (typeJob inconnu, erreur cliente) d'une panne Quartz ou d'un worker
   * injoignable, plutôt que de tout renvoyer en 503.
   * @param typeJob identifiant lisible du job à relancer
   * @return le résultat métier (succès, ou échec avec message et code HTTP)
   */
  public ResultatRelance relancerJob(final String typeJob) {
    try {
      ajouterCorrelationId(
              restClient.post().uri(WORKER_BASE_URL + "/trigger/job?typeJob={typeJob}", typeJob))
          .retrieve()
          .toBodilessEntity();
      logAdminService.logAdmin(
          NIVEAU_INFO, SOURCE_ADMIN, "Job relancé manuellement", Map.of("typeJob", typeJob));
      return new ResultatRelance(true, null, 200);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()) {
        return new ResultatRelance(
            false, "Tâche inconnue : " + typeJob + ".", HttpStatus.BAD_REQUEST.value());
      }
      return new ResultatRelance(
          false, MESSAGE_EN_ERREUR, 502);
    } catch (Exception e) {
      LOG.warn("Erreur appel worker trigger job typeJob={} : {}", typeJob, e.getMessage());
      return new ResultatRelance(false, MESSAGE_INJOIGNABLE, 503);
    }
  }

  /**
   * Déclenche l'envoi push "nouvelle version" à tous les utilisateurs
   * opt-in — action admin manuelle, jamais automatique.
   * @param version version applicative courante
   * @return le résultat métier (succès, ou échec avec message et code HTTP)
   */
  public ResultatRelance notifierNouvelleVersion(final String version) {
    try {
      ajouterCorrelationId(
              restClient
                  .post()
                  .uri(WORKER_BASE_URL + "/trigger/notifier-version?version={version}", version))
          .retrieve()
          .toBodilessEntity();
      logAdminService.logAdmin(
          NIVEAU_INFO,
          SOURCE_ADMIN,
          "Notification nouvelle version déclenchée",
          Map.of("version", version));
      return new ResultatRelance(true, null, 200);
    } catch (RestClientResponseException e) {
      return new ResultatRelance(
          false, MESSAGE_EN_ERREUR, 502);
    } catch (Exception e) {
      LOG.warn(
          "Erreur appel worker trigger notifier-version version={} : {}", version, e.getMessage());
      return new ResultatRelance(false, MESSAGE_INJOIGNABLE, 503);
    }
  }
}

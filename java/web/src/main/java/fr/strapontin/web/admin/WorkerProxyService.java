/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Proxy HTTP vers le worker (port 8081) pour les opérations admin.
 *
 * <p>Centralise les appels internes vers le worker (jobs en cours, prochaines exécutions, relance
 * des fetches) en réutilisant le {@link RestClient} partagé plutôt que d'instancier un {@code
 * HttpClient} jetable par appel.
 *
 * @since 2.2.3
 * @author Kwaite
 */
@Service
public class WorkerProxyService {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerProxyService.class);
  private static final String WORKER_BASE_URL = "http://localhost:8081";
  private static final String CLE_MDC_CORRELATION_ID = "correlationId";
  private static final String HEADER_CORRELATION_ID = "X-Correlation-ID";

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
   * Ajoute le header {@code X-Correlation-ID} porté par le MDC de la requête entrante courante —
   * pose par {@code CorrelationIdFilter}, absent si l'appel a lieu hors contexte de requête HTTP
   * (ex. depuis un thread Quartz).
   *
   * @param requete requête en cours de construction
   * @return la même requête, avec le header ajouté si un correlationId est disponible
   */
  private RestClient.RequestHeadersSpec<?> ajouterCorrelationId(
      final RestClient.RequestHeadersSpec<?> requete) {
    final String correlationId = MDC.get(CLE_MDC_CORRELATION_ID);
    return correlationId != null ? requete.header(HEADER_CORRELATION_ID, correlationId) : requete;
  }

  /**
   * Retourne les identifiants des jobs Quartz actuellement en cours d'exécution.
   *
   * @return la liste des jobs actifs, ou une liste vide si le worker est inaccessible
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
   * @return map type de job vers prochaine exécution, vide si le worker est inaccessible
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
            if (v instanceof Number n) {
              resultat.put(k, Instant.ofEpochMilli(n.longValue()));
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
   * @param identifiants identifiants préfixés par source (ex. {@code "tm:rZFSnyZdFvA"})
   * @return {@code true} si planifié, {@code false} si le worker est inaccessible
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
          "info", "admin", "Fetch salle déclenché", Map.of("identifiants", identifiants));
      return true;
    } catch (Exception e) {
      LOG.warn("Erreur déclenchement salle identifiants={}", identifiants, e);
      return false;
    }
  }

  /**
   * Déclenche immédiatement tous les fetches via le worker.
   *
   * @return le résultat métier (succès, ou échec avec message et code HTTP adapté à la cause — )
   */
  public ResultatRelance relancerFetches() {
    try {
      ajouterCorrelationId(restClient.post().uri(WORKER_BASE_URL + "/trigger/tout"))
          .retrieve()
          .toBodilessEntity();
      logAdminService.logAdmin("info", "admin", "Fetches relancés manuellement");
      return new ResultatRelance(true, null, 200);
    } catch (RestClientResponseException e) {
      return new ResultatRelance(
          false, "Erreur interne worker (statut " + e.getStatusCode().value() + ").", 502);
    } catch (Exception e) {
      LOG.warn("Erreur appel worker trigger", e);
      return new ResultatRelance(false, "Worker inaccessible.", 503);
    }
  }

  /**
   * Déclenche immédiatement un job de maintenance identifié par son {@code typeJob} lisible (ex.
   * {@code "purger:salles"}, {@code "reconciliation:nocturne"}) via l'endpoint générique du worker
   * (v2.6.0).
   *
   * <p>Distingue trois causes d'échec plutôt que de toutes les renvoyer en 503 : le worker répond
   * 400 si {@code typeJob} est inconnu (erreur cliente, pas une panne), un autre code d'erreur si
   * Quartz échoue en interne, ou aucune réponse si le worker est réellement injoignable.
   *
   * @param typeJob identifiant lisible du job à relancer
   * @return le résultat métier (succès, ou échec avec message et code HTTP adapté à la cause)
   */
  public ResultatRelance relancerJob(final String typeJob) {
    try {
      ajouterCorrelationId(
              restClient.post().uri(WORKER_BASE_URL + "/trigger/job?typeJob={typeJob}", typeJob))
          .retrieve()
          .toBodilessEntity();
      logAdminService.logAdmin(
          "info", "admin", "Job relancé manuellement", Map.of("typeJob", typeJob));
      return new ResultatRelance(true, null, 200);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()) {
        return new ResultatRelance(
            false, "Job inconnu : " + typeJob + ".", HttpStatus.BAD_REQUEST.value());
      }
      return new ResultatRelance(
          false, "Erreur interne worker (statut " + e.getStatusCode().value() + ").", 502);
    } catch (Exception e) {
      LOG.warn("Erreur appel worker trigger job typeJob={}", typeJob, e);
      return new ResultatRelance(false, "Worker inaccessible.", 503);
    }
  }
}

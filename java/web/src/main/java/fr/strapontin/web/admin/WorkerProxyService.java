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
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/// Proxy HTTP vers le worker (port 8081) pour les opérations admin.
///
/// <p>Centralise les appels internes vers le worker (jobs en cours, prochaines
/// exécutions, relance des fetches) en réutilisant le {@link RestClient} partagé
/// plutôt que d'instancier un {@code HttpClient} jetable par appel.
///
/// @since 2.2.3
/// @author Kwaite
@Service
public class WorkerProxyService {

  private static final Logger log = LoggerFactory.getLogger(WorkerProxyService.class);
  private static final String WORKER_BASE_URL = "http://localhost:8081";

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final LogAdminService logAdminService;

  /// @param restClient      client HTTP partagé
  /// @param objectMapper    désérialiseur JSON des réponses worker
  /// @param logAdminService service de logs admin
  public WorkerProxyService(
      RestClient restClient, ObjectMapper objectMapper, LogAdminService logAdminService) {
    this.restClient = restClient;
    this.objectMapper = objectMapper;
    this.logAdminService = logAdminService;
  }

  /// Retourne les identifiants des jobs Quartz actuellement en cours d'exécution.
  ///
  /// @return la liste des jobs actifs, ou une liste vide si le worker est inaccessible
  public List<String> obtenirJobsEnCours() {
    try {
      String corps =
          restClient.get().uri(WORKER_BASE_URL + "/trigger/actif").retrieve().body(String.class);
      @SuppressWarnings("unchecked")
      Map<String, Object> data = objectMapper.readValue(corps, Map.class);
      Object actifs = data.get("actifs");
      if (actifs instanceof List<?> liste) {
        return liste.stream().map(Object::toString).toList();
      }
    } catch (Exception e) {
      log.debug("Worker inaccessible pour jobs actifs");
    }
    return List.of();
  }

  /// Retourne les prochaines dates d'exécution planifiées par type de job.
  ///
  /// @return map type de job vers prochaine exécution, vide si le worker est inaccessible
  public Map<String, Instant> obtenirProchainesExec() {
    try {
      String corps =
          restClient
              .get()
              .uri(WORKER_BASE_URL + "/trigger/prochaines-exec")
              .retrieve()
              .body(String.class);
      @SuppressWarnings("unchecked")
      Map<String, Object> data = objectMapper.readValue(corps, Map.class);
      Map<String, Instant> resultat = new HashMap<>();
      data.forEach(
          (k, v) -> {
            if (v instanceof Number n) resultat.put(k, Instant.ofEpochMilli(n.longValue()));
          });
      return resultat;
    } catch (Exception e) {
      log.debug("Worker inaccessible pour prochaines exécutions");
    }
    return Map.of();
  }

  /// Déclenche un {@code FetchSalleJob} immédiat pour les identifiants donnés.
  ///
  /// @param identifiants identifiants préfixés par source (ex. {@code "tm:rZFSnyZdFvA"})
  /// @return {@code true} si planifié, {@code false} si le worker est inaccessible
  public boolean relancerSalle(String identifiants) {
    try {
      restClient
          .post()
          .uri(WORKER_BASE_URL + "/trigger/salle?identifiants={ids}", identifiants)
          .retrieve()
          .toBodilessEntity();
      logAdminService.logAdmin(
          "info", "admin", "Fetch salle déclenché", Map.of("identifiants", identifiants));
      return true;
    } catch (Exception e) {
      log.warn("Erreur déclenchement salle identifiants={}", identifiants, e);
      return false;
    }
  }

  /// Déclenche immédiatement tous les fetches via le worker.
  ///
  /// @return le résultat métier (succès, ou échec avec message si le worker est inaccessible)
  public ResultatRelance relancerFetches() {
    try {
      restClient.post().uri(WORKER_BASE_URL + "/trigger/tout").retrieve().toBodilessEntity();
      logAdminService.logAdmin("info", "admin", "Fetches relancés manuellement");
      return new ResultatRelance(true, null);
    } catch (RestClientResponseException e) {
      return new ResultatRelance(
          false, "Worker inaccessible (statut " + e.getStatusCode().value() + ").");
    } catch (Exception e) {
      log.warn("Erreur appel worker trigger", e);
      return new ResultatRelance(false, "Worker inaccessible.");
    }
  }
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.client;

import fr.strapontin.web.admin.LogAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de remontée des erreurs JavaScript côté client — permet aux
 * navigateurs des utilisateurs authentifiés de signaler erreurs JS et
 * rejections de promesses non catchées, stockées dans {@code logs_admin}
 * (source {@code client-js}).
 * @since 2.1.2
 * @author Kwaite
 */
@RestController
@RequestMapping("/api/client")
public class ClientController {

  private static final int LONGUEUR_MAX_MESSAGE = 1000;
  private static final int LONGUEUR_MAX_URL = 500;
  private static final int MAX_CLES_META = 20;
  private static final Set<String> TYPES_VALIDES = Set.of("js-error", "promise-rejection");

  private final LogAdminService logAdminService;

  /**
   * @param logAdminService journalisation des erreurs signalées
   */
  public ClientController(final LogAdminService logAdminService) {
    this.logAdminService = logAdminService;
  }

  /**
   * Signale une erreur JavaScript depuis le navigateur.
   *
   * @param corps corps JSON de l'erreur
   * @param request requête HTTP (pour récupérer le User-Agent)
   * @return 201 Created avec {@code {"succes": true}}
   */
  @PostMapping("/erreur")
  public ResponseEntity<Map<String, Object>> signalerErreur(
      @RequestBody final Map<String, Object> corps, final HttpServletRequest request) {
    final String message =
        tronquer(String.valueOf(corps.getOrDefault("message", "")), LONGUEUR_MAX_MESSAGE);
    final String type = normaliserType(corps.getOrDefault("type", "js-error"));
    final String url = tronquer(String.valueOf(corps.getOrDefault("url", "")), LONGUEUR_MAX_URL);
    final String userAgent = tronquer(request.getHeader("User-Agent"), LONGUEUR_MAX_URL);

    if (message.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("erreur", "Message requis."));
    }

    final Map<String, Object> meta = new HashMap<>();
    meta.put("url", url != null ? url : "");
    meta.put("userAgent", userAgent != null ? userAgent : "");
    ajouterMetaSupplementaire(meta, corps);

    logAdminService.logAdmin("error", "client-js", "[" + type + "] " + message, meta);

    return ResponseEntity.status(201).body(Map.of("succes", true));
  }

  private static String normaliserType(final Object typeBrut) {
    final String type = String.valueOf(typeBrut);
    return TYPES_VALIDES.contains(type) ? type : "js-error";
  }

  private void ajouterMetaSupplementaire(
      final Map<String, Object> meta, final Map<String, Object> corps) {
    final Set<String> clesConnues = Set.of("message", "type", "url", "userAgent");
    for (final Map.Entry<String, Object> entree : corps.entrySet()) {
      if (meta.size() >= MAX_CLES_META) {
        break;
      }
      if (!clesConnues.contains(entree.getKey())) {
        final Object valeur = entree.getValue();
        meta.put(
            entree.getKey(),
            valeur != null ? tronquer(String.valueOf(valeur), LONGUEUR_MAX_URL) : "");
      }
    }
  }

  private String tronquer(final String valeur, final int max) {
    if (valeur == null) {
      return null;
    }
    return valeur.length() > max ? valeur.substring(0, max) : valeur;
  }
}

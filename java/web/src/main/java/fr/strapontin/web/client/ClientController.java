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

/// Endpoint de remontée des erreurs JavaScript côté client.
///
/// <p>Permet aux navigateurs des utilisateurs authentifiés de signaler les erreurs JS non catchées
/// et les rejections de promesses. Les entrées sont stockées dans {@code logs_admin} avec la source
/// {@code client-js}.
///
/// @since 2.1.2
/// @author Kwaite
@RestController
@RequestMapping("/api/client")
public class ClientController {

  private static final int LONGUEUR_MAX_MESSAGE = 1000;
  private static final int LONGUEUR_MAX_URL = 500;
  private static final int MAX_CLES_META = 20;
  private static final Set<String> TYPES_VALIDES = Set.of("js-error", "promise-rejection");

  private final LogAdminService logAdminService;

  public ClientController(LogAdminService logAdminService) {
    this.logAdminService = logAdminService;
  }

  /// Signale une erreur JavaScript depuis le navigateur.
  ///
  /// @param corps corps JSON de l'erreur
  /// @param request requête HTTP (pour récupérer le User-Agent)
  /// @return 201 Created avec {@code {"succes": true}}
  @PostMapping("/erreur")
  public ResponseEntity<Map<String, Object>> signalerErreur(
      @RequestBody Map<String, Object> corps, HttpServletRequest request) {
    String message =
        tronquer(String.valueOf(corps.getOrDefault("message", "")), LONGUEUR_MAX_MESSAGE);
    String type = String.valueOf(corps.getOrDefault("type", "js-error"));
    if (!TYPES_VALIDES.contains(type)) {
      type = "js-error";
    }
    String url = tronquer(String.valueOf(corps.getOrDefault("url", "")), LONGUEUR_MAX_URL);
    String userAgent = tronquer(request.getHeader("User-Agent"), LONGUEUR_MAX_URL);

    if (message.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("erreur", "Message requis."));
    }

    Map<String, Object> meta = new HashMap<>();
    meta.put("url", url != null ? url : "");
    meta.put("userAgent", userAgent != null ? userAgent : "");
    for (var entree : corps.entrySet()) {
      if (meta.size() >= MAX_CLES_META) {
        break;
      }
      String cle = entree.getKey();
      if (!"message".equals(cle)
          && !"type".equals(cle)
          && !"url".equals(cle)
          && !"userAgent".equals(cle)) {
        Object valeur = entree.getValue();
        meta.put(cle, valeur != null ? tronquer(String.valueOf(valeur), LONGUEUR_MAX_URL) : "");
      }
    }

    logAdminService.logAdmin("error", "client-js", "[" + type + "] " + message, meta);

    return ResponseEntity.status(201).body(Map.of("succes", true));
  }

  private String tronquer(String valeur, int max) {
    if (valeur == null) return null;
    return valeur.length() > max ? valeur.substring(0, max) : valeur;
  }
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.securite.AdresseClient;
import fr.strapontin.web.securite.LimiteurDebit;
import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Remontée des erreurs JavaScript des utilisateurs dans {@code logs_admin}.
 * Volume borné par utilisateur (débit, dédoublonnage) : chaque erreur
 * alimente le digest d'alerte admin, donc le quota d'emails.
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
  /* package-private : visibilité élargie pour ClientControllerTest (même package). */
  static final int MAX_ERREURS_PAR_HEURE = 20;
  private static final Duration FENETRE = Duration.ofHours(1);

  private final LogAdminService logAdminService;

  private final LimiteurDebit limiteurErreurs =
      new LimiteurDebit(MAX_ERREURS_PAR_HEURE, FENETRE, FENETRE.plusMinutes(1), 10_000);
  // Une erreur récurrente (boucle, polling) remonte le même message en rafale.
  private final Cache<String, Boolean> messagesRecents =
      Caffeine.newBuilder().expireAfterWrite(FENETRE).maximumSize(10_000).build();

  /**
   * @param logAdminService journalisation des erreurs signalées
   */
  public ClientController(final LogAdminService logAdminService) {
    this.logAdminService = logAdminService;
  }

  /**
   * @param auth contexte d'authentification (clé de limitation)
   * @return 201 (même pour un message déjà signalé dans l'heure, non
   *     rejournalisé), 429 au-delà de {@value #MAX_ERREURS_PAR_HEURE} par heure
   */
  @PostMapping("/erreur")
  public ResponseEntity<Map<String, Object>> signalerErreur(
      @RequestBody final Map<String, Object> corps,
      final Authentication auth,
      final HttpServletRequest request) {
    final String message =
        tronquer(String.valueOf(corps.getOrDefault("message", "")), LONGUEUR_MAX_MESSAGE);
    final String type = normaliserType(corps.getOrDefault("type", "js-error"));
    final String url = tronquer(String.valueOf(corps.getOrDefault("url", "")), LONGUEUR_MAX_URL);
    final String userAgent = tronquer(request.getHeader("User-Agent"), LONGUEUR_MAX_URL);

    if (message.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("erreur", "Message requis."));
    }

    final String cleUtilisateur =
        auth != null && auth.getPrincipal() instanceof UtilisateurDetails details
            ? details.id()
            : AdresseClient.cle(request.getRemoteAddr());
    if (messagesRecents.asMap().putIfAbsent(cleUtilisateur + "|" + type + "|" + message, true)
        != null) {
      return ResponseEntity.status(201).body(Map.of("succes", true));
    }
    if (!limiteurErreurs.autoriser(cleUtilisateur)) {
      return ResponseEntity.status(429).body(Map.of("erreur", "Trop d'erreurs signalées."));
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

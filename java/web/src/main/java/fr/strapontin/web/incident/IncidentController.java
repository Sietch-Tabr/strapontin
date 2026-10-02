/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.incident;

import static fr.strapontin.web.ControllerUtils.erreur;

import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Communication d'incident : lecture publique (bannière, y compris sur les
 * pages d'authentification) et publication réservée aux admins.
 * @since 3.0.1
 * @author Kwaite
 */
@RestController
public class IncidentController {

  private final IncidentService service;

  /**
   * @param service service de communication d'incident
   */
  public IncidentController(final IncidentService service) {
    this.service = service;
  }

  /**
   * Communication active, accessible sans session : un utilisateur déconnecté
   * (session révoquée, mot de passe à changer) doit aussi être prévenu.
   * @return 200 avec la communication, 204 si aucune
   */
  @GetMapping("/api/incident")
  public ResponseEntity<Map<String, Object>> lirePublique() {
    return service
        .lireActive()
        .map(
            c ->
                ResponseEntity.ok(
                    Map.<String, Object>of(
                        "id", c.id(),
                        "titre", c.titre(),
                        "message", c.message(),
                        "publieeLe", c.publieeLe().toString())))
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  /**
   * État pour l'admin : communication active, comptes et quota d'emails.
   * @return 200 avec {@code active}, {@code nbComptes}, {@code envoisEmailRestants}
   */
  @GetMapping("/api/admin/incident")
  public ResponseEntity<Map<String, Object>> lireAdmin() {
    final Map<String, Object> corps = new HashMap<>();
    corps.put("active", service.lireActive().orElse(null));
    corps.put("nbComptes", service.compterComptes());
    corps.put("envoisEmailRestants", service.envoisEmailRestants());
    return ResponseEntity.ok(corps);
  }

  /**
   * Publie une communication d'incident et la diffuse.
   * @param requete contenu et canaux
   * @param admin administrateur connecté
   * @param request requête HTTP (session de l'admin, conservée)
   * @return 200 avec la communication publiée, 409 si quota email insuffisant
   */
  @PostMapping("/api/admin/incident")
  public ResponseEntity<CommunicationIncident> publier(
      @Valid @RequestBody final RequetePublicationIncident requete,
      @AuthenticationPrincipal final UtilisateurDetails admin,
      final HttpServletRequest request) {
    final HttpSession session = request.getSession(false);
    return ResponseEntity.ok(
        service.publier(
            requete,
            admin != null ? admin.id() : "inconnu",
            session != null ? session.getId() : null));
  }

  /**
   * Clôt la communication active.
   * @return 200 si close, 404 si aucune communication active
   */
  @PostMapping("/api/admin/incident/terminer")
  public ResponseEntity<Map<String, Object>> terminer() {
    if (!service.terminer()) {
      return erreur(404, "Aucune communication d'incident active.");
    }
    return ResponseEntity.ok(Map.of("succes", true));
  }
}

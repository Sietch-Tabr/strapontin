/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour la configuration utilisateur (portage de {@code
// parametres/+page.server.ts}).
///
/// <p>Gère les départements, filtres de disponibilité, préférences de notification et thème.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/user/config")
public class UserConfigController {

  private static final Pattern FORMAT_DEPT = Pattern.compile("^\\d{2,3}$");
  private static final Set<String> FREQUENCES_VALIDES = Set.of("jamais", "temps_reel", "quotidien");
  private static final Set<String> THEMES_VALIDES = Set.of("auto", "clair", "sombre");

  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigService configService;

  public UserConfigController(NamedParameterJdbcTemplate jdbc, ConfigService configService) {
    this.jdbc = jdbc;
    this.configService = configService;
  }

  record ConfigUtilisateur(
      List<String> departements,
      boolean filtreDisponibles,
      String frequenceNotifications,
      boolean notifArtistes,
      boolean notifSalles,
      String theme,
      boolean resendDisponible) {}

  record RequeteDepartements(String departements) {}

  record RequeteNotifications(
      String frequenceNotifications, boolean notifArtistes, boolean notifSalles) {}

  record RequeteFiltres(boolean filtreDisponibles) {}

  record RequeteTheme(String valeur) {}

  /// Retourne la configuration de l'utilisateur courant.
  ///
  /// @param auth contexte d'authentification
  /// @return la configuration ou valeurs par défaut si absente
  @GetMapping
  public ResponseEntity<ConfigUtilisateur> lireConfig(Authentication auth) {
    String userId = utilisateur(auth).id();

    List<ConfigUtilisateur> lignes =
        jdbc.query(
            """
            SELECT departements, filtre_disponibles, frequence_notifications,
                   notif_artistes, notif_salles, theme
            FROM user_config
            WHERE user_id = :userId
            """,
            Map.of("userId", userId),
            (rs, _) -> {
              String[] depts = (String[]) rs.getArray("departements").getArray();
              return new ConfigUtilisateur(
                  Arrays.asList(depts),
                  rs.getBoolean("filtre_disponibles"),
                  rs.getString("frequence_notifications"),
                  rs.getBoolean("notif_artistes"),
                  rs.getBoolean("notif_salles"),
                  rs.getString("theme"),
                  false);
            });

    boolean resendDisponible = configService.lireConfig("RESEND_API_KEY").isPresent();

    ConfigUtilisateur config =
        lignes.isEmpty()
            ? new ConfigUtilisateur(List.of(), true, "jamais", true, false, "auto", resendDisponible)
            : new ConfigUtilisateur(
                lignes.get(0).departements(),
                lignes.get(0).filtreDisponibles(),
                lignes.get(0).frequenceNotifications(),
                lignes.get(0).notifArtistes(),
                lignes.get(0).notifSalles(),
                lignes.get(0).theme(),
                resendDisponible);

    return ResponseEntity.ok(config);
  }

  /// Met à jour les départements de l'utilisateur.
  ///
  /// @param requete corps JSON avec {@code departements} séparés par virgules
  /// @param auth    contexte d'authentification
  /// @return 200 si mis à jour, 400 en cas d'erreur
  @PatchMapping("/departements")
  public ResponseEntity<Map<String, Object>> majDepartements(
      @RequestBody RequeteDepartements requete, Authentication auth) {
    String userId = utilisateur(auth).id();

    List<String> depts =
        Arrays.stream((requete.departements() == null ? "" : requete.departements()).split(","))
            .map(String::strip)
            .map(this::normaliserDept)
            .filter(d -> d != null)
            .distinct()
            .collect(Collectors.toList());

    upsertConfig(userId, Map.of("departements", depts.toArray(String[]::new)));
    return ResponseEntity.ok(Map.of("succes", "departements"));
  }

  /// Met à jour les préférences de notification.
  ///
  /// @param requete corps JSON avec fréquence et types de notification
  /// @param auth    contexte d'authentification
  /// @return 200 si mis à jour, 400 en cas d'erreur
  @PatchMapping("/notifications")
  public ResponseEntity<Map<String, Object>> majNotifications(
      @RequestBody RequeteNotifications requete, Authentication auth) {
    String userId = utilisateur(auth).id();

    if (!FREQUENCES_VALIDES.contains(requete.frequenceNotifications())) {
      return erreur(400, "Valeur invalide.");
    }

    Map<String, Object> champs = new HashMap<>();
    champs.put("frequenceNotifications", requete.frequenceNotifications());
    champs.put("notifArtistes", requete.notifArtistes());
    champs.put("notifSalles", requete.notifSalles());
    upsertConfig(userId, champs);
    return ResponseEntity.ok(Map.of("succes", "notifications"));
  }

  /// Met à jour le filtre de disponibilité.
  ///
  /// @param requete corps JSON avec {@code filtreDisponibles}
  /// @param auth    contexte d'authentification
  /// @return 200 si mis à jour
  @PatchMapping("/filtres")
  public ResponseEntity<Map<String, Object>> majFiltres(
      @RequestBody RequeteFiltres requete, Authentication auth) {
    String userId = utilisateur(auth).id();
    upsertConfig(userId, Map.of("filtreDisponibles", requete.filtreDisponibles()));
    return ResponseEntity.ok(Map.of("succes", "filtres"));
  }

  /// Met à jour le thème de l'interface.
  ///
  /// @param requete corps JSON avec {@code valeur} ({@code auto}, {@code clair} ou {@code sombre})
  /// @param auth    contexte d'authentification
  /// @return 200 si mis à jour, 400 si thème invalide
  @PatchMapping("/theme")
  public ResponseEntity<Map<String, Object>> majTheme(
      @RequestBody RequeteTheme requete, Authentication auth) {
    String userId = utilisateur(auth).id();

    if (!THEMES_VALIDES.contains(requete.valeur())) {
      return erreur(400, "Valeur de thème invalide.");
    }

    // Cast explicite ::theme_interface requis : PostgreSQL refuse varchar → ENUM sans cast.
    jdbc.update(
        """
        INSERT INTO user_config (user_id, theme)
        VALUES (:userId, :theme::theme_interface)
        ON CONFLICT (user_id) DO UPDATE SET theme = :theme::theme_interface, updated_at = now()
        """,
        Map.of("userId", userId, "theme", requete.valeur()));
    return ResponseEntity.ok(Map.of("succes", "theme", "theme", requete.valeur()));
  }

  private void upsertConfig(String userId, Map<String, Object> champs) {
    // Construire le ON CONFLICT DO UPDATE SET dynamiquement
    StringBuilder setClauses = new StringBuilder();
    Map<String, Object> params = new HashMap<>(champs);
    params.put("userId", userId);

    for (String cle : champs.keySet()) {
      if (setClauses.length() > 0) setClauses.append(", ");
      String colonne = versColonne(cle);
      setClauses.append(colonne).append(" = :").append(cle);
    }

    // Pour le INSERT, on construit la liste des colonnes aussi
    List<String> colonnes =
        champs.keySet().stream().map(this::versColonne).collect(Collectors.toList());
    List<String> placeholders =
        champs.keySet().stream().map(c -> ":" + c).collect(Collectors.toList());

    colonnes.add(0, "user_id");
    placeholders.add(0, ":userId");

    String sql =
        """
        INSERT INTO user_config (%s)
        VALUES (%s)
        ON CONFLICT (user_id) DO UPDATE SET %s, updated_at = now()
        """
            .formatted(String.join(", ", colonnes), String.join(", ", placeholders), setClauses);

    jdbc.update(sql, params);
  }

  private String versColonne(String champJava) {
    return switch (champJava) {
      case "departements" -> "departements";
      case "filtreDisponibles" -> "filtre_disponibles";
      case "frequenceNotifications" -> "frequence_notifications";
      case "notifArtistes" -> "notif_artistes";
      case "notifSalles" -> "notif_salles";
      case "theme" -> "theme";
      default -> throw new IllegalArgumentException("Champ inconnu : " + champJava);
    };
  }

  // Normalise 2A/2B → "20", sinon format 2-3 chiffres
  private String normaliserDept(String d) {
    String valeur = d.toUpperCase();
    if (valeur.matches("2[AB]")) return "20";
    if (FORMAT_DEPT.matcher(valeur).matches()) return valeur;
    return null;
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }
}

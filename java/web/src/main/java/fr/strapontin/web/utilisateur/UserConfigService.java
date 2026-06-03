/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.config.ConfigService;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service de gestion de la configuration utilisateur ({@code user_config}).
///
/// <p>Couvre la lecture, la mise à jour des départements, notifications, filtres et thème.
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class UserConfigService {

  private static final Pattern FORMAT_DEPT = Pattern.compile("^\\d{2,3}$");
  private static final Set<String> FREQUENCES_VALIDES = Set.of("jamais", "temps_reel", "quotidien");
  private static final Set<String> THEMES_VALIDES = Set.of("auto", "clair", "sombre");

  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigService configService;

  /// @param jdbc          accès JDBC
  /// @param configService lecture des clés applicatives (RESEND_API_KEY)
  public UserConfigService(NamedParameterJdbcTemplate jdbc, ConfigService configService) {
    this.jdbc = jdbc;
    this.configService = configService;
  }

  /// Lit le filtre "concerts disponibles uniquement" de l'utilisateur.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @return {@code true} si seuls les concerts disponibles doivent être affichés (défaut)
  public boolean lireFiltreDisponibles(String userId) {
    List<Boolean> lignes =
        jdbc.queryForList(
            "SELECT filtre_disponibles FROM user_config WHERE user_id = :userId",
            Map.of("userId", userId),
            Boolean.class);
    return lignes.isEmpty() || lignes.get(0);
  }

  /// Retourne la configuration complète de l'utilisateur.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @return la configuration, ou les valeurs par défaut si absente
  public ConfigUtilisateur lireConfig(String userId) {
    List<ConfigUtilisateur> lignes =
        jdbc.query(
            """
            SELECT departements, filtre_disponibles, frequence_notifications,
                   notif_artistes, notif_salles, notif_artistes_push, notif_salles_push, theme
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
                  rs.getBoolean("notif_artistes_push"),
                  rs.getBoolean("notif_salles_push"),
                  rs.getString("theme"),
                  false);
            });

    boolean resendDisponible = configService.lireConfig("RESEND_API_KEY").isPresent();

    if (lignes.isEmpty()) {
      return new ConfigUtilisateur(
          List.of(), true, "jamais", true, false, true, true, "auto", resendDisponible);
    }
    ConfigUtilisateur base = lignes.get(0);
    return new ConfigUtilisateur(
        base.departements(),
        base.filtreDisponibles(),
        base.frequenceNotifications(),
        base.notifArtistes(),
        base.notifSalles(),
        base.notifArtistesPush(),
        base.notifSallesPush(),
        base.theme(),
        resendDisponible);
  }

  /// Met à jour les départements de l'utilisateur.
  ///
  /// <p>Les codes non reconnus sont retournés dans la liste {@code ignores}.
  ///
  /// @param userId  identifiant de l'utilisateur
  /// @param bruts   liste brute des codes département (peuvent être invalides)
  /// @return résultat avec la liste des codes ignorés
  public ResultatMajDepartements majDepartements(String userId, List<String> bruts) {
    List<String> depts =
        bruts.stream()
            .filter(s -> normaliserDept(s) != null)
            .map(this::normaliserDept)
            .distinct()
            .collect(Collectors.toList());

    List<String> ignores = bruts.stream().filter(s -> normaliserDept(s) == null).toList();

    upsertConfig(userId, Map.of("departements", depts.toArray(String[]::new)));
    return new ResultatMajDepartements(ignores);
  }

  /// Met à jour les préférences de notification.
  ///
  /// @param userId  identifiant de l'utilisateur
  /// @param requete données de la requête
  /// @throws ErreurMetier si la fréquence est invalide ou aucun canal de notification sélectionné
  public void majNotifications(String userId, RequeteNotifications requete) {
    if (!FREQUENCES_VALIDES.contains(requete.frequenceNotifications())) {
      throw new ErreurMetier("Valeur invalide.");
    }
    boolean aucunCanal =
        !requete.notifArtistes()
            && !requete.notifSalles()
            && !requete.notifArtistesPush()
            && !requete.notifSallesPush();
    if (!"jamais".equals(requete.frequenceNotifications()) && aucunCanal) {
      throw new ErreurMetier(
          "Activez au moins un type de contenu pour recevoir des notifications.");
    }

    Map<String, Object> champs = new HashMap<>();
    champs.put("frequenceNotifications", requete.frequenceNotifications());
    champs.put("notifArtistes", requete.notifArtistes());
    champs.put("notifSalles", requete.notifSalles());
    champs.put("notifArtistesPush", requete.notifArtistesPush());
    champs.put("notifSallesPush", requete.notifSallesPush());
    upsertConfig(userId, champs);
  }

  /// Met à jour le filtre de disponibilité.
  ///
  /// @param userId            identifiant de l'utilisateur
  /// @param filtreDisponibles nouvelle valeur du filtre
  public void majFiltres(String userId, boolean filtreDisponibles) {
    upsertConfig(userId, Map.of("filtreDisponibles", filtreDisponibles));
  }

  /// Met à jour le thème de l'interface.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @param valeur nouvelle valeur du thème ({@code auto}, {@code clair} ou {@code sombre})
  /// @throws ErreurMetier si la valeur du thème est invalide
  public void majTheme(String userId, String valeur) {
    if (!THEMES_VALIDES.contains(valeur)) {
      throw new ErreurMetier("Valeur de thème invalide.");
    }
    jdbc.update(
        """
        INSERT INTO user_config (user_id, theme)
        VALUES (:userId, CAST(:theme AS theme_interface))
        ON CONFLICT (user_id) DO UPDATE SET theme = CAST(:theme AS theme_interface), updated_at = now()
        """,
        Map.of("userId", userId, "theme", valeur));
  }

  private void upsertConfig(String userId, Map<String, Object> champs) {
    StringBuilder setClauses = new StringBuilder();
    Map<String, Object> params = new HashMap<>(champs);
    params.put("userId", userId);

    for (String cle : champs.keySet()) {
      if (setClauses.length() > 0) setClauses.append(", ");
      String colonne = versColonne(cle);
      setClauses.append(colonne).append(" = ").append(versCast(cle, ":" + cle));
    }

    List<String> colonnes =
        champs.keySet().stream().map(this::versColonne).collect(Collectors.toList());
    List<String> placeholders =
        champs.keySet().stream().map(c -> versCast(c, ":" + c)).collect(Collectors.toList());

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

  private static String versCast(String champJava, String placeholder) {
    return switch (champJava) {
      case "frequenceNotifications" -> "CAST(" + placeholder + " AS frequence_notif)";
      default -> placeholder;
    };
  }

  private String versColonne(String champJava) {
    return switch (champJava) {
      case "departements" -> "departements";
      case "filtreDisponibles" -> "filtre_disponibles";
      case "frequenceNotifications" -> "frequence_notifications";
      case "notifArtistes" -> "notif_artistes";
      case "notifSalles" -> "notif_salles";
      case "notifArtistesPush" -> "notif_artistes_push";
      case "notifSallesPush" -> "notif_salles_push";
      case "theme" -> "theme";
      default -> throw new IllegalArgumentException("Champ inconnu : " + champJava);
    };
  }

  private String normaliserDept(String d) {
    String valeur = d.toUpperCase();
    if (valeur.matches("2[AB]")) return "20";
    if (FORMAT_DEPT.matcher(valeur).matches()) return valeur;
    return null;
  }

  /// Résultat de la mise à jour des départements.
  public record ResultatMajDepartements(List<String> ignores) {}
}

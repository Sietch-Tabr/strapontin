/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Service de gestion de la configuration utilisateur ({@code user_config}).
 *
 * <p>Couvre la lecture, la mise à jour des départements, notifications, filtres et thème.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@Service
public class UserConfigService {

  private static final Logger LOG = LoggerFactory.getLogger(UserConfigService.class);
  private static final Pattern FORMAT_DEPT = Pattern.compile("^\\d{2,3}$");
  private static final Set<String> FREQUENCES_VALIDES = Set.of("jamais", "temps_reel", "quotidien");
  private static final Set<String> THEMES_VALIDES = Set.of("auto", "clair", "sombre");
  private static final String CLE_USER_ID = "userId";
  private static final String CLE_DEPARTEMENTS = "departements";
  private static final String CLE_THEME = "theme";
  private static final String CHAMP_FREQUENCE_NOTIFICATIONS = "frequenceNotifications";
  private static final Map<String, String> CHAMPS_VERS_COLONNES =
      Map.ofEntries(
          Map.entry(CLE_DEPARTEMENTS, CLE_DEPARTEMENTS),
          Map.entry("filtreDisponibles", "filtre_disponibles"),
          Map.entry("frequenceNotifications", "frequence_notifications"),
          Map.entry("notifArtistes", "notif_artistes"),
          Map.entry("notifSalles", "notif_salles"),
          Map.entry("notifArtistesPush", "notif_artistes_push"),
          Map.entry("notifSallesPush", "notif_salles_push"),
          Map.entry(CLE_THEME, CLE_THEME),
          Map.entry("onboardingVu", "onboarding_vu"));

  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigService configService;
  private final LogAdminService logAdminService;

  /**
   * @param jdbc accès JDBC
   * @param configService lecture des clés applicatives (RESEND_API_KEY)
   * @param logAdminService journalisation des actions utilisateur
   */
  public UserConfigService(
      final NamedParameterJdbcTemplate jdbc,
      final ConfigService configService,
      final LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.configService = configService;
    this.logAdminService = logAdminService;
  }

  /**
   * Lit le filtre "concerts disponibles uniquement" de l'utilisateur.
   *
   * @param userId identifiant de l'utilisateur
   * @return {@code true} si seuls les concerts disponibles doivent être affichés (défaut)
   */
  public boolean lireFiltreDisponibles(final String userId) {
    final List<Boolean> lignes =
        jdbc.queryForList(
            "SELECT filtre_disponibles FROM user_config WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Boolean.class);
    return lignes.isEmpty() || lignes.get(0);
  }

  /**
   * Retourne la configuration complète de l'utilisateur.
   *
   * @param userId identifiant de l'utilisateur
   * @return la configuration, ou les valeurs par défaut si absente
   */
  public ConfigUtilisateur lireConfig(final String userId) {
    final List<ConfigUtilisateur> lignes =
        jdbc.query(
            """
            SELECT departements, filtre_disponibles, frequence_notifications,
                   notif_artistes, notif_salles, notif_artistes_push, notif_salles_push, theme
            FROM user_config
            WHERE user_id = :userId
            """,
            Map.of(CLE_USER_ID, userId),
            (rs, _) -> {
              final String[] depts = (String[]) rs.getArray(CLE_DEPARTEMENTS).getArray();
              return new ConfigUtilisateur(
                  Arrays.asList(depts),
                  rs.getBoolean("filtre_disponibles"),
                  rs.getString("frequence_notifications"),
                  rs.getBoolean("notif_artistes"),
                  rs.getBoolean("notif_salles"),
                  rs.getBoolean("notif_artistes_push"),
                  rs.getBoolean("notif_salles_push"),
                  rs.getString(CLE_THEME),
                  false);
            });

    final boolean resendDisponible = configService.lireConfig("RESEND_API_KEY").isPresent();

    if (lignes.isEmpty()) {
      return new ConfigUtilisateur(
          List.of(), true, "jamais", false, false, false, false, "auto", resendDisponible);
    }
    final ConfigUtilisateur base = lignes.get(0);
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

  /**
   * Met à jour les départements de l'utilisateur.
   *
   * <p>Les codes non reconnus sont retournés dans la liste {@code ignores}.
   *
   * @param userId identifiant de l'utilisateur
   * @param bruts liste brute des codes département (peuvent être invalides)
   * @return résultat avec la liste des codes ignorés
   */
  public ResultatMajDepartements majDepartements(final String userId, final List<String> bruts) {
    final List<String> depts =
        bruts.stream()
            .filter(s -> normaliserDept(s) != null)
            .map(this::normaliserDept)
            .distinct()
            .collect(Collectors.toList());

    final List<String> ignores = bruts.stream().filter(s -> normaliserDept(s) == null).toList();

    upsertConfig(userId, Map.of(CLE_DEPARTEMENTS, depts.toArray(String[]::new)));
    return new ResultatMajDepartements(ignores);
  }

  /**
   * Met à jour les préférences de notification.
   *
   * @param userId identifiant de l'utilisateur
   * @param requete données de la requête
   * @throws ErreurMetier si la fréquence est invalide ou aucun canal de notification sélectionné
   */
  public void majNotifications(final String userId, final RequeteNotifications requete) {
    if (!FREQUENCES_VALIDES.contains(requete.frequenceNotifications())) {
      throw new ErreurMetier("Valeur invalide.");
    }
    final boolean aucunCanal =
        !requete.notifArtistes()
            && !requete.notifSalles()
            && !requete.notifArtistesPush()
            && !requete.notifSallesPush();
    if (!"jamais".equals(requete.frequenceNotifications()) && aucunCanal) {
      throw new ErreurMetier(
          "Activez au moins un type de contenu pour recevoir des notifications.");
    }

    final Map<String, Object> champs = new HashMap<>();
    champs.put("frequenceNotifications", requete.frequenceNotifications());
    champs.put("notifArtistes", requete.notifArtistes());
    champs.put("notifSalles", requete.notifSalles());
    champs.put("notifArtistesPush", requete.notifArtistesPush());
    champs.put("notifSallesPush", requete.notifSallesPush());
    upsertConfig(userId, champs);

    LOG.info(
        "notifications modifiées userId={} frequence={} artistes={} salles={} artistesPush={}"
            + " sallesPush={}",
        userId,
        requete.frequenceNotifications(),
        requete.notifArtistes(),
        requete.notifSalles(),
        requete.notifArtistesPush(),
        requete.notifSallesPush());
    logAdminService.logAdmin(
        "info",
        "web",
        "notifications modifiées",
        Map.of(
            CLE_USER_ID,
            userId,
            "frequence",
            requete.frequenceNotifications(),
            "notifArtistes",
            requete.notifArtistes(),
            "notifSalles",
            requete.notifSalles(),
            "notifArtistesPush",
            requete.notifArtistesPush(),
            "notifSallesPush",
            requete.notifSallesPush()));
  }

  /**
   * Indique si l'utilisateur n'a pas encore vu la modale d'onboarding.
   *
   * @param userId identifiant de l'utilisateur
   * @return {@code true} si la modale doit être affichée (absence de ligne = jamais vue)
   */
  public boolean onboardingNonVu(final String userId) {
    final List<Boolean> lignes =
        jdbc.queryForList(
            "SELECT onboarding_vu FROM user_config WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Boolean.class);
    return lignes.isEmpty() || !lignes.get(0);
  }

  /**
   * Marque la modale d'onboarding comme vue pour l'utilisateur.
   *
   * @param userId identifiant de l'utilisateur
   */
  public void marquerOnboardingVu(final String userId) {
    upsertConfig(userId, Map.of("onboardingVu", true));
  }

  /**
   * Met à jour le filtre de disponibilité.
   *
   * @param userId identifiant de l'utilisateur
   * @param filtreDisponibles nouvelle valeur du filtre
   */
  public void majFiltres(final String userId, final boolean filtreDisponibles) {
    upsertConfig(userId, Map.of("filtreDisponibles", filtreDisponibles));
  }

  /**
   * Met à jour le thème de l'interface.
   *
   * @param userId identifiant de l'utilisateur
   * @param valeur nouvelle valeur du thème ({@code auto}, {@code clair} ou {@code sombre})
   * @throws ErreurMetier si la valeur du thème est invalide
   */
  public void majTheme(final String userId, final String valeur) {
    if (!THEMES_VALIDES.contains(valeur)) {
      throw new ErreurMetier("Valeur de thème invalide.");
    }
    jdbc.update(
        """
        INSERT INTO user_config (user_id, theme)
        VALUES (:userId, CAST(:theme AS theme_interface))
        ON CONFLICT (user_id) DO UPDATE SET theme = CAST(:theme AS theme_interface), updated_at = now()
        """,
        Map.of(CLE_USER_ID, userId, CLE_THEME, valeur));
  }

  private void upsertConfig(final String userId, final Map<String, Object> champs) {
    final StringBuilder setClauses = new StringBuilder();
    final Map<String, Object> params = new HashMap<>(champs);
    params.put(CLE_USER_ID, userId);

    for (final String cle : champs.keySet()) {
      if (setClauses.length() > 0) {
        setClauses.append(", ");
      }
      final String colonne = versColonne(cle);
      setClauses.append(colonne).append(" = ").append(versCast(cle, ":" + cle));
    }

    final List<String> colonnes =
        champs.keySet().stream().map(this::versColonne).collect(Collectors.toList());
    final List<String> placeholders =
        champs.keySet().stream().map(c -> versCast(c, ":" + c)).collect(Collectors.toList());

    colonnes.add(0, "user_id");
    placeholders.add(0, ":userId");

    final String sql =
        """
        INSERT INTO user_config (%s)
        VALUES (%s)
        ON CONFLICT (user_id) DO UPDATE SET %s, updated_at = now()
        """
            .formatted(String.join(", ", colonnes), String.join(", ", placeholders), setClauses);

    jdbc.update(sql, params);
  }

  private static String versCast(final String champJava, final String placeholder) {
    if (CHAMP_FREQUENCE_NOTIFICATIONS.equals(champJava)) {
      return "CAST(" + placeholder + " AS frequence_notif)";
    }
    return placeholder;
  }

  private String versColonne(final String champJava) {
    final String colonne = CHAMPS_VERS_COLONNES.get(champJava);
    if (colonne == null) {
      throw new IllegalArgumentException("Champ inconnu : " + champJava);
    }
    return colonne;
  }

  private String normaliserDept(final String dept) {
    final String valeur = dept.toUpperCase(Locale.ROOT);
    if (valeur.matches("2[AB]")) {
      return "20";
    }
    if (FORMAT_DEPT.matcher(valeur).matches()) {
      return valeur;
    }
    return null;
  }

  /** Résultat de la mise à jour des départements. */
  public record ResultatMajDepartements(List<String> ignores) {}
}

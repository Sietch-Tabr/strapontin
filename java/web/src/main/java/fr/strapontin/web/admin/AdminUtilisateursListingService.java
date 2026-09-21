/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Pagination des utilisateurs (dernière activité, compteurs de suivi)
 * pour l'onglet admin — extrait d'{@link AdminService}.
 * @since 2.9.1
 * @author Kwaite
 */
@Service
class AdminUtilisateursListingService {

  private static final int TAILLE_PAGE_UTILISATEURS = 50;

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * @param jdbc accès JDBC
   */
  /* package-private : instancié uniquement par AdminService (même package). */
  AdminUtilisateursListingService(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Retourne une page d'utilisateurs avec leur dernière activité et compteurs
   * de suivi.
   *
   * @param page numéro de page (0-indexé)
   * @return page d'utilisateurs triés par date de création
   */
  /* package-private : appelé par AdminService (même package). */
  ReponseUtilisateursAdmin lister(final int page) {
    final int pageNum = Math.max(0, page);
    final List<Map<String, Object>> pageUsers = chargerPageUtilisateurs(pageNum);

    final long total =
        pageUsers.isEmpty()
            ? compterUtilisateurs(pageNum)
            : ((Number) pageUsers.get(0).get("total_filtre")).longValue();

    final Map<String, Long> activiteParUsername = chargerActiviteParUsername();

    final List<UtilisateurAdmin> utilisateurs =
        pageUsers.stream().map(u -> assemblerUtilisateurAdmin(u, activiteParUsername)).toList();

    final int totalPages = Math.max(1, (int) Math.ceil((double) total / TAILLE_PAGE_UTILISATEURS));
    return new ReponseUtilisateursAdmin(utilisateurs, pageNum, totalPages, total);
  }

  private List<Map<String, Object>> chargerPageUtilisateurs(final int pageNum) {
    final Map<String, Object> paramsPage =
        Map.of("taille", TAILLE_PAGE_UTILISATEURS, "offset", pageNum * TAILLE_PAGE_UTILISATEURS);
    return jdbc.queryForList(
        """
        SELECT u.id, u.email, u.role::text, u.username, u.display_username,
               u.must_change_credentials, u.created_at, u.revocation_admin_le, u.verrouille,
               COUNT(DISTINCT us.salle_id) AS nb_salles,
               COUNT(DISTINCT ua.artiste_id) AS nb_artistes,
               COUNT(*) OVER() AS total_filtre
        FROM users u
        LEFT JOIN user_salles us ON us.user_id = u.id
        LEFT JOIN user_artistes ua ON ua.user_id = u.id
        GROUP BY u.id, u.email, u.role, u.username, u.display_username,
                 u.must_change_credentials, u.created_at, u.revocation_admin_le, u.verrouille
        ORDER BY u.created_at
        LIMIT :taille OFFSET :offset
        """,
        paramsPage);
  }

  private Map<String, Long> chargerActiviteParUsername() {
    final Map<String, Long> activiteParUsername = new HashMap<>();
    jdbc.query(
        """
        SELECT PRINCIPAL_NAME, MAX(LAST_ACCESS_TIME) as derniere
        FROM SPRING_SESSION
        WHERE PRINCIPAL_NAME IS NOT NULL
        GROUP BY PRINCIPAL_NAME
        """,
        Map.of(),
        (RowCallbackHandler)
            rs -> activiteParUsername.put(rs.getString("PRINCIPAL_NAME"), rs.getLong("derniere")));
    return activiteParUsername;
  }

  private static UtilisateurAdmin assemblerUtilisateurAdmin(
      final Map<String, Object> utilisateurBrut, final Map<String, Long> activiteParUsername) {
    final String username = (String) utilisateurBrut.get("username");
    final Long derniereMs = activiteParUsername.get(username);
    final Object revocationBrute = utilisateurBrut.get("revocation_admin_le");
    final Number nbSallesNum = (Number) utilisateurBrut.get("nb_salles");
    final Number nbArtistesNum = (Number) utilisateurBrut.get("nb_artistes");
    return new UtilisateurAdmin(
        (String) utilisateurBrut.get("id"),
        (String) utilisateurBrut.get("email"),
        (String) utilisateurBrut.get("role"),
        username,
        (String) utilisateurBrut.get("display_username"),
        (Boolean) utilisateurBrut.get("must_change_credentials"),
        ((Timestamp) utilisateurBrut.get("created_at")).toInstant(),
        derniereMs != null ? Instant.ofEpochMilli(derniereMs) : null,
        revocationBrute != null ? ((Timestamp) revocationBrute).toInstant() : null,
        nbSallesNum != null ? nbSallesNum.intValue() : 0,
        nbArtistesNum != null ? nbArtistesNum.intValue() : 0,
        (Boolean) utilisateurBrut.get("verrouille"));
  }

  // page vide (offset au-delà du total, ex. dernier utilisateur supprimé
  // depuis) : total_filtre n'est renvoyé par aucune ligne, un second
  // aller-retour reste alors nécessaire.
  private long compterUtilisateurs(final int pageNum) {
    if (pageNum == 0) {
      return 0;
    }
    final Long total = jdbc.queryForObject("SELECT COUNT(*) FROM users", Map.of(), Long.class);
    return total != null ? total : 0;
  }
}

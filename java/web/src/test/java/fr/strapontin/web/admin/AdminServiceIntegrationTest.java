/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import fr.strapontin.web.AbstractWebIntegrationTest;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.email.EmailServiceAdmin;
import fr.strapontin.web.email.EmailServiceSecurite;
import fr.strapontin.web.securite.VerificationRepository;
import java.sql.Timestamp;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Test d'intégration de {@link AdminService} avec PostgreSQL réel (Testcontainers).
 *
 * <p>Les tests mockés de {@code AdminServiceTest} ne peuvent pas reproduire l'épuisement du pool
 * HikariCP documenté en v2.6.4 (jdbc mocké, aucune vraie transaction) : ce test vérifie avec une
 * vraie connexion que le garde-fou rejette la combinaison nom/email + rétrogradation avant toute
 * écriture, et que la rétrogradation seule s'applique normalement.
 *
 * <p>Pas de {@code @SpringBootTest} : {@link AdminService} et {@link AdminRevocationService} sont
 * construits manuellement (cette dernière avec une vraie connexion, comme le permet son injection
 * {@code self} par setter, prévue pour les tests hors contexte Spring, pour que la révocation
 * différée écrive réellement en base), DataSource/Flyway montés à la main pour éviter la complexité
 * d'un contexte web complet (sécurité, sessions JDBC, etc.) non nécessaire pour ce test ciblé.
 *
 * @since 2.7.0
 * @author Kwaite
 */
class AdminServiceIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private AdminService service;

  @BeforeAll
  static void migrerBase() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());

    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new NamedParameterJdbcTemplate(dataSource);
  }

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM users", Map.of());
    final AdminRevocationService revocationService =
        new AdminRevocationService(
            jdbc, mock(EmailServiceAdmin.class), mock(LogAdminService.class));
    revocationService.setSelf(revocationService);
    final AdminModificationUtilisateurService modificationService =
        new AdminModificationUtilisateurService(
            jdbc, mock(LogAdminService.class), revocationService, mock(EmailServiceAdmin.class));
    service =
        new AdminService(
            jdbc,
            mock(EmailService.class),
            mock(EmailServiceAdmin.class),
            mock(EmailServiceSecurite.class),
            mock(AdminUtilisateursListingService.class),
            modificationService,
            mock(LogAdminService.class),
            mock(VerificationRepository.class));
  }

  private void insererAdmin(final String id) {
    jdbc.update(
        """
        INSERT INTO users (id, email, role, display_username)
        VALUES (:id, :email, CAST('admin' AS role_utilisateur), :id)
        """,
        Map.of("id", id, "email", id + "@test.fr"));
  }

  // combiner nom/email + rétrogradation imbriquerait initierRevocationAdminTransactionnel
  // (REQUIRES_NEW + SERIALIZABLE) dans la transaction déjà ouverte de modifierUtilisateur —
  // risque d'épuisement du pool HikariCP (incident v2.6.4). Vérifie avec une vraie connexion
  // qu'aucune écriture n'a lieu (ni le nom, ni le rôle) lorsque la combinaison est rejetée.
  @Test
  void modifierUtilisateur_nomEtRetrogradationCombines_rejetteSansEcrireEnBase() {
    insererAdmin("admin1");
    insererAdmin("admin2");
    final RequeteModifierUtilisateur requete =
        new RequeteModifierUtilisateur("Nouveau Nom", null, "user");

    assertThrows(
        ErreurMetier.class, () -> service.modifierUtilisateur("admin2", requete, "admin1"));

    final String nomEnBase =
        jdbc.queryForObject(
            "SELECT display_username FROM users WHERE id = :id",
            Map.of("id", "admin2"),
            String.class);
    final String roleEnBase =
        jdbc.queryForObject(
            "SELECT role::text FROM users WHERE id = :id", Map.of("id", "admin2"), String.class);
    assertNotEquals("Nouveau Nom", nomEnBase);
    assertEquals("admin", roleEnBase);
  }

  @Test
  void modifierUtilisateur_seulementRetrogradation_appliqueLaRevocationDifferee() {
    insererAdmin("admin3");
    insererAdmin("admin4");
    final RequeteModifierUtilisateur requete = new RequeteModifierUtilisateur(null, null, "user");

    assertDoesNotThrow(() -> service.modifierUtilisateur("admin4", requete, "admin3"));

    final Timestamp revocationLe =
        jdbc.queryForObject(
            "SELECT revocation_admin_le FROM users WHERE id = :id",
            Map.of("id", "admin4"),
            Timestamp.class);
    assertNotNull(revocationLe, "la révocation différée doit être planifiée");
    final String roleEnBase =
        jdbc.queryForObject(
            "SELECT role::text FROM users WHERE id = :id", Map.of("id", "admin4"), String.class);
    assertEquals("admin", roleEnBase, "le rôle ne change qu'à l'échéance (RevoquerAdminsJob)");
  }
}

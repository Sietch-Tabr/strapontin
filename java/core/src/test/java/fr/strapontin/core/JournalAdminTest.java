/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests unitaires pour {@link JournalAdmin} ; le comportement du SQL sur une
 * vraie base est contrôlé par les tests d'intégration du worker.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class JournalAdminTest {

  private NamedParameterJdbcTemplate jdbc;
  private JournalAdmin journal;

  @BeforeEach
  void preparer() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    journal = new JournalAdmin(jdbc, JsonMapper.builder().build());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parametres(final String requeteAttendue) {
    final ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    final ArgumentCaptor<String> requete = ArgumentCaptor.forClass(String.class);
    verify(jdbc).update(requete.capture(), captor.capture());
    assertTrue(requete.getValue().contains(requeteAttendue), requete.getValue());
    return captor.getValue();
  }

  @Test
  void ecrire_transmetNiveauSourceMessageEtMetaEnJson() {
    journal.ecrire("warn", "web", "message", Map.of("cle", "valeur"));
    final Map<String, Object> p = parametres("VALUES (:ts");
    assertEquals("warn", p.get("niveau"));
    assertEquals("web", p.get("source"));
    assertEquals("message", p.get("message"));
    assertEquals("{\"cle\":\"valeur\"}", p.get("meta"));
    assertInstanceOf(Timestamp.class, p.get("ts"));
  }

  @Test
  void ecrire_sansMeta_ecritUnNull() {
    journal.ecrire("info", "web", "message", null);
    assertNull(parametres("VALUES (:ts").get("meta"));
  }

  @Test
  void ecrireSansRepetition_utiliseLaRequeteQuiIgnoreLesAvertissementsRecents() {
    journal.ecrireSansRepetition("warn", "worker", "message", Map.of());
    final Map<String, Object> p = parametres("NOT EXISTS");
    assertEquals("{}", p.get("meta"));
  }

  @Test
  void ecrire_erreurDeBase_neFaitJamaisEchouerLAppelant() {
    doThrow(new DataAccessResourceFailureException("base indisponible"))
        .when(jdbc)
        .update(anyString(), any(Map.class));
    assertDoesNotThrow(() -> journal.ecrire("error", "web", "message", null));
    assertDoesNotThrow(() -> journal.ecrireSansRepetition("warn", "web", "message", null));
  }

  /** Objet dont la lecture d'une propriété échoue, donc impossible à sérialiser. */
  public static final class Inserialisable {
    /** @return jamais : lève toujours une exception */
    public String getPropriete() {
      throw new IllegalStateException("lecture impossible");
    }
  }

  @Test
  void ecrire_metaNonSerialisable_neFaitPasEchouerEtN_ecritRien() {
    final Map<String, Object> inserialisable = Map.of("objet", new Inserialisable());
    assertDoesNotThrow(() -> journal.ecrire("info", "web", "message", inserialisable));
    verify(jdbc, never()).update(anyString(), any(Map.class));
  }

  @Test
  void ecrireDansLaTransaction_erreurDeBase_remonte() {
    doThrow(new DataAccessResourceFailureException("base indisponible"))
        .when(jdbc)
        .update(anyString(), any(Map.class));
    assertThrows(
        DataAccessResourceFailureException.class,
        () -> journal.ecrireDansLaTransaction("warn", "admin", "message", Map.of()));
  }
}

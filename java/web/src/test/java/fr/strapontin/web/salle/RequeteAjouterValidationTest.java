/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de validation Bean Validation de {@link RequeteAjouter}.
 *
 * <p>Le {@code wikidataId} est concaténé dans une requête SPARQL exécutée par le job
 * d'enrichissement nocturne ({@code WikidataSalleSource}) — sans contrainte de format, une valeur
 * arbitraire pouvait casser la requête exécutée contre {@code query.wikidata.org}.
 *
 * @since 2.6.3
 * @author Kwaite
 */
class RequeteAjouterValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void wikidataId_null_estValide() {
    final Set<ConstraintViolation<RequeteAjouter>> violations =
        VALIDATOR.validate(new RequeteAjouter("Zénith Paris", "tm:venue-1", null, null));
    assertTrue(violations.isEmpty());
  }

  @Test
  void wikidataId_formatQIdValide_estValide() {
    final Set<ConstraintViolation<RequeteAjouter>> violations =
        VALIDATOR.validate(new RequeteAjouter("Zénith Paris", null, null, "Q3438008"));
    assertTrue(violations.isEmpty());
  }

  // une valeur du type "Q1. } SELECT..." casserait la clause VALUES SPARQL
  // (WikidataSalleSource.java:154-168) — doit être rejetée avant persistance.
  @Test
  void wikidataId_formatInvalide_estRejete() {
    final Set<ConstraintViolation<RequeteAjouter>> violations =
        VALIDATOR.validate(
            new RequeteAjouter("Zénith Paris", null, null, "Q1 . } SELECT ?x WHERE"));
    assertFalse(violations.isEmpty());
  }

  @Test
  void nom_vide_estRejete() {
    final Set<ConstraintViolation<RequeteAjouter>> violations =
        VALIDATOR.validate(new RequeteAjouter("", "tm:venue-1"));
    assertFalse(violations.isEmpty());
  }

  @Test
  void nom_null_estRejete() {
    final Set<ConstraintViolation<RequeteAjouter>> violations =
        VALIDATOR.validate(new RequeteAjouter(null, "tm:venue-1", null, null));
    assertFalse(violations.isEmpty());
  }

  // un nom de plusieurs Mo atteint DistanceTexte.levenshtein() (tableau int[m][n]) via
  // CorrespondanceSalle.estDoublonSalle — risque de OutOfMemoryError sans cette borne.
  @Test
  void nom_troplong_estRejete() {
    final Set<ConstraintViolation<RequeteAjouter>> violations =
        VALIDATOR.validate(new RequeteAjouter("A".repeat(201), "tm:venue-1"));
    assertFalse(violations.isEmpty());
  }

  @Test
  void nom_tailleLimite_estValide() {
    final Set<ConstraintViolation<RequeteAjouter>> violations =
        VALIDATOR.validate(new RequeteAjouter("A".repeat(200), "tm:venue-1"));
    assertTrue(violations.isEmpty());
  }
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LimiteurDebitTest {

  /** Horloge réglable à la main. */
  private static final class HorlogeManuelle extends Clock {
    private Instant instant = Instant.parse("2026-10-17T10:00:00Z");

    void avancer(final Duration duree) {
      instant = instant.plus(duree);
    }

    @Override
    public Instant instant() {
      return instant;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final java.time.ZoneId zone) {
      return this;
    }
  }

  private static LimiteurDebit limiteur(final int capacite, final Clock horloge) {
    return new LimiteurDebit(capacite, Duration.ofHours(1), Duration.ofHours(2), 100, horloge);
  }

  @Test
  void autoriser_quotaEpuise_refuse() {
    final LimiteurDebit limiteur = limiteur(2, new HorlogeManuelle());
    assertTrue(limiteur.autoriser("a"));
    assertTrue(limiteur.autoriser("a"));
    assertFalse(limiteur.autoriser("a"));
  }

  @Test
  void autoriser_clesDifferentes_quotasIndependants() {
    final LimiteurDebit limiteur = limiteur(1, new HorlogeManuelle());
    assertTrue(limiteur.autoriser("a"));
    assertTrue(limiteur.autoriser("b"));
    assertFalse(limiteur.autoriser("a"));
  }

  @Test
  void reinitialiser_apresEpuisement_reautorise() {
    final LimiteurDebit limiteur = limiteur(1, new HorlogeManuelle());
    limiteur.autoriser("a");
    assertFalse(limiteur.autoriser("a"));
    limiteur.reinitialiser();
    assertTrue(limiteur.autoriser("a"));
  }

  @Test
  void autoriser_rafaleEnFinPuisDebutDeFenetre_nePasseJamaisPlusQueLaCapacite() {
    final HorlogeManuelle horloge = new HorlogeManuelle();
    final LimiteurDebit limiteur = limiteur(3, horloge);
    horloge.avancer(Duration.ofMinutes(50));
    for (int i = 0; i < 3; i++) {
      assertTrue(limiteur.autoriser("a"));
    }
    // 20 minutes plus tard : l'ancienne borne de fenêtre fixe est passée, pas la fenêtre glissante
    horloge.avancer(Duration.ofMinutes(20));
    assertFalse(limiteur.autoriser("a"));
  }

  @Test
  void autoriser_fenetreEcoulee_reautorise() {
    final HorlogeManuelle horloge = new HorlogeManuelle();
    final LimiteurDebit limiteur = limiteur(1, horloge);
    assertTrue(limiteur.autoriser("a"));
    horloge.avancer(Duration.ofMinutes(59));
    assertFalse(limiteur.autoriser("a"));
    horloge.avancer(Duration.ofMinutes(1));
    assertTrue(limiteur.autoriser("a"));
  }

  @Test
  void autoriser_requeteRefusee_nEstPasComptee() {
    final HorlogeManuelle horloge = new HorlogeManuelle();
    final LimiteurDebit limiteur = limiteur(1, horloge);
    assertTrue(limiteur.autoriser("a"));
    horloge.avancer(Duration.ofMinutes(30));
    assertFalse(limiteur.autoriser("a"));
    horloge.avancer(Duration.ofMinutes(30));
    assertTrue(limiteur.autoriser("a"));
  }

  @Test
  void estEpuise_cleInconnue_neCreePasDeSuiviEtRetourneFaux() {
    final LimiteurDebit limiteur = limiteur(1, new HorlogeManuelle());
    assertFalse(limiteur.estEpuise("a"));
    assertTrue(limiteur.autoriser("a"));
  }

  @Test
  void enregistrer_quotaAtteint_signaleLeSeuilUneSeuleFois() {
    final LimiteurDebit limiteur = limiteur(2, new HorlogeManuelle());
    assertFalse(limiteur.enregistrer("a"));
    assertTrue(limiteur.enregistrer("a"));
    assertTrue(limiteur.estEpuise("a"));
    assertFalse(limiteur.enregistrer("a"));
  }

  @Test
  void oublier_apresEpuisement_liberLaCle() {
    final LimiteurDebit limiteur = limiteur(1, new HorlogeManuelle());
    limiteur.enregistrer("a");
    assertTrue(limiteur.estEpuise("a"));
    limiteur.oublier("a");
    assertFalse(limiteur.estEpuise("a"));
  }
}

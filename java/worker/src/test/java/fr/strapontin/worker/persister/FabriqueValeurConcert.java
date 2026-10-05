/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.PositionLieu;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Construction de {@link ValeurConcert} par champs nommés pour les tests, valeurs par défaut
 * pour les champs obligatoires non renseignés.
 *
 * @since 3.0.2
 * @author Kwaite
 */
final class FabriqueValeurConcert {

  private final ConcertBrut.Constructeur brut =
      ConcertBrut.nouveau().artiste("Artiste").salle("Salle").source("ticketmaster");
  private String artisteNorm;
  private LocalDate date = LocalDate.now().plusMonths(2);
  private LocalTime heure;
  private String salleNorm;
  private UUID salleId;
  private UUID artisteId;

  private FabriqueValeurConcert() {}

  static FabriqueValeurConcert nouveau() {
    return new FabriqueValeurConcert();
  }

  FabriqueValeurConcert artiste(final String valeur) {
    brut.artiste(valeur);
    return this;
  }

  FabriqueValeurConcert artisteNorm(final String valeur) {
    artisteNorm = valeur;
    return this;
  }

  FabriqueValeurConcert date(final LocalDate valeur) {
    date = valeur;
    return this;
  }

  FabriqueValeurConcert heure(final LocalTime valeur) {
    heure = valeur;
    return this;
  }

  FabriqueValeurConcert salle(final String valeur) {
    brut.salle(valeur);
    return this;
  }

  FabriqueValeurConcert salleNorm(final String valeur) {
    salleNorm = valeur;
    return this;
  }

  FabriqueValeurConcert ville(final String valeur) {
    brut.ville(valeur);
    return this;
  }

  FabriqueValeurConcert dept(final String valeur) {
    brut.dept(valeur);
    return this;
  }

  FabriqueValeurConcert url(final String valeur) {
    brut.url(valeur);
    return this;
  }

  FabriqueValeurConcert source(final String valeur) {
    brut.source(valeur);
    return this;
  }

  FabriqueValeurConcert classification(final String valeur) {
    brut.classification(valeur);
    return this;
  }

  FabriqueValeurConcert genre(final String valeur) {
    brut.genre(valeur);
    return this;
  }

  FabriqueValeurConcert statut(final String valeur) {
    brut.statut(valeur);
    return this;
  }

  FabriqueValeurConcert presaleDebut(final String valeur) {
    brut.presaleDebut(valeur);
    return this;
  }

  FabriqueValeurConcert presaleNom(final String valeur) {
    brut.presaleNom(valeur);
    return this;
  }

  FabriqueValeurConcert salleSourceId(final String valeur) {
    brut.salleSourceId(valeur);
    return this;
  }

  FabriqueValeurConcert artisteSourceId(final String valeur) {
    brut.artisteSourceId(valeur);
    return this;
  }

  FabriqueValeurConcert salleId(final UUID valeur) {
    salleId = valeur;
    return this;
  }

  FabriqueValeurConcert artisteId(final UUID valeur) {
    artisteId = valeur;
    return this;
  }

  FabriqueValeurConcert position(final PositionLieu valeur) {
    brut.position(valeur);
    return this;
  }

  ValeurConcert construire() {
    final ConcertBrut concert =
        brut.date(date.toString()).heure(heure != null ? heure.toString() : null).construire();
    final ValeurConcert initiale = ValeurConcert.depuis(concert);
    return new ValeurConcert(
        concert,
        artisteNorm != null ? artisteNorm : initiale.artisteNorm(),
        date,
        heure,
        salleNorm != null ? salleNorm : initiale.salleNorm(),
        salleId,
        artisteId);
  }
}

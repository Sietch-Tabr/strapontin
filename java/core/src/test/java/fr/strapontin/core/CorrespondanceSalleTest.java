/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/// Tests unitaires de {@link CorrespondanceSalle#estDoublonSalle} — dédoublonnage de salles.
///
/// <p>Noms passés déjà normalisés (via {@link Normalisation#normaliserTexte}), comme les
/// consommateurs réels de cette classe ({@code ReconciliateurSalles}, {@code WikidataSalleSource}).
///
/// @since 2.7.2
/// @author Kwaite
class CorrespondanceSalleTest {

  // --- Faux positifs pivot confirmés, devant devenir false après l'exclusion des
  // mots-types génériques ---

  @Test
  void estDoublonSalle_theatresParisiensDistinctsPartageantLeMotTheatre_retourneFalse() {
    assertFalse(
        CorrespondanceSalle.estDoublonSalle(
            "theatre rive gauche paris 14", "theatre le bout paris 09"));
  }

  @Test
  void estDoublonSalle_chateauxDistinctsPartageantLeMotChateau_retourneFalse() {
    assertFalse(
        CorrespondanceSalle.estDoublonSalle(
            "carre d argent pont chateau", "chateau de sollies pont"));
  }

  // --- Limites connues (documentées, pas des régressions) ---

  // "Casino - Barriere" Toulouse/Lille : contrairement à theatre/chateau ci-dessus, ce cas
  // ne passe pas par le critère pivot — les deux noms bruts ("Casino - Barriere" dans les deux
  // villes, seule la localisation des concerts diffère) sont déjà quasi identiques textuellement
  // (Levenshtein ≥ 0.75, critère 1), indépendamment de tout mot-type générique. L'exclusion des
  // mots génériques ne peut rien y faire ; c'est le filtre dept qui protège ce cas dans le flux
  // réel (Toulouse=31, Lille=59, départements distincts).
  @Test
  void estDoublonSalle_casinosDeVillesDifferentesTextesQuasiIdentiques_resteTrueSansFiltreDept() {
    assertTrue(
        CorrespondanceSalle.estDoublonSalle("casino barriere toulouse", "casino barriere lille"));
  }

  // "Centre des Congrès Angers"/"Centre des Congrès Jonzac" : le token "des" (3
  // caractères) gonfle le Jaccard global à 0.6 (> SEUIL_JACCARD_SALLE=0.5), indépendamment du
  // critère pivot — l'exclusion de mots génériques n'agit que sur le pivot, pas sur ce
  // critère. Reste donc true au niveau de cette fonction pure ; protégé dans le flux réel par le
  // filtre dept (Angers=49, Jonzac=17, départements distincts, filtré en amont côté SQL avant
  // d'atteindre cette comparaison). Revoir LONGUEUR_MIN_TOKEN_JACCARD est explicitement hors
  // scope (risque de régression sur d'autres tokens courts légitimes, non évalué).
  @Test
  void estDoublonSalle_centresDeCongresDeptDistinctsAvecToken_desPartage_resteTrueSansFiltreDept() {
    assertTrue(
        CorrespondanceSalle.estDoublonSalle(
            "centre des congres angers", "centre des congres jonzac"));
  }

  // "Le Rockstore Montpellier"/"Le Corum - Montpellier" : le pivot "montpellier" (11
  // caractères) n'est pas un mot-type de salle générique (c'est un nom de ville) — hors scope de
  // la liste d'exclusion (nécessiterait une liste de communes françaises, non demandée). Reste
  // également hors de portée du filtre dept : les deux salles sont dans le même département
  // (34). Faux positif connu et non corrigé.
  @Test
  void
      estDoublonSalle_sallesMontpellieraisesDistinctesPartageantLeNomDeVille_resteTrueLimiteConnue() {
    assertTrue(
        CorrespondanceSalle.estDoublonSalle("le rockstore montpellier", "le corum montpellier"));
  }

  // --- Non-régression : cas légitimes déjà documentés, doivent rester true ---

  @Test
  void estDoublonSalle_variantesKursaalBesancon_resteTrue() {
    // variante Eventim "Grand Kursaal"/"Kursaal Besançon" — pivot "kursaal" (7 car.),
    // pas un mot-type générique, ne doit pas être affecté par l'exclusion.
    assertTrue(CorrespondanceSalle.estDoublonSalle("grand kursaal", "kursaal besancon"));
  }

  @Test
  void estDoublonSalle_zenithParisLaVillette_resteTrue() {
    assertTrue(
        CorrespondanceSalle.estDoublonSalle(
            "zenith paris la villette", "zenith de paris la villette"));
  }

  @Test
  void estDoublonSalle_olympiaApostropheOuEspace_resteTrue() {
    // "l olympia" (apostrophe substituée par un espace) vs "olympia" seul.
    assertTrue(CorrespondanceSalle.estDoublonSalle("l olympia", "olympia"));
  }

  @Test
  void estDoublonSalle_olympiaSansApostropheOmiseParUneSource_resteTrue() {
    // critère d'égalité sans espaces — "lolympia" (apostrophe totalement omise) vs
    // "l olympia" (apostrophe substituée par un espace).
    assertTrue(CorrespondanceSalle.estDoublonSalle("lolympia", "l olympia"));
  }

  @Test
  void estDoublonSalle_villesDistinctesAvecMemeMotTypeZenith_retourneFalse() {
    // Non-régression générale : "zenith" (6 car., sous LONGUEUR_MIN_PIVOT=7) ne doit de toute
    // façon jamais suffire seul comme pivot entre deux villes différentes.
    assertFalse(CorrespondanceSalle.estDoublonSalle("zenith bordeaux", "zenith caen"));
  }

  // --- Mots-types génériques exclus du Jaccard/pivot, pas seulement du pivot ---

  // Reste vrai via le critère Levenshtein (containment), indépendant des mots-types génériques —
  // même limite connue que "casino barriere"/"centre des congres" plus haut, protégée par le
  // filtre dept en amont plutôt que par cette fonction seule.
  @Test
  void estDoublonSalle_theatresAntiquesDeVillesDifferentes_resteTrueLimiteConnue() {
    assertTrue(
        CorrespondanceSalle.estDoublonSalle("theatre antique orange", "theatre antique vienne"));
  }

  @Test
  void estDoublonSalle_parcsExpositionsDeVillesDifferentes_retourneFalse() {
    assertFalse(
        CorrespondanceSalle.estDoublonSalle(
            "forum du parc des expositions", "parc des expositions juraparc"));
  }

  @Test
  void estDoublonSalle_palaisDesCongresDeVillesDifferentes_retourneFalse() {
    assertFalse(
        CorrespondanceSalle.estDoublonSalle(
            "palais des congres", "palais des congres la baule escoublac"));
  }

  // Même limite connue que theatresAntiques ci-dessus : le containment de préfixe l'emporte
  // indépendamment de l'exclusion "municipal" (qui neutralise bien le pivot).
  @Test
  void estDoublonSalle_theatresMunicipauxDeVillesDifferentes_resteTrueLimiteConnue() {
    assertTrue(
        CorrespondanceSalle.estDoublonSalle("theatre municipal", "theatre municipal jean alary"));
  }

  @Test
  void estDoublonSalle_coliseesDeVillesDifferentes_retourneFalse() {
    assertFalse(CorrespondanceSalle.estDoublonSalle("le colisee roubaix", "le colisee chartres"));
  }

  // --- Containment de tokens partiel asymétrique : mot inséré au milieu d'un token ---

  @Test
  void estDoublonSalle_motInsereAuMilieuDUnToken_retourneTrue() {
    // "hotels" concaténé sans espace forme un unique token "accorhotels", jamais égal ni partagé
    // avec "accor" — échappe aux quatre autres critères.
    assertTrue(CorrespondanceSalle.estDoublonSalle("accor arena", "accorhotels arena"));
  }

  @Test
  void estDoublonSalle_containmentPartielSansAucunTokenSpecifiquePartage_retourneFalse() {
    assertFalse(CorrespondanceSalle.estDoublonSalle("accor arena", "zenith bordeaux"));
  }
}

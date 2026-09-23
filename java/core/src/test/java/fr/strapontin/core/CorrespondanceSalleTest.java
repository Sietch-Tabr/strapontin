/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de {@link CorrespondanceSalle#estDoublonSalle} — dédoublonnage de salles.
 *
 * <p>Noms passés déjà normalisés (via {@link Normalisation#normaliserTexte}), comme les
 * consommateurs réels de cette classe ({@code ReconciliateurSalles}, {@code WikidataSalleSource}).
 *
 * @since 2.7.2
 * @author Kwaite
 */
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

  // Régression #1204 : "lessaion" (Eventim, apostrophe omise dans "L'Essaion") contient
  // trivialement l'article "les" — sans garde de longueur côté nom long, ce token de 3 caractères
  // validait un containment entre deux salles parisiennes réelles et distinctes.
  @Test
  void estDoublonSalle_articleCourtIssuDUnNomMalDesapostrophe_retourneFalse() {
    assertFalse(
        CorrespondanceSalle.estDoublonSalle(
            "les trois baudets paris 18", "theatre de lessaion paris 04"));
  }

  // --- Containment partiel : garde-fou sur le nombre de tokens spécifiques (pas bruts) ---

  // "Casino de Paris" ne conserve qu'un seul token spécifique ("paris") une fois "casino" retiré
  // comme mot-type générique — ce token unique, un simple nom de ville, ne doit jamais suffire
  // seul à faire matcher un lieu totalement sans rapport au seul motif qu'il contient aussi
  // "paris" comme token (arrondissement/ville partagés par des dizaines de salles distinctes).
  @Test
  void estDoublonSalle_casinoDeParisEtTheatreSansRapportPartageantLeToken_paris_retourneFalse() {
    assertFalse(
        CorrespondanceSalle.estDoublonSalle("casino de paris", "theatre rive gauche paris 14"));
  }

  @Test
  void estDoublonSalle_casinoDeParisEtParvisEgliseSansRapport_retourneFalse() {
    assertFalse(
        CorrespondanceSalle.estDoublonSalle(
            "casino de paris", "parvis eglise saint germain paris 06"));
  }

  @Test
  void estDoublonSalle_casinoDeParisEtCathedraleAmericaineSansRapport_retourneFalse() {
    assertFalse(
        CorrespondanceSalle.estDoublonSalle("casino de paris", "cathedrale americaine paris 08"));
  }

  @Test
  void estDoublonSalle_casinoDeParisEtCathedraleNotreDameSansRapport_retourneFalse() {
    assertFalse(
        CorrespondanceSalle.estDoublonSalle("casino de paris", "cathedrale notre dame de paris"));
  }

  @Test
  void estDoublonSalle_casinoDeParisEtSunsetSunsideSansRapport_retourneFalse() {
    assertFalse(CorrespondanceSalle.estDoublonSalle("casino de paris", "sunset sunside paris 01"));
  }

  // Non-régression : le vrai Casino de Paris reste bien détecté comme doublon de lui-même via un
  // autre critère (containment de caractères, critère 1) — le garde-fou ajouté ne doit affaiblir
  // que le containment de tokens spécifiques, pas les autres critères.
  @Test
  void estDoublonSalle_casinoDeParisEtLuiMemeAvecSuffixeArrondissement_resteTrue() {
    assertTrue(CorrespondanceSalle.estDoublonSalle("casino de paris", "casino de paris paris 09"));
  }

  // --- pivotAutorise=false : critère pivot désactivé (adjectif générique sans localisation) ---

  @Test
  void estDoublonSalle_pivotAutorise_adjectifGeneriqueCommun_resteTrue() {
    // "nouvelle" (8 car., ≥ seuil pivot 7) déclenche le critère pivot par défaut — comportement
    // inchangé tant que pivotAutorise n'est pas explicitement désactivé.
    assertTrue(CorrespondanceSalle.estDoublonSalle("la nouvelle vague", "la nouvelle republique"));
  }

  @Test
  void estDoublonSalle_pivotInterdit_adjectifGeneriqueCommun_retourneFalse() {
    // "La Nouvelle Vague" (Saint-Malo) ne doit pas être confondue avec "La Nouvelle République"
    // (un journal) sur le seul pivot "nouvelle" — aucune donnée de localisation pour trancher.
    assertFalse(
        CorrespondanceSalle.estDoublonSalle("la nouvelle vague", "la nouvelle republique", false));
  }

  @Test
  void estDoublonSalle_pivotInterdit_autresCriteresResteInchanges() {
    // Le retrait du critère pivot ne doit affaiblir aucun des quatre autres critères.
    assertTrue(CorrespondanceSalle.estDoublonSalle("grand kursaal", "kursaal besancon", true));
    assertFalse(CorrespondanceSalle.estDoublonSalle("grand kursaal", "kursaal besancon", false));
    assertTrue(CorrespondanceSalle.estDoublonSalle("l olympia", "olympia", false));
    assertFalse(CorrespondanceSalle.estDoublonSalle("zenith bordeaux", "zenith caen", false));
  }

  @Test
  void estDoublonSalle_containmentInterdit_prefixeStrictSansLocalisation_retourneFalse() {
    // "la prade" (Pradines, salle réelle) est un préfixe strict de "la pradella" (une localité
    // catalane sans rapport) : le containment de caractères seul ne doit plus suffire sans
    // signal de localisation indépendant.
    assertFalse(CorrespondanceSalle.estDoublonSalle("la prade", "la pradella", false));
  }

  @Test
  void estDoublonSalle_containmentAutorise_prefixeStrict_resteTrue() {
    // Comportement inchangé quand pivotAutorise vaut true (défaut, réconciliation intra-lot).
    assertTrue(CorrespondanceSalle.estDoublonSalle("la prade", "la pradella"));
  }

  // --- "eglise"/"saint" génériques : deux édifices religieux distincts sans rapport ---

  @Test
  void estDoublonSalle_eglisesParisiennesDistinctesPartageantEgliseSaint_retourneFalse() {
    assertFalse(
        CorrespondanceSalle.estDoublonSalle(
            "eglise saint ephrem paris 05", "eglise saint eustache de paris"));
  }

  @Test
  void estDoublonSalle_eglisesSaintPaulEtSaintLouisSansRapport_retourneFalse() {
    assertFalse(
        CorrespondanceSalle.estDoublonSalle(
            "eglise saint paul saint louis", "eglise saint louis en l ile"));
  }

  // --- "chapelle" générique : deux lieux parisiens distincts (prod, #1239) ---

  @Test
  void estDoublonSalle_sainteChapelleEtChapelleSaintSymphorienSansRapport_retourneFalse() {
    assertFalse(CorrespondanceSalle.estDoublonSalle("sainte chapelle", "chapelle st symphorien"));
  }

  @Test
  void estDoublonSalle_laSainteChapelleParis01EtSainteChapelle_containmentRestePositif() {
    // le vrai match légitime (nom Eventim complet contenant le nom TM court) ne doit jamais être
    // cassé par le retrait de "chapelle" des tokens spécifiques — passe par containment de
    // caractères, testé avant tout calcul de tokens.
    assertTrue(
        CorrespondanceSalle.estDoublonSalle("la sainte chapelle paris 01", "sainte chapelle"));
  }

  // --- qualificatifsDivergents : complexe multi-salles (La Seine Musicale) ---

  @Test
  void qualificatifsDivergents_qualificatifsDistinctsMemeComplexe_retourneTrue() {
    assertTrue(
        CorrespondanceSalle.qualificatifsDivergents(
            "La Seine Musicale - Grande Seine", "Seine Musicale - Auditorium P.devedjian"));
  }

  @Test
  void qualificatifsDivergents_memeQualificatif_retourneFalse() {
    assertFalse(
        CorrespondanceSalle.qualificatifsDivergents(
            "La Seine Musicale - Grande Seine", "Seine Musicale - Grande Seine"));
  }

  @Test
  void qualificatifsDivergents_unSeulNomQualifie_retourneFalse() {
    assertFalse(
        CorrespondanceSalle.qualificatifsDivergents("La Seine Musicale - Grande Seine", "Zenith"));
  }

  @Test
  void qualificatifsDivergents_aucunQualificatif_retourneFalse() {
    assertFalse(CorrespondanceSalle.qualificatifsDivergents("Zenith Paris", "Zenith de Paris"));
  }

  @Test
  void extraireBase_nomQualifie_retourneLaPartieComplexe() {
    assertEquals(
        "La Seine Musicale", CorrespondanceSalle.extraireBase("La Seine Musicale - Grande Seine"));
  }

  @Test
  void extraireBase_nomSansQualificatif_retourneNull() {
    assertNull(CorrespondanceSalle.extraireBase("Zenith Paris"));
  }
}

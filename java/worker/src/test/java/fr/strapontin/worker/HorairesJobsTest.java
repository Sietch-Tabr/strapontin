/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.core.HorairesJobs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.quartz.CronExpression;

/**
 * Registre des horaires : expressions valides, ordre des chaînes nocturnes et
 * documentation du worker identique.
 */
class HorairesJobsTest {

  private static final String DEBUT = "<!-- horaires-jobs:debut -->";
  private static final String FIN = "<!-- horaires-jobs:fin -->";

  @Test
  void horaires_toutesLesExpressionsDuWorker_sontValidesPourQuartz() {
    for (final HorairesJobs.Horaire h : HorairesJobs.WORKER) {
      assertTrue(CronExpression.isValidExpression(h.cron()), h.type() + " : " + h.cron());
    }
  }

  @Test
  void horaires_toutesLesExpressionsDuWeb_sontValidesPourSpring() {
    for (final HorairesJobs.Horaire h : HorairesJobs.WEB) {
      assertTrue(
          org.springframework.scheduling.support.CronExpression.isValidExpression(h.cron()),
          h.type() + " : " + h.cron());
    }
  }

  private static int minutesDepuisMinuit(final String type) {
    final String cron =
        HorairesJobs.WORKER.stream()
            .filter(h -> h.type().equals(type))
            .findFirst()
            .orElseThrow()
            .cron();
    final String[] champs = cron.split(" ");
    return Integer.parseInt(champs[2]) * 60 + Integer.parseInt(champs[1]);
  }

  // Les purges s'enchaînent dans cet ordre : une salle n'est orpheline qu'une fois ses
  // concerts supprimés, un artiste qu'une fois ses salles.
  @Test
  void horaires_chaineDesPurges_estDansLOrdre() {
    final List<Integer> minutes =
        List.of("purger:concerts", "purger:salles", "purger:artistes", "purger:logs-admin").stream()
            .map(HorairesJobsTest::minutesDepuisMinuit)
            .toList();

    assertEquals(minutes.stream().sorted().distinct().toList(), minutes);
  }

  @Test
  void horaires_purgesAvantRecuperationEtReconciliation_economisentLeTravail() {
    assertTrue(minutesDepuisMinuit("purger:concerts") < minutesDepuisMinuit("fetch:region"));
    assertTrue(
        minutesDepuisMinuit("fetch:region") < minutesDepuisMinuit("reconciliation:nocturne"));
  }

  // Le 25 octobre la plage 02:00-02:59 existe deux fois, le 28 mars jamais : un job quotidien
  // y serait manqué, doublé ou reporté à 03:00 contre un autre.
  @Test
  void horaires_aucunJobQuotidienDansLaPlageDuChangementDHeure() {
    for (final HorairesJobs.Horaire h : HorairesJobs.WORKER) {
      final String[] champs = h.cron().split(" ");
      final boolean quotidien = !"*".equals(champs[2]) && !champs[2].contains("/");
      if (quotidien) {
        for (final String heure : champs[2].split(",")) {
          assertTrue(!"2".equals(heure), h.type() + " planifié dans la plage 02:00-02:59");
        }
      }
    }
  }

  @Test
  void horaires_recuperationEtReconciliation_gardentUneHeureDEcart() {
    assertTrue(
        minutesDepuisMinuit("reconciliation:nocturne") - minutesDepuisMinuit("fetch:region")
            >= 60);
  }

  @Test
  void horaires_surchargesDistinctes_neSontDeclareesQuUneFois() {
    final Map<String, Long> parPropriete =
        HorairesJobs.WORKER.stream()
            .filter(h -> h.surcharge() != null)
            .collect(Collectors.groupingBy(HorairesJobs.Horaire::surcharge, Collectors.counting()));

    assertTrue(parPropriete.values().stream().allMatch(n -> n == 1L), parPropriete.toString());
  }

  // La documentation reprend le tableau du registre à l'identique : un horaire changé dans le
  // code sans la documentation fait échouer ce test, qui affiche le tableau attendu.
  @Test
  void documentation_tableauDesHoraires_correspondAuRegistre() throws IOException {
    final String doc = Files.readString(Path.of("src/main/java/fr/strapontin/worker/CLAUDE.md"));
    final int debut = doc.indexOf(DEBUT);
    final int fin = doc.indexOf(FIN);
    assertTrue(debut >= 0 && fin > debut, "repères " + DEBUT + " et " + FIN + " absents");

    final String documente = doc.substring(debut + DEBUT.length(), fin);

    assertEquals(normaliser(HorairesJobs.tableau()), normaliser(documente));
  }

  // Le formateur aligne les colonnes du tableau : seul le contenu des cellules compte.
  private static List<String> normaliser(final String tableau) {
    return tableau
        .lines()
        .filter(ligne -> !ligne.isBlank())
        .map(
            ligne ->
                java.util.Arrays.stream(ligne.split("\\|", -1))
                    .map(String::strip)
                    .map(cellule -> cellule.matches("-+") ? "---" : cellule)
                    .collect(Collectors.joining("|")))
        .toList();
  }
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EmailTemplatesTest {

  // ---- e() — échappement HTML ----

  @Test
  void e_null_retourneChainVide() {
    assertThat(EmailTemplates.e(null)).isEmpty();
  }

  @Test
  void e_caracteresSpeciaux_sontEchappes() {
    assertThat(EmailTemplates.e("<script>alert('xss' & \"ok\")"))
        .isEqualTo("&lt;script&gt;alert(&#39;xss&#39; &amp; &quot;ok&quot;)");
  }

  @Test
  void e_chaineNormale_passeSansModification() {
    assertThat(EmailTemplates.e("Strapontin 2026")).isEqualTo("Strapontin 2026");
  }

  // ---- invitation() ----

  @Test
  void invitation_contientLienEtBouton() {
    String html = EmailTemplates.invitation("https://exemple.fr/reset/abc", "https://app.fr");
    assertThat(html).contains("href=\"https://exemple.fr/reset/abc\"");
    assertThat(html).contains("Créer mon compte");
    assertThat(html).contains("identifiant");
    assertThat(html).contains("https://app.fr/parametres");
    assertThat(html).contains("color: #111");
  }

  // ---- resetMotDePasse() ----

  @Test
  void resetMotDePasse_contientLienReset() {
    String html = EmailTemplates.resetMotDePasse("https://exemple.fr/reset/xyz", "https://app.fr");
    assertThat(html).contains("href=\"https://exemple.fr/reset/xyz\"");
    assertThat(html).contains("Réinitialiser mon mot de passe");
    assertThat(html).contains("1 heure");
  }

  // ---- confirmationEmail() ----

  @Test
  void confirmationEmail_contientLienEtMessage() {
    String html =
        EmailTemplates.confirmationEmail("https://exemple.fr/confirm/abc", "https://app.fr");
    assertThat(html).contains("href=\"https://exemple.fr/confirm/abc\"");
    assertThat(html).contains("Confirmer ma nouvelle adresse");
    assertThat(html).contains("24 heures");
  }

  // ---- signalement() ----

  @Test
  void signalement_contientToutesLesDonnees() {
    String html =
        EmailTemplates.signalement(
            "Bug", "alice@exemple.fr", "Le bouton ne fonctionne pas.", "https://app.fr");
    assertThat(html).contains("alice@exemple.fr");
    assertThat(html).contains("Le bouton ne fonctionne pas.");
    assertThat(html).contains("Signalement — Bug");
  }

  @Test
  void signalement_messageAvecBalises_estEchappe() {
    String html =
        EmailTemplates.signalement(
            "Bug", "bob@ex.fr", "<b>injection</b> & 'test'", "https://app.fr");
    assertThat(html).contains("&lt;b&gt;injection&lt;/b&gt; &amp; &#39;test&#39;");
    assertThat(html).doesNotContain("<b>injection</b>");
  }

  // ---- digestConcerts() ----

  @Test
  void digestConcerts_afficheTousConcerts() {
    var lignes =
        List.of(
            new LigneConcert("Daft Punk", "samedi 1 mars 2025", "AccorHotels Arena", null),
            new LigneConcert("Téléphone", "dimanche 2 mars 2025", "Zénith Paris", null));
    String html = EmailTemplates.digestConcerts("2 nouveaux concerts", lignes, "https://app.fr");
    assertThat(html).contains("Daft Punk");
    assertThat(html).contains("Téléphone");
    assertThat(html).contains("samedi 1 mars 2025");
    assertThat(html).contains("AccorHotels Arena");
    assertThat(html).contains("2 nouveaux concerts");
    assertThat(html).contains("Voir mes spectacles");
  }

  @Test
  void digestConcerts_concertAvecUrl_afficheLienBillets() {
    var lignes =
        List.of(
            new LigneConcert("Stromae", "lundi 3 mars 2025", "Bercy", "https://billets.fr/123"));
    String html = EmailTemplates.digestConcerts("1 nouveau concert", lignes, "https://app.fr");
    assertThat(html).contains("Billets →");
    assertThat(html).contains("href=\"https://billets.fr/123\"");
  }

  @Test
  void digestConcerts_concertSansUrl_nAffichePasLienBillets() {
    var lignes =
        List.of(new LigneConcert("Indochine", "mardi 4 mars 2025", "Stade de France", null));
    String html = EmailTemplates.digestConcerts("1 nouveau concert", lignes, "https://app.fr");
    assertThat(html).doesNotContain("Billets →");
  }

  // ---- alerteBackup() ----

  @Test
  void alerteBackup_avecHorodatage_afficheLaDate() {
    String html = EmailTemplates.alerteBackup("20260605_020000", "", "https://app.fr");
    assertThat(html).contains("Démarré le : 20260605_020000");
    assertThat(html).doesNotContain("Horodatage indisponible");
    assertThat(html).contains("color: #c00");
  }

  @Test
  void alerteBackup_sansHorodatage_afficheFallback() {
    String html = EmailTemplates.alerteBackup("", null, "https://app.fr");
    assertThat(html).contains("Horodatage indisponible");
  }

  @Test
  void alerteBackup_avecRaison_afficheBlocMonospace() {
    String html =
        EmailTemplates.alerteBackup("20260605_020000", "pg_dump: fatal", "https://app.fr");
    assertThat(html).contains("pg_dump: fatal");
    assertThat(html).contains("border-left: 3px solid #c00");
  }

  @Test
  void alerteBackup_sansRaison_omitBlocMonospace() {
    String html = EmailTemplates.alerteBackup("20260605_020000", "", "https://app.fr");
    assertThat(html).doesNotContain("border-left: 3px solid #c00");
  }

  // ---- alerteBounce() ----

  @Test
  void alerteBounce_contientEmailEtCta() {
    String html = EmailTemplates.alerteBounce("user@exemple.fr", "https://app.fr");
    assertThat(html).contains("user@exemple.fr");
    assertThat(html).contains("l&#39;administration");
    assertThat(html).contains("color: #c00");
  }

  @Test
  void alerteBounce_emailAvecCaracteresSpeciaux_estEchappe() {
    String html = EmailTemplates.alerteBounce("<test>@exemple.fr", "https://app.fr");
    assertThat(html).contains("&lt;test&gt;@exemple.fr");
  }

  // ---- alerteWorker() ----

  @Test
  void alerteWorker_contientDetailEtCta() {
    String html =
        EmailTemplates.alerteWorker(
            "Dernier job complété : 2026-06-05T00:00:00Z.", "https://app.fr");
    assertThat(html).contains("Dernier job complété");
    assertThat(html).contains("l&#39;administration");
    assertThat(html).contains("color: #c00");
  }

  // ---- alerteLogsAdmin() ----

  @Test
  void alerteLogsAdmin_afficheToutesLesEntreesEtLeCompte() {
    List<fr.strapontin.web.admin.LogAdminService.EntreeLogAdmin> erreurs =
        List.of(
            new fr.strapontin.web.admin.LogAdminService.EntreeLogAdmin(
                1751500000000L, "error", "worker", "échec purger:logs-admin", null),
            new fr.strapontin.web.admin.LogAdminService.EntreeLogAdmin(
                1751500100000L, "error", "reconciliation-nocturne", "conflit wikidataId", null));

    String html = EmailTemplates.alerteLogsAdmin(erreurs, "https://app.fr");

    assertThat(html).contains("2 nouvelle(s) entrée(s)");
    assertThat(html).contains("échec purger:logs-admin");
    assertThat(html).contains("conflit wikidataId");
    assertThat(html).contains("worker");
    assertThat(html).contains("reconciliation-nocturne");
    assertThat(html).contains("color: #c00");
  }

  @Test
  void alerteLogsAdmin_messageAvecBalises_estEchappe() {
    List<fr.strapontin.web.admin.LogAdminService.EntreeLogAdmin> erreurs =
        List.of(
            new fr.strapontin.web.admin.LogAdminService.EntreeLogAdmin(
                1751500000000L, "error", "worker", "<script>alert(1)</script>", null));

    String html = EmailTemplates.alerteLogsAdmin(erreurs, "https://app.fr");

    assertThat(html).doesNotContain("<script>");
  }

  // ---- signalementSalleManquante() ----

  @Test
  void signalementSalleManquante_contientToutesLesDonnees() {
    String html =
        EmailTemplates.signalementSalleManquante(
            "La Cigale",
            "Paris 75018",
            "https://lacigale.fr",
            "Grande salle de concert",
            "user@exemple.fr",
            "https://app.fr");
    assertThat(html).contains("La Cigale");
    assertThat(html).contains("Paris 75018");
    assertThat(html).contains("https://lacigale.fr");
    assertThat(html).contains("Grande salle de concert");
    assertThat(html).contains("user@exemple.fr");
    assertThat(html).contains("Salle manquante signalée");
  }

  @Test
  void signalementSalleManquante_champsPtifsVides_afficheTiret() {
    String html =
        EmailTemplates.signalementSalleManquante(
            "Ma Salle", "", "", "", "user@ex.fr", "https://app.fr");
    assertThat(html).doesNotContain("pre-wrap");
    long nbTirets = html.chars().filter(c -> c == '—').count();
    assertThat(nbTirets).isGreaterThanOrEqualTo(2);
  }

  @Test
  void signalementSalleManquante_nomAvecBalises_estEchappe() {
    String html =
        EmailTemplates.signalementSalleManquante(
            "<script>alert(1)</script>", "", "", "", "u@ex.fr", "https://app.fr");
    assertThat(html).contains("&lt;script&gt;");
    assertThat(html).doesNotContain("<script>");
  }

  // ---- confirmationSignalementSalle() ----

  @Test
  void confirmationSignalementSalle_contientNomEtMessage() {
    String html = EmailTemplates.confirmationSignalementSalle("La Cigale", "https://app.fr");
    assertThat(html).contains("La Cigale");
    assertThat(html).contains("transmis");
    assertThat(html).contains("Votre signalement a été transmis");
  }

  @Test
  void confirmationSignalementSalle_nomAvecBalises_estEchappe() {
    String html = EmailTemplates.confirmationSignalementSalle("<b>Salle</b>", "https://app.fr");
    assertThat(html).contains("&lt;b&gt;Salle&lt;/b&gt;");
    assertThat(html).doesNotContain("<b>Salle</b>");
  }

  // ---- Structure commune ----

  @Test
  void tousLesEmails_contiennentLienGererPreferences() {
    String baseUrl = "https://app.fr";
    assertThat(EmailTemplates.invitation("https://reset", baseUrl))
        .contains(baseUrl + "/parametres");
    assertThat(EmailTemplates.resetMotDePasse("https://reset", baseUrl))
        .contains(baseUrl + "/parametres");
    assertThat(EmailTemplates.alerteBackup("ts", "raison", baseUrl))
        .contains(baseUrl + "/parametres");
  }

  @Test
  void emailsAlerte_ontTitreRouge() {
    assertThat(EmailTemplates.alerteBackup("ts", "", "https://app.fr")).contains("color: #c00");
    assertThat(EmailTemplates.alerteBounce("x@y.fr", "https://app.fr")).contains("color: #c00");
    assertThat(EmailTemplates.alerteWorker("detail", "https://app.fr")).contains("color: #c00");
  }

  @Test
  void emailsNormaux_ontTitreNoir() {
    assertThat(EmailTemplates.invitation("https://reset", "https://app.fr"))
        .contains("color: #111");
    assertThat(EmailTemplates.resetMotDePasse("https://reset", "https://app.fr"))
        .contains("color: #111");
    assertThat(EmailTemplates.confirmationEmail("https://confirm", "https://app.fr"))
        .contains("color: #111");
  }
}

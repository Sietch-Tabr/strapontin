/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.miseajour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.exploitation.Etape;
import fr.strapontin.exploitation.EtatMiseAJour;
import fr.strapontin.exploitation.Resultat;
import fr.strapontin.exploitation.Version;
import fr.strapontin.web.ErreurMetier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.info.BuildProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests unitaires pour {@link MiseAJourService}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class MiseAJourServiceTest {

  private static final Instant MAINTENANT = Instant.parse("2026-11-17T10:00:00Z");

  @TempDir Path dossier;

  private ReleasesGithub releases;
  private Path demandes;
  private Path dossierEtat;
  private MiseAJourService service;

  @BeforeEach
  void preparer() throws IOException {
    releases = mock(ReleasesGithub.class);
    final BuildProperties build = mock(BuildProperties.class);
    when(build.getVersion()).thenReturn("3.1.5");
    when(releases.plusRecente(Version.analyser("3.1.5")))
        .thenReturn(Optional.of(Version.analyser("3.2.0")));
    demandes = Files.createDirectories(dossier.resolve("demandes"));
    dossierEtat = Files.createDirectories(dossier.resolve("etat"));
    service =
        new MiseAJourService(
            releases, build, demandes, dossierEtat, Clock.fixed(MAINTENANT, ZoneOffset.UTC));
  }

  private void ecrireEtat(final Resultat resultat, final String debut) {
    new EtatMiseAJour(resultat, Etape.SAUVEGARDE, "3.2.0", Instant.parse(debut), null, null)
        .ecrire(dossierEtat);
  }

  private List<Path> demandesDeposees() throws IOException {
    try (Stream<Path> contenu = Files.list(demandes)) {
      return contenu.toList();
    }
  }

  @Test
  void etat_sansEtatNiDemande_exposeLesVersions() {
    final EtatMiseAJourWeb etat = service.etat(true);
    assertEquals("3.1.5", etat.versionInstallee());
    assertEquals("3.2.0", etat.versionProposee());
    assertNull(etat.etat());
    assertFalse(etat.demandeEnAttente());
    assertTrue(etat.totpActif());
  }

  @Test
  void etat_avecFichierEtat_leRelit() throws IOException {
    ecrireEtat(Resultat.ECHEC, "2026-11-17T09:30:00Z");
    assertEquals(Resultat.ECHEC, service.etat(true).etat().resultat());
  }

  @Test
  void etat_fichierEtatIllisible_neLevePas() throws IOException {
    Files.writeString(dossierEtat.resolve(EtatMiseAJour.FICHIER), "pas du json");
    assertNull(service.etat(true).etat());
  }

  @Test
  void demander_versionProposee_deposeLaDemande() throws IOException {
    service.demander("3.2.0", "kwaite");

    final List<Path> deposees = demandesDeposees();
    assertEquals(1, deposees.size());
    assertEquals(MAINTENANT.toEpochMilli() + ".json", deposees.get(0).getFileName().toString());
    final JsonNode demande = new JsonMapper().readTree(Files.readString(deposees.get(0)));
    assertEquals("3.2.0", demande.get("version").asString());
    assertEquals("kwaite", demande.get("demandeur").asString());
    assertEquals("2026-11-17T10:00:00Z", demande.get("horodatage").asString());
  }

  @Test
  void demander_versionNonProposee_refuseSansRienDeposer() throws IOException {
    final ErreurMetier erreur =
        assertThrows(ErreurMetier.class, () -> service.demander("9.9.9", "kwaite"));
    assertEquals(400, erreur.getStatus());
    assertTrue(demandesDeposees().isEmpty());
  }

  @Test
  void demander_aucuneVersionProposee_refuse() {
    when(releases.plusRecente(Version.analyser("3.1.5"))).thenReturn(Optional.empty());
    assertThrows(ErreurMetier.class, () -> service.demander("3.2.0", "kwaite"));
  }

  @Test
  void demander_demandeDejaEnAttente_refuseEn409() throws IOException {
    Files.writeString(demandes.resolve("1.json"), "{}");
    final ErreurMetier erreur =
        assertThrows(ErreurMetier.class, () -> service.demander("3.2.0", "kwaite"));
    assertEquals(409, erreur.getStatus());
    assertEquals(1, demandesDeposees().size());
  }

  @Test
  void demander_miseAJourEnCours_refuseEn409() throws IOException {
    ecrireEtat(Resultat.EN_COURS, "2026-11-17T09:30:00Z");
    final ErreurMetier erreur =
        assertThrows(ErreurMetier.class, () -> service.demander("3.2.0", "kwaite"));
    assertEquals(409, erreur.getStatus());
  }

  @Test
  void demander_enCoursPerime_neBloquePlus() throws IOException {
    ecrireEtat(Resultat.EN_COURS, "2026-11-17T07:00:00Z");
    service.demander("3.2.0", "kwaite");
    assertEquals(1, demandesDeposees().size());
  }

  @Test
  void demander_dernierEtatEnEchec_autoriseUneNouvelleDemande() throws IOException {
    ecrireEtat(Resultat.ECHEC, "2026-11-17T09:30:00Z");
    service.demander("3.2.0", "kwaite");
    assertEquals(1, demandesDeposees().size());
  }

  @Test
  void demander_dossierAbsent_leveUneErreur500() throws IOException {
    Files.delete(demandes);
    final ErreurMetier erreur =
        assertThrows(ErreurMetier.class, () -> service.demander("3.2.0", "kwaite"));
    assertEquals(500, erreur.getStatus());
  }
}

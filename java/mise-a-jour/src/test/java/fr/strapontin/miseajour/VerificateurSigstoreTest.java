/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.exploitation.Version;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de {@link VerificateurSigstore}, hors ligne : une vraie attestation
 * de ce dépôt (archive d'exploitation signée par ci.yml sur main), vérifiée
 * avec une copie de la racine de confiance publique de Sigstore.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class VerificateurSigstoreTest {

  private static final String DEPOT = "Sietch-Tabr/strapontin";
  private static final IdentiteAttendue IDENTITE_MAIN =
      new IdentiteAttendue(DEPOT, "ci.yml", "refs/heads/main");

  @TempDir Path dossier;

  private Path archive;
  private String bundle;
  private Path racine;

  private static Path ressource(final String nom) throws URISyntaxException {
    return Path.of(VerificateurSigstoreTest.class.getResource("/provenance/" + nom).toURI());
  }

  @BeforeEach
  void preparer() throws IOException, URISyntaxException {
    archive = ressource("exploitation.tar.gz");
    racine = ressource("trusted_root.json");
    bundle = Files.readString(ressource("exploitation.tar.gz.sigstore.json"));
  }

  private VerificateurSigstore verificateur(final List<String> bundles) {
    return VerificateurSigstore.avecRacineLocale((depot, sha256) -> bundles, racine);
  }

  private Path copieModifiee() throws IOException {
    final Path copie = dossier.resolve("exploitation.tar.gz");
    Files.copy(archive, copie);
    Files.write(copie, new byte[] {1}, StandardOpenOption.APPEND);
    return copie;
  }

  @Test
  void verifier_fichierAuthentiqueEtIdentiteAttendue_estAccepte() {
    assertDoesNotThrow(() -> verificateur(List.of(bundle)).verifier(archive, IDENTITE_MAIN));
  }

  @Test
  void verifier_uneBonneAttestationParmiD_autres_estAcceptee() {
    assertDoesNotThrow(
        () -> verificateur(List.of("{}", "pas du json", bundle)).verifier(archive, IDENTITE_MAIN));
  }

  @Test
  void verifier_fichierModifie_estRefuse() throws IOException {
    final Path modifie = copieModifiee();
    final ProvenanceException refus =
        assertThrows(
            ProvenanceException.class,
            () -> verificateur(List.of(bundle)).verifier(modifie, IDENTITE_MAIN));
    assertTrue(refus.getMessage().contains("exploitation.tar.gz"), refus.getMessage());
  }

  @Test
  void verifier_autreWorkflow_estRefuse() {
    final IdentiteAttendue autre = new IdentiteAttendue(DEPOT, "autre.yml", "refs/heads/main");
    assertThrows(
        ProvenanceException.class, () -> verificateur(List.of(bundle)).verifier(archive, autre));
  }

  @Test
  void verifier_autreReference_estRefusee() {
    final IdentiteAttendue autre = new IdentiteAttendue(DEPOT, "ci.yml", "refs/heads/feature");
    assertThrows(
        ProvenanceException.class, () -> verificateur(List.of(bundle)).verifier(archive, autre));
  }

  @Test
  void verifier_autreDepot_estRefuse() {
    final IdentiteAttendue autre =
        new IdentiteAttendue("Sietch-Tabr/autre", "ci.yml", "refs/heads/main");
    assertThrows(
        ProvenanceException.class, () -> verificateur(List.of(bundle)).verifier(archive, autre));
  }

  @Test
  void verifier_fichierConstruitSurMainPourUneRelease_estRefuse() {
    // L'identité d'une release est le tag de sa version : un fichier signé sur main ne passe pas.
    final IdentiteAttendue release = IdentiteAttendue.pourRelease(DEPOT, Version.analyser("3.2.0"));
    assertThrows(
        ProvenanceException.class, () -> verificateur(List.of(bundle)).verifier(archive, release));
  }

  @Test
  void verifier_aucuneAttestation_estRefuse() {
    assertThrows(
        ProvenanceException.class, () -> verificateur(List.of()).verifier(archive, IDENTITE_MAIN));
  }

  @Test
  void verifier_attestationIllisible_estRefusee() {
    assertThrows(
        ProvenanceException.class,
        () -> verificateur(List.of("pas du json")).verifier(archive, IDENTITE_MAIN));
  }

  @Test
  void avecRacineLocale_racineIllisible_leveUneException() throws IOException {
    final Path vide = dossier.resolve("racine.json");
    Files.writeString(vide, "pas une racine", StandardCharsets.UTF_8);
    assertThrows(
        ProvenanceException.class,
        () -> VerificateurSigstore.avecRacineLocale((depot, sha256) -> List.of(), vide));
  }
}

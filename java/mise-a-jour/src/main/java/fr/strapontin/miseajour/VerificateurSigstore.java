/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import dev.sigstore.KeylessVerificationException;
import dev.sigstore.KeylessVerifier;
import dev.sigstore.TrustedRootProvider;
import dev.sigstore.VerificationOptions;
import dev.sigstore.VerificationOptions.CertificateMatcher;
import dev.sigstore.bundle.Bundle;
import dev.sigstore.bundle.BundleParseException;
import dev.sigstore.strings.StringMatcher;
import dev.sigstore.trustroot.SigstoreConfigurationException;
import dev.sigstore.tuf.SigstoreTufClient;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Vérification de provenance par Sigstore : un fichier est accepté si une
 * attestation GitHub publiée pour son empreinte est signée par l'identité
 * attendue (workflow, référence, dépôt, déclencheur et émetteur épinglés).
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class VerificateurSigstore implements VerificateurProvenance {

  private static final String EMETTEUR = "https://token.actions.githubusercontent.com";
  private static final String OID_DEPOT = "1.3.6.1.4.1.57264.1.12";
  private static final String OID_REFERENCE = "1.3.6.1.4.1.57264.1.14";
  private static final String OID_DECLENCHEUR = "1.3.6.1.4.1.57264.1.20";
  private static final String DECLENCHEUR = "push";
  private static final Duration VALIDITE_RACINE = Duration.ofHours(12);

  private final FournisseurAttestations attestations;
  private final KeylessVerifier verifieur;

  /**
   * @param attestations origine des attestations
   * @param verifieur vérificateur Sigstore déjà configuré
   */
  public VerificateurSigstore(
      final FournisseurAttestations attestations, final KeylessVerifier verifieur) {
    this.attestations = attestations;
    this.verifieur = verifieur;
  }

  /**
   * Vérificateur de production : racine de confiance publique de Sigstore,
   * mise à jour en ligne et conservée dans un dossier de cache.
   *
   * @param attestations origine des attestations
   * @param cache dossier de cache de la racine de confiance
   * @return le vérificateur
   */
  public static VerificateurSigstore production(
      final FournisseurAttestations attestations, final Path cache) {
    final SigstoreTufClient.Builder tuf =
        SigstoreTufClient.builder()
            .usePublicGoodInstance()
            .tufCacheLocation(cache)
            .cacheValidity(VALIDITE_RACINE);
    return avecRacine(attestations, TrustedRootProvider.from(tuf));
  }

  /**
   * Vérificateur à racine de confiance fournie (tests hors ligne).
   *
   * @param attestations origine des attestations
   * @param racine fichier {@code trusted_root.json}
   * @return le vérificateur
   */
  public static VerificateurSigstore avecRacineLocale(
      final FournisseurAttestations attestations, final Path racine) {
    return avecRacine(attestations, TrustedRootProvider.from(racine));
  }

  private static VerificateurSigstore avecRacine(
      final FournisseurAttestations attestations, final TrustedRootProvider racine) {
    try {
      return new VerificateurSigstore(
          attestations, KeylessVerifier.builder().trustedRootProvider(racine).build());
    } catch (final GeneralSecurityException | SigstoreConfigurationException e) {
      throw new ProvenanceException("Racine de confiance Sigstore inutilisable", e);
    }
  }

  @Override
  public void verifier(final Path fichier, final IdentiteAttendue identite) {
    final List<String> bundles = attestations.bundles(identite.depot(), empreinte(fichier));
    if (bundles.isEmpty()) {
      throw new ProvenanceException("Aucune attestation pour " + fichier.getFileName());
    }
    final VerificationOptions options = options(identite);
    ProvenanceException premierRefus = null;
    for (final String json : bundles) {
      try {
        verifieur.verify(fichier, Bundle.from(new StringReader(json)), options);
        return;
      } catch (final KeylessVerificationException | BundleParseException e) {
        if (premierRefus == null) {
          premierRefus = new ProvenanceException(motif(fichier, e), e);
        }
      }
    }
    throw premierRefus;
  }

  private static VerificationOptions options(final IdentiteAttendue identite) {
    return VerificationOptions.builder()
        .addCertificateMatchers(
            CertificateMatcher.fulcio()
                .subjectAlternativeName(StringMatcher.string(identite.sujetCertificat()))
                .issuer(StringMatcher.string(EMETTEUR))
                .putOidDerAsn1Strings(OID_DEPOT, StringMatcher.string(identite.adresseDepot()))
                .putOidDerAsn1Strings(OID_REFERENCE, StringMatcher.string(identite.reference()))
                .putOidDerAsn1Strings(OID_DECLENCHEUR, StringMatcher.string(DECLENCHEUR))
                .build())
        .build();
  }

  private static String empreinte(final Path fichier) {
    try {
      final MessageDigest calcul = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(calcul.digest(Files.readAllBytes(fichier)));
    } catch (final GeneralSecurityException | IOException e) {
      throw new ProvenanceException("Empreinte impossible : " + fichier.getFileName(), e);
    }
  }

  private static String motif(final Path fichier, final Exception cause) {
    return "Provenance refusée pour " + fichier.getFileName() + " : " + cause.getMessage();
  }
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.strapontin.exploitation.Version;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests unitaires pour {@link SourceGithub}, face à un faux GitHub local.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class SourceGithubTest {

  private static final Version VERSION = Version.analyser("3.2.0");
  private static final byte[] CONTENU = "contenu du fichier".getBytes(StandardCharsets.UTF_8);

  @TempDir Path dossier;

  private HttpServer serveur;
  private String base;
  private final AtomicReference<String> agentRecu = new AtomicReference<>();

  @BeforeEach
  void demarrerLeFauxGithub() throws IOException {
    serveur = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    serveur.start();
    base = "http://localhost:" + serveur.getAddress().getPort();
  }

  @AfterEach
  void arreterLeFauxGithub() {
    serveur.stop(0);
  }

  private void repondre(final String chemin, final int statut, final String corps) {
    repondre(chemin, statut, corps.getBytes(StandardCharsets.UTF_8), null);
  }

  private void repondre(
      final String chemin, final int statut, final byte[] corps, final String emplacement) {
    serveur.createContext(
        chemin,
        echange -> {
          agentRecu.set(echange.getRequestHeaders().getFirst("User-Agent"));
          if (emplacement != null) {
            echange.getResponseHeaders().add("Location", emplacement);
          }
          envoyer(echange, statut, corps);
        });
  }

  private static void envoyer(final HttpExchange echange, final int statut, final byte[] corps)
      throws IOException {
    echange.sendResponseHeaders(statut, corps.length == 0 ? -1 : corps.length);
    if (corps.length > 0) {
      echange.getResponseBody().write(corps);
    }
    echange.close();
  }

  private ConfigurationSource configuration(final long tailleMax) {
    return new ConfigurationSource(
        URI.create(base), "proprio/depot", Set.of("localhost"), false, tailleMax, "essai/1");
  }

  private SourceGithub source(final long tailleMax) {
    return new SourceGithub(SourceGithub.clientSansRedirection(), configuration(tailleMax));
  }

  private String jsonRelease(final String etiquette, final String urlFichier, final long taille) {
    return """
        {"tag_name":"%s","draft":false,"prerelease":true,
         "assets":[{"name":"web.jar","size":%d,"browser_download_url":"%s"}]}
        """
        .formatted(etiquette, taille, urlFichier);
  }

  @Test
  void release_reponseValide_lisEtiquetteIndicateursEtFichiers() {
    repondre(
        "/repos/proprio/depot/releases/tags/v3.2.0",
        200,
        jsonRelease("v3.2.0", base + "/fichier", CONTENU.length));
    final ReleaseGithub release = source(1000).release(VERSION).orElseThrow();
    assertEquals("v3.2.0", release.etiquette());
    assertTrue(release.preRelease());
    assertFalse(release.brouillon());
    assertEquals(CONTENU.length, release.assets().get("web.jar").taille());
    assertEquals("essai/1", agentRecu.get());
  }

  @Test
  void release_etiquetteInconnue_retourneVide() {
    repondre("/repos/proprio/depot/releases/tags/v3.2.0", 404, "{}");
    assertEquals(Optional.empty(), source(1000).release(VERSION));
  }

  @Test
  void release_erreurDuServeur_leveUneException() {
    repondre("/repos/proprio/depot/releases/tags/v3.2.0", 500, "{}");
    assertThrows(SourceInaccessibleException.class, () -> source(1000).release(VERSION));
  }

  @Test
  void release_etiquetteDifferenteDeCelleDemandee_leveUneException() {
    repondre(
        "/repos/proprio/depot/releases/tags/v3.2.0",
        200,
        jsonRelease("v9.9.9", base + "/fichier", 1));
    assertThrows(SourceInaccessibleException.class, () -> source(1000).release(VERSION));
  }

  @Test
  void release_corpsIllisible_leveUneException() {
    repondre("/repos/proprio/depot/releases/tags/v3.2.0", 200, "pas du json {");
    assertThrows(SourceInaccessibleException.class, () -> source(1000).release(VERSION));
  }

  @Test
  void release_fichierSurUnHoteNonAutorise_leveUneException() {
    repondre(
        "/repos/proprio/depot/releases/tags/v3.2.0",
        200,
        jsonRelease("v3.2.0", "http://127.0.0.1:1/fichier", 1));
    assertThrows(SourceInaccessibleException.class, () -> source(1000).release(VERSION));
  }

  @Test
  void release_nomDeFichierDangereux_leveUneException() {
    repondre(
        "/repos/proprio/depot/releases/tags/v3.2.0",
        200,
        """
        {"tag_name":"v3.2.0","assets":[{"name":"../web.jar","size":1,
         "browser_download_url":"%s/f"}]}
        """
            .formatted(base));
    assertThrows(SourceInaccessibleException.class, () -> source(1000).release(VERSION));
  }

  @Test
  void telecharger_fichierConforme_ecritLeFichierEtCalculeLEmpreinte() throws Exception {
    repondre("/fichier", 200, CONTENU, null);
    final Asset asset = new Asset("web.jar", URI.create(base + "/fichier"), CONTENU.length);
    final Telechargement resultat = source(1000).telecharger(asset, dossier);
    assertEquals(dossier.resolve("web.jar"), resultat.fichier());
    assertEquals(CONTENU.length, resultat.taille());
    assertEquals(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENU)),
        resultat.sha256());
    assertEquals(new String(CONTENU, StandardCharsets.UTF_8), Files.readString(resultat.fichier()));
    assertFalse(Files.exists(dossier.resolve("web.jar.partiel")));
  }

  @Test
  void telecharger_tailleAnnonceeAuDessusDuPlafond_estRefuse() {
    final Asset asset = new Asset("web.jar", URI.create(base + "/fichier"), 5000);
    assertThrows(SourceInaccessibleException.class, () -> source(1000).telecharger(asset, dossier));
  }

  @Test
  void telecharger_fichierPlusGrosQueLAnnonce_estRefuseSansLaisserDeFichier() {
    repondre("/fichier", 200, CONTENU, null);
    final Asset asset = new Asset("web.jar", URI.create(base + "/fichier"), CONTENU.length - 1);
    assertThrows(SourceInaccessibleException.class, () -> source(1000).telecharger(asset, dossier));
    assertFalse(Files.exists(dossier.resolve("web.jar")));
    assertFalse(Files.exists(dossier.resolve("web.jar.partiel")));
  }

  @Test
  void telecharger_fichierPlusPetitQueLAnnonce_estRefuseSansLaisserDeFichier() {
    repondre("/fichier", 200, CONTENU, null);
    final Asset asset = new Asset("web.jar", URI.create(base + "/fichier"), CONTENU.length + 1);
    assertThrows(SourceInaccessibleException.class, () -> source(1000).telecharger(asset, dossier));
    assertFalse(Files.exists(dossier.resolve("web.jar")));
    assertFalse(Files.exists(dossier.resolve("web.jar.partiel")));
  }

  @Test
  void telecharger_reponseEnErreur_leveUneException() {
    repondre("/fichier", 403, "interdit");
    final Asset asset = new Asset("web.jar", URI.create(base + "/fichier"), 7);
    assertThrows(SourceInaccessibleException.class, () -> source(1000).telecharger(asset, dossier));
  }

  @Test
  void telecharger_redirectionVersUnHoteAutorise_estSuivie() throws Exception {
    repondre("/depart", 302, new byte[0], base + "/arrivee");
    repondre("/arrivee", 200, CONTENU, null);
    final Asset asset = new Asset("web.jar", URI.create(base + "/depart"), CONTENU.length);
    assertEquals(CONTENU.length, source(1000).telecharger(asset, dossier).taille());
  }

  @Test
  void telecharger_redirectionVersUnHoteNonAutorise_estRefusee() {
    final String autreHote = "http://127.0.0.1:" + serveur.getAddress().getPort() + "/arrivee";
    repondre("/depart", 302, new byte[0], autreHote);
    repondre("/arrivee", 200, CONTENU, null);
    final Asset asset = new Asset("web.jar", URI.create(base + "/depart"), CONTENU.length);
    assertThrows(SourceInaccessibleException.class, () -> source(1000).telecharger(asset, dossier));
  }

  @Test
  void telecharger_redirectionsSansFin_sontRefusees() {
    repondre("/boucle", 302, new byte[0], base + "/boucle");
    final Asset asset = new Asset("web.jar", URI.create(base + "/boucle"), 1);
    assertThrows(SourceInaccessibleException.class, () -> source(1000).telecharger(asset, dossier));
  }

  @Test
  void telecharger_redirectionSansAdresse_estRefusee() {
    repondre("/sans", 302, new byte[0], null);
    final Asset asset = new Asset("web.jar", URI.create(base + "/sans"), 1);
    assertThrows(SourceInaccessibleException.class, () -> source(1000).telecharger(asset, dossier));
  }

  @Test
  void telecharger_enProductionSansHttps_estRefuse() {
    repondre("/fichier", 200, CONTENU, null);
    final ConfigurationSource production =
        new ConfigurationSource(
            URI.create(base), "proprio/depot", Set.of("localhost"), true, 1000, "essai/1");
    final Asset asset = new Asset("web.jar", URI.create(base + "/fichier"), CONTENU.length);
    assertThrows(
        SourceInaccessibleException.class,
        () ->
            new SourceGithub(SourceGithub.clientSansRedirection(), production)
                .telecharger(asset, dossier));
  }

  @Test
  void telecharger_adresseAvecIdentifiants_estRefusee() {
    final String piegee =
        "http://utilisateur:secret@localhost:" + serveur.getAddress().getPort() + "/f";
    final Asset asset = new Asset("web.jar", URI.create(piegee), 1);
    assertThrows(SourceInaccessibleException.class, () -> source(1000).telecharger(asset, dossier));
  }

  @Test
  void configurationGithub_estEnHttpsSurLesHotesDeGithubSeulement() {
    final ConfigurationSource production = ConfigurationSource.github("proprio/depot", "agent");
    assertTrue(production.httpsExige());
    assertEquals(URI.create("https://api.github.com"), production.apiBase());
    assertTrue(production.hotesAutorises().contains("objects.githubusercontent.com"));
    assertFalse(production.hotesAutorises().contains("example.com"));
  }
}

/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.resend.Resend;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link AlerteMiseAJourService} et le gabarit de
 * l'alerte.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class AlerteMiseAJourServiceTest {

  private EmailSender envoi;
  private AlerteMiseAJourService service;

  @BeforeEach
  void preparer() {
    envoi = mock(EmailSender.class);
    service = new AlerteMiseAJourService(envoi);
  }

  private void configurer() {
    when(envoi.obtenirContexte())
        .thenReturn(
            Optional.of(
                new EmailSender.ContexteEmail(
                    mock(Resend.class), "no-reply@exemple.fr", "https://strapontin.exemple.fr")));
    when(envoi.obtenirDestinataireAlerte()).thenReturn(Optional.of("admin@exemple.fr"));
  }

  @Test
  void envoyer_succes_envoieUnMessageDeVersionInstallee() {
    configurer();

    service.envoyer(true, "3.2.0", "Version 3.2.0 installée.");

    verify(envoi)
        .envoyer(
            any(),
            eq("admin@exemple.fr"),
            anyString(),
            contains("3.2.0 installée"),
            contains("Mise à jour installée"),
            contains("Administration"));
  }

  @Test
  void envoyer_echec_envoieUnMessageDEchec() {
    configurer();

    service.envoyer(false, "3.2.0", "Sauvegarde locale impossible");

    verify(envoi)
        .envoyer(
            any(),
            eq("admin@exemple.fr"),
            anyString(),
            contains("échec de la mise à jour vers 3.2.0"),
            contains("Sauvegarde locale impossible"),
            anyString());
  }

  @Test
  void envoyer_sansResend_neFaitRien() {
    when(envoi.obtenirContexte()).thenReturn(Optional.empty());
    when(envoi.obtenirDestinataireAlerte()).thenReturn(Optional.of("admin@exemple.fr"));

    service.envoyer(true, "3.2.0", "ok");

    verify(envoi, never())
        .envoyer(any(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void envoyer_sansDestinataire_neFaitRien() {
    configurer();
    when(envoi.obtenirDestinataireAlerte()).thenReturn(Optional.empty());

    service.envoyer(false, "3.2.0", "x");

    verify(envoi, never())
        .envoyer(any(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void alerteMiseAJour_echapperLeMessage() {
    final String html =
        EmailTemplatesAlertes.alerteMiseAJour(false, "3.2.0", "<script>x</script>", "https://a.fr");

    assertTrue(html.contains("&lt;script&gt;"));
    assertTrue(!html.contains("<script>"));
  }
}

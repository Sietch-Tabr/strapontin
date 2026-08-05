/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.CompteurEnvoisResend;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Tests unitaires de {@link AlerteQuotaResendService}.
///
/// @since 2.7.2
/// @author Kwaite
class AlerteQuotaResendServiceTest {

  private GestionQuotaResend gestionQuotaResend;
  private EmailAlerteService emailAlerteService;
  private AlerteQuotaResendService service;

  private static final CompteurEnvoisResend COMPTEUR =
      new CompteurEnvoisResend("2026-07-18", 85, "2026-07", 200, false, false);

  @BeforeEach
  void setUp() {
    gestionQuotaResend = mock(GestionQuotaResend.class);
    emailAlerteService = mock(EmailAlerteService.class);
    service = new AlerteQuotaResendService(gestionQuotaResend, emailAlerteService);
  }

  @Test
  void verifier_seuilNonFranchi_neContactePasEmailAlerteService() {
    when(gestionQuotaResend.lireCompteurCourant()).thenReturn(COMPTEUR);
    when(gestionQuotaResend.seuilFranchiNonSignale(COMPTEUR)).thenReturn(false);

    service.verifier();

    verify(emailAlerteService, never()).obtenirDestinataireAlerte();
  }

  @Test
  void verifier_seuilFranchiSansDestinataire_neMarquePasLAlerteEnvoyee() {
    when(gestionQuotaResend.lireCompteurCourant()).thenReturn(COMPTEUR);
    when(gestionQuotaResend.seuilFranchiNonSignale(COMPTEUR)).thenReturn(true);
    when(emailAlerteService.obtenirDestinataireAlerte()).thenReturn(Optional.empty());

    service.verifier();

    verify(gestionQuotaResend, never()).marquerAlerteEnvoyee(any());
  }

  @Test
  void verifier_seuilFranchiEnvoiReussi_marqueLAlerteEnvoyee() {
    when(gestionQuotaResend.lireCompteurCourant()).thenReturn(COMPTEUR);
    when(gestionQuotaResend.seuilFranchiNonSignale(COMPTEUR)).thenReturn(true);
    when(emailAlerteService.obtenirDestinataireAlerte())
        .thenReturn(Optional.of("admin@exemple.fr"));
    when(emailAlerteService.envoyerAlerteQuotaResend("admin@exemple.fr", COMPTEUR))
        .thenReturn(true);

    service.verifier();

    verify(gestionQuotaResend).marquerAlerteEnvoyee(COMPTEUR);
  }

  @Test
  void verifier_seuilFranchiEchecEnvoi_neMarquePasLAlerteEnvoyee() {
    when(gestionQuotaResend.lireCompteurCourant()).thenReturn(COMPTEUR);
    when(gestionQuotaResend.seuilFranchiNonSignale(COMPTEUR)).thenReturn(true);
    when(emailAlerteService.obtenirDestinataireAlerte())
        .thenReturn(Optional.of("admin@exemple.fr"));
    when(emailAlerteService.envoyerAlerteQuotaResend("admin@exemple.fr", COMPTEUR))
        .thenReturn(false);

    service.verifier();

    verify(gestionQuotaResend, never()).marquerAlerteEnvoyee(any());
  }
}

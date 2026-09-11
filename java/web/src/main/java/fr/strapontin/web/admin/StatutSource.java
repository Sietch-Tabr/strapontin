/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.core.QuotaTM;
import java.time.Instant;

/**
 * Statut agrégé d'une source de données pour l'onglet admin Sources.
 *
 * @param id                    identifiant technique de la source (ex. {@code "ticketmaster"})
 * @param nom                   libellé affiché
 * @param type                  {@code "concerts"} ou {@code "metadonnees"}
 * @param quotaTM               quota Ticketmaster (non null pour id={@code "ticketmaster"})
 * @param etatQuota             {@code "ok"}, {@code "attention"} ou {@code "critique"}
 * @param dernierFetch          date du dernier fetch réussi (null si jamais exécuté)
 * @param nbSallesConfigurees   nb de salles avec un slug (Digitick) ou un {@code wikidata_id}
 *     (Wikidata) pour cette source (null si non applicable)
 * @param nbArtistesConfigures  nb d'artistes avec un {@code wikidata_id} pour cette source (null
 *     si non applicable, uniquement renseigné pour {@code "wikidata"})
 * @since 2.3.0
 * @author Kwaite
 */
public record StatutSource(
    String id,
    String nom,
    String type,
    QuotaTM quotaTM,
    String etatQuota,
    Instant dernierFetch,
    Integer nbSallesConfigurees,
    Integer nbArtistesConfigures) {}

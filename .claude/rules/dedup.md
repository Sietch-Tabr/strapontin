---
paths:
  - "packages/core/**/*.ts"
---

# Dédoublonnage inter-sources

Deux passes : exacte sur `(artiste_norm, date, ville_norm)`, fuzzy sur `(date, ville_norm)` avec similarité artiste ≥ 0.85. Index secondaire `(date, salle_norm)` pour les salles où TM et Eventim donnent des villes différentes.

## Casse des noms de salles (TM retourne TOUT MAJUSCULE)

- **Couche 1 — dédup** (`fusionner`) : préférer la valeur avec le plus de diacritiques (Eventim a la bonne casse).
- **Couche 2 — source TM** (`normaliserCasse`) : title-case français. Apostrophe → lettre suivante capitalisée (`L'OLYMPIA` → `L'Olympia`). Accents non restaurés.
- **Couche 3 — resolver** (`resoudreNomCanonique`) : MusicBrainz (score ≥ 90 ET Levenshtein ≥ 0.6) puis Deezer. Cache Redis TTL 7j. Délai 1,1s entre requêtes MB. Timeout 8s. ⚠️ MusicBrainz bloqué (IP SFR, Cloudflare) — Deezer seul resolver effectif.

## Nom d'artiste vs titre d'événement

`mapEvenement()` résout dans l'ordre : `attractions[0].name` → `artisteFallback` (si le titre contient le nom) → `null` (pas d'heuristique `split(" - ")`).

## `persisterConcerts`

`reconcilierSalles()` réécrit `salle_norm` des concerts entrants si similarité ≥ 0.5 ou containment (longueur ≥ 4).
Clé upsert : `(artiste_norm, date, salle_norm)`. En conflit : diacritiques préférées pour artiste, casse mixte pour salle.
Colonne `statut` (nullable) : valeur brute TM (`onsale`, `offsale`, `cancelled`, `postponed`, `rescheduled`), `NULL` pour Eventim. Seuls `onsale` et `NULL` déclenchent une notification digest.

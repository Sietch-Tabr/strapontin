---
paths:
  - "apps/worker/**/*.ts"
  - "packages/sources/**/*.ts"
---

# BullMQ — pièges

- **`:` interdit dans les `jobId`** — utiliser `__` (`artiste__norm`, `artistes__batch`, `region__all`, `salle__venueId`, `matches__all`, `purge__YYYY-MM-DD`). Les `:` dans le type de job (1er arg de `queue.add`) sont acceptés.
- **`removeOnFail/Complete: { count: N }` bloque le re-queuing** (BullMQ ≥ 5.76) — hash Redis persiste, `queue.add` ultérieur ignoré silencieusement. Utiliser `removeOnFail: true` et `removeOnComplete: true` pour les jobs cron à `jobId` fixe.
- **Concurrence** : `{ concurrency: 2 }` — concurrency: 3 provoquait des 429 TM (#256).
- **Quota TM** : `SEUIL_QUOTA_CRON = 500` (suspend cron), `SEUIL_QUOTA_ABORT = 50` (annule tous jobs TM), `SEUIL_QUOTA_WARN = 200` (log warn) (#267).
- **Rate limit burst** : TM limite 5 req/s. `fetchSalle` : +200ms/appel ; `fetchToutesPages` : +250ms/page (#240).
- **`artiste_norm` vide** : `persisterConcerts` rejette les concerts dont `normaliserTexte(artiste)` → chaîne vide (scripts non-latins, séparateurs) — loggés `warn` (#242, #243).
- **Retry** : tous les jobs cron (jobId fixe ou journalier) → `attempts: 3, backoff: exponential 10s` (#312). Jobs utilisateur (`fetch:artiste`, `fetch:salle`, `recalculer:matches`) → `attempts: 3, backoff: exponential 5s` (#255). `attempts` s'applique dans un run BullMQ avant `removeOnFail`, pas au re-queuing du cron.

## Redis

- `maxmemory 128mb` + `maxmemory-policy allkeys-lru` — configuré via `provision.sh`
- Quota TM : `strapontin:tm:quota` + historique `strapontin:tm:quota:historique` (LTRIM 100)

## Classifications TM

`fetchArtiste`, `fetchArtistesBatch`, `fetchRegion` : `music`, `opera`, `musical` — `theatre` exclu (spectacles sans `_embedded.attractions`).

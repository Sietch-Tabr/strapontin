# CLAUDE.md — strapontin (strapontin.kwaite.fr)

## Lancer l'application (développement)

L'app démarre automatiquement au démarrage du LXC dev (`pct start 114`).
Accès : `http://192.168.20.78:3000`

```bash
# Logs en direct
journalctl -fu strapontin-dev-web
journalctl -fu strapontin-dev-worker

# Redémarrer (ex. après git pull + pnpm install)
systemctl restart strapontin-dev-web strapontin-dev-worker
```

## Architecture — monorepo pnpm workspaces

```
strapontin/
├── apps/
│   ├── web/              ← SvelteKit (UI + API routes)
│   └── worker/           ← BullMQ worker (fetch + normalisation)
├── packages/
│   ├── core/             ← Dédoublonnage, normalisation, filtres (logique pure)
│   └── sources/          ← Clients API : TM, Bandsintown, Eventim
├── scripts/
│   ├── provision.sh          ← Création + provisioning LXC prod ou dev (`--env prod|dev`)
│   ├── deploy.sh             ← Premier déploiement (clone + build + migrations + services)
│   ├── backup.sh             ← pg_dump + .env → /mnt/backup/ (NAS monté)
│   ├── update.sh             ← backup → pull → build → migrate → restart → healthcheck
│   └── update-dev.sh         ← déploie la branche courante sur le LXC dev (Mac → SSH)
└── archive/
    └── concert-finder-cli/  ← App Python CLI d'origine (référence)
```

## Stack technique

- **SvelteKit** + TypeScript — frontend SSR + API routes
- **Drizzle ORM** + PostgreSQL 18 — données persistantes
- **BullMQ** + Redis 8 — jobs background (fetch concerts toutes les 2h)
- **Better-Auth** — auth username/password, invite-only (plugin `username` activé)
- **Resend** — notifications email (tier gratuit 3 000/mois)
- **pnpm workspaces** — monorepo sans Turborepo

## Workflow de développement

### Dev quotidien (LXC dev)

LXC dev : VMID 114, `192.168.20.78`, VLAN 20 — même stack que prod (PG 18, Redis 8).
Les services `strapontin-dev-web` et `strapontin-dev-worker` démarrent automatiquement avec le LXC.

```bash
# Démarrer / arrêter le LXC (depuis le host Proxmox)
pct start 114
pct stop 114

# Connexion SSH
ssh root@192.168.20.78

# Redémarrer les services (après git pull + pnpm install)
systemctl restart strapontin-dev-web strapontin-dev-worker
```

Application accessible sur `http://192.168.20.78:3000`.

### Gestion des issues (Codeberg)

CLI : `tea` (pas `gh` — Codeberg est Gitea, incompatible avec `gh`).

```bash
# Lister les issues avec leur contenu
tea issues -r Kwaite/strapontin -f "index,title,body,comments" -o json

# Créer une issue
tea issue create -r Kwaite/strapontin

# Créer une PR (toujours mettre Closes #N dans le corps, pas seulement le titre)
tea pr create -r Kwaite/strapontin --title "..." --description "$(cat <<'EOF'
Closes #N

## Résumé
...
EOF
)"

# Jalons et labels
tea milestones -r Kwaite/strapontin -o json
tea labels -r Kwaite/strapontin -o json
```

`tea issue view N` n'affiche pas `body` par défaut — toujours passer `-f "index,title,body"`.

Jalons définis : **v 1.0**, **v 1.1**, **v 1.2**.
Labels utiles en solo : `bug`, `enhancement`, `question`.

### Workflow Git

```
git checkout -b feature/nom-court
     ↓
  pnpm dev (Mac)  →  validation locale / WireGuard si besoin
     ↓
  git push feature/nom-court  →  Woodpecker ignore (branches feature)
     ↓
  PR feature/xxx → main  →  review + merge
     ↓
  push main  →  Woodpecker → update.sh sur LXC prod
```

- **Toujours travailler sur une branche feature** — jamais de commit direct sur `main`
- Woodpecker déploie **uniquement** sur push `main`
- Nommage : `feature/`, `fix/`, `chore/`

### Pipeline Woodpecker (.woodpecker.yaml)

Deux steps sur push `main` : `typecheck` puis `deploy` (`depends_on: [typecheck]`).

- **`typecheck`** (`node:22-alpine`) : crée un `.env` stub, exécute `svelte-check` sur web et `tsc --noEmit` sur worker. Bloque le deploy en cas d'erreur TypeScript.
- **`deploy`** (`alpine`) : SSH vers le LXC prod, exécute `update.sh`.

Voir `.woodpecker.yaml` pour le YAML complet. Secrets : `ssh_key` (clé privée), `lxc_host` = `192.168.20.79`. LXC 111 (Woodpecker) et LXC 113 (strapontin) sur VLAN 20 — SSH direct.

### Configuration CI/CD (une seule fois)

Clé SSH : `ssh-keygen -t ed25519 -C "woodpecker-strapontin" -f ~/.ssh/woodpecker_strapontin -N ""`

**Piège post-reinstallation** : le LXC recréé n'a pas de `authorized_keys`. La clé publique est aussi dans `/root/.ssh/authorized_keys` sur Proxmox (commentaire `woodpecker-strapontin`) :

```bash
ssh root@192.168.1.99 "
  PUBKEY=\$(grep woodpecker-strapontin /root/.ssh/authorized_keys)
  pct exec 113 -- bash -c \"mkdir -p /root/.ssh && echo '\$PUBKEY' >> /root/.ssh/authorized_keys && chmod 700 /root/.ssh && chmod 600 /root/.ssh/authorized_keys\"
"
```

## Scripts d'exploitation

```
scripts/
├── provision.sh         # création + provisioning LXC prod ou dev (--env prod|dev, idempotent)
│                        #   pct create, réseau VLAN 20, apt, PostgreSQL, Redis, systemd, bind mount NAS (prod)
├── deploy.sh            # premier déploiement : clone → build → migrations → services
├── backup.sh            # pg_dump + .env → /mnt/backup/ (NAS monté en bind mount), purge 30j
├── update.sh            # backup → pull → install → build → migrate → restart → healthcheck
├── update-dev.sh        # déploie la branche courante sur le LXC dev (Mac → SSH → 192.168.20.78)
├── system-update.sh     # mise à jour OS du LXC (depuis host Proxmox) : snapshot → apt → healthcheck
├── seed-artistes.ts     # importe une liste d'artistes pour un user (tsx scripts/seed-artistes.ts)
└── artistes-seed.txt    # liste d'artistes par défaut (un par ligne, # = commentaire)
```

### update.sh — ordre strict

1. `backup.sh` — jamais d'update sans backup préalable
2. `git fetch origin && git reset --hard origin/main` — jamais `git pull` (évite les conflits si patches serveur) ; mémorise `PREV_COMMIT` avant le reset
3. `pnpm install`
4. `set -a; source .env; set +a && pnpm build` — sourcer `.env` avant build (Better Auth init au démarrage)
5. `drizzle-kit migrate`
6. `systemctl restart strapontin-web strapontin-worker`
7. healthcheck : `curl -f http://localhost:3000/health` (10 retries × 3s)

Si le healthcheck expire, rollback automatique : `git reset --hard $PREV_COMMIT` → rebuild → restart. Log `ERREUR CRITIQUE` si le rollback lui-même échoue.

### Page de maintenance

`scripts/maintenance.html` — page statique à configurer dans Nginx Proxy Manager (Settings → Custom Error Pages → 502/503/504). S'affiche automatiquement quand le backend est injoignable. Aucun service supplémentaire sur le LXC.

### system-update.sh — mise à jour OS du LXC

À exécuter depuis le **host Proxmox** (pas depuis le LXC ni le Mac) :

```bash
# Copier ou exécuter depuis le host Proxmox
bash /opt/strapontin/scripts/system-update.sh
# ou en SSH depuis le Mac :
ssh root@192.168.1.99 "bash -s" < scripts/system-update.sh
```

Ordre : snapshot Proxmox → `apt upgrade` → vérification des 4 services → healthcheck `/health`.
En cas d'échec : `pct rollback 113 pre-sysupdate-YYYYMMDD`.

**Ne pas intégrer dans `update.sh`** (déployé par Woodpecker) — un `apt upgrade` intempestif
risque de casser PostgreSQL ou Redis en plein déploiement applicatif.

**Suivi des versions** :

```bash
# Dépendances npm — tous workspaces
pnpm outdated -r

# Paquets système obsolètes sur le LXC
ssh root@192.168.20.79 "apt list --upgradable 2>/dev/null"
```

PostgreSQL et Redis sont blacklistés dans `unattended-upgrades` (fichier
`/etc/apt/apt.conf.d/51strapontin-upgrades`) — leurs montées de version majeures
sont gérées manuellement avec snapshot préalable.

### backup.sh

NAS monté en bind mount sur `/mnt/backup/`. `pg_dump` exécuté en tant que `postgres` (peer auth). Cron systemd quotidien (`OnCalendar=daily`). Montage NAS : `pct set $VMID --mp0 /mnt/synology/backup/Strapontin,mp=/mnt/backup` (depuis host Proxmox).

## Infrastructure (déploiement)

- LXC `strapontin` — **VMID 113**, **192.168.20.79**, VLAN 20, Debian 13, 2 vCPU, 2 Go RAM, 20 Go
  - Réseau : `--net0 name=eth0,bridge=vmbr0,tag=20,hwaddr=02:00:00:00:00:13,ip=192.168.20.79/24,gw=192.168.20.1`
  - Feature `nesting=1` obligatoire — requis par Systemd 257 pour redémarrer les services (journald, etc.) dans un LXC non-privilégié
- 4 services systemd dans un seul LXC : `postgresql` (18), `redis` (8), `strapontin-web`, `strapontin-worker`
- Nginx Proxy Manager (LXC 100) → `strapontin.kwaite.fr` → `192.168.20.79:3000`
  - Proxy host 26 — WebSocket off, Block Exploits off, SSL forcé, HTTP/2
  - `advanced_config` : headers sécurité + `location /` custom (sans headers WebSocket)
  - Piège : coller du contenu avec `\n` littéraux casse le conf silencieusement → voir wiki Déploiement
- Provisioning : `scripts/provision.sh` (depuis host Proxmox 192.168.1.99)
  - Pré-requis : copier les scripts vers le host (`ssh mkdir -p /opt/strapontin/scripts` + `scp scripts/provision.sh scripts/deploy.sh root@192.168.1.99:/opt/strapontin/scripts/`)
  - Appel : `provision.sh --env prod` ou `provision.sh --env dev`
  - VMID résolu dynamiquement : `VMID=$(pvesh get /cluster/nextid)`
- Variables : `.env` sur le serveur (non commité), `.env.example` versionné

## Variables d'environnement (.env)

Voir `.env.example`. En prod, seules `DATABASE_URL`, `REDIS_URL` et `BETTER_AUTH_SECRET` restent dans `.env` — les autres clés applicatives sont dans `app_config` (DB).

## Sources de données

| Source                    | Méthode                                                                              | Priorité  |
| ------------------------- | ------------------------------------------------------------------------------------ | --------- |
| Ticketmaster Discovery v2 | API officielle (clé .env)                                                            | Core      |
| Eventim France            | API publique reverse-engineered                                                      | Core      |
| Dice.fm                   | Scraping Playwright                                                                  | Phase 3   |
| Shotgun.live              | Playwright + API interne JSON (Vercel bot protection contournée via session browser) | Phase 2   |
| Bandsintown               | API gratuite (app_id .env) — HTTP 401 depuis 2026-04-10, free tier inaccessible      | Abandonné |
| Fnac Spectacles           | Bloqué (Cloudflare JS challenge, timeout même Playwright)                            | Abandonné |

## Pattern workers (BullMQ)

Paradigme fondamental : **pré-fetch en arrière-plan, jamais d'appel API depuis le browser**.
Les concerts sont stockés en DB. Le frontend lit uniquement la DB.

### Jobs implémentés

| Type                   | Déclenchement             | Description                                                                                      |
| ---------------------- | ------------------------- | ------------------------------------------------------------------------------------------------ |
| `fetch:artiste`        | Ajout artiste + cron 2h   | Fetch TM + Bandsintown + Eventim par nom artiste                                                 |
| `fetch:artistes:batch` | Cron 2h                   | Fetch TM en lots de 20 attractionIds — 200 concerts/lot, paginé jusqu'à 1 000 concerts           |
| `fetch:region`         | Cron 2h + action manuelle | Fetch TM + Eventim par département — 200 concerts/dept, paginé. Timestamp Redis par dept         |
| `fetch:salle`          | Ajout salle + cron 2h     | Fetch TM par `venueId` (stocké dans `user_salles.tmVenueId`)                                     |
| `recalculer:matches`   | Après fetch               | Recalcule `user_matches` pour tous les users (artistes + salles, filtre géo si depts configurés) |
| `purger:concerts`      | Cron quotidien (minuit)   | Supprime les concerts dont la date est passée depuis > 7 jours                                   |

Classifications TM pour `fetch:region` : `music`, `theatre`, `opera`, `musical`.

**Déclenchement immédiat** : quand l'user ajoute un artiste depuis `/artistes`, l'action server fait `queue.add("fetch:artiste", ...)`. Quand l'user ajoute une salle avec `tmVenueId`, `queue.add("fetch:salle", ...)` est déclenché immédiatement. Le cron 2h assure les mises à jour périodiques.

### Redis — configuration

- `maxmemory 128mb` + `maxmemory-policy allkeys-lru` — configuré dans `/etc/redis/redis.conf` via `provision.sh`
- Pas de réplication, instance unique — le `maxmemory` borne la consommation si les clés sans TTL grossissent

### Redis — clés applicatives

| Clé                                      | Valeur                                                   | TTL     | Usage                                             |
| ---------------------------------------- | -------------------------------------------------------- | ------- | ------------------------------------------------- |
| `strapontin:tm:quota`                    | JSON `{dispo, total, reset, misAJour}`                   | —       | Quota TM affiché en /admin                        |
| `strapontin:tm:attraction:{artisteNorm}` | attractionId TM                                          | 7 jours | Cache batching attractions                        |
| `strapontin:region:fetched:{dept}`       | ISO timestamp                                            | 8 jours | Bannière découverte — last fetch par dept         |
| `strapontin:job:stats:artistes-batch`    | JSON `{statut, debutRun, finRun?, durationMs?, erreur?}` | 48h     | Stats du dernier fetch:artistes:batch             |
| `strapontin:job:stats:region`            | JSON `{statut, debutRun, finRun?, durationMs?, erreur?}` | 48h     | Stats du dernier fetch:region                     |
| `strapontin:nom:canonique:{type}:{norm}` | nom résolu (string)                                      | 7 jours | Cache resolver MB/Deezer — `type` = artiste/salle |

### Pièges BullMQ

- **BullMQ v5 interdit les `:` dans les `jobId`** — utiliser `__` comme séparateur (`artiste__norm`, `artistes__batch`, `region__all`, `salle__venueId`, `matches__all`, `purge__YYYY-MM-DD`). Les `:` dans le _type de job_ (premier arg de `queue.add`) sont acceptés.
- **`removeOnFail: { count: N }` et `removeOnComplete: { count: N }` bloquent le re-queuing des jobs à `jobId` fixe** (BullMQ ≥ 5.76) — le hash Redis persiste et tout `queue.add` ultérieur avec le même `jobId` est ignoré silencieusement. Utiliser `removeOnFail: true` et `removeOnComplete: true` pour les jobs cron à `jobId` fixe (`artistes__batch`, `region__all`, `matches__all`, `salle__*`).

### Centroïdes des départements (`CENTRES_DEPT`)

Table statique dans `packages/sources/src/ticketmaster.ts` : latitude/longitude du centroïde de chaque département métropolitain (01–95 + `"20"` pour la Corse entière). Utilisée comme point d'ancrage pour `fetchRegion` (radius TM).

Générée une seule fois par `scripts/generer-centres-dept.ts` (moyenne des centres de communes via `geo.api.gouv.fr`). La Corse est agrégée : `"20"` = moyenne de 2A + 2B. Statique dans le code — ne pas relancer sauf nouveau découpage territorial.

### Pagination TM (`packages/sources/src/ticketmaster.ts`)

- `TAILLE_PAGE = 20` pour `fetchArtiste` et `fetchSalle` (1 artiste/salle, rarement > 20 concerts FR)
- `TAILLE_PAGE_REGION = 200` (max TM) pour `fetchArtistesBatch` et `fetchRegion`
- `NB_PAGES_MAX = 5` — protection quota : max 1 000 concerts par appel
- `fetchToutesPages()` : boucle de pagination avec délai 250ms entre pages

### Guard quota TM (`apps/worker/src/jobs.ts`)

`verifierQuotaTM(nomJob)` appelée en début de `fetch:artistes:batch` et `fetch:region` :

- `dispo < 200` → `warn` (quota faible)
- `dispo < 10` → `error` + job annulé (break)

### Interface sources (packages/sources)

```typescript
interface SourceConcerts {
  fetchArtiste(nom: string): Promise<ConcertBrut[]>;
  fetchRegion(depts: string[], classifs: string[]): Promise<ConcertBrut[]>;
  fetchSalle?(venueId: string): Promise<ConcertBrut[]>; // optionnel — TM uniquement
}
```

### Configuration applicative (`app_config`)

Table `app_config` : clé API et autres variables configurables depuis l'IHM admin (`/admin`), chiffrées en base. Prend le dessus sur les variables d'environnement.

**Chiffrement** : AES-256-GCM. Clé de 32 bytes dérivée de `BETTER_AUTH_SECRET` via `scryptSync` (salt fixe `strapontin-app-config`).
⚠️ `BETTER_AUTH_SECRET` est obligatoire au démarrage — le serveur refuse de démarrer si la variable est absente (fail-fast dans `config.ts` des deux apps).
⚠️ Si `BETTER_AUTH_SECRET` change en production, toutes les valeurs en base deviennent illisibles — vider la table `app_config` et les re-saisir depuis l'IHM.

**Clés gérées** : `TICKETMASTER_API_KEY`, `RESEND_API_KEY`, `CONTACT_EMAIL`, `PUBLIC_BASE_URL`, `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`.

`CONTACT_EMAIL` et `PUBLIC_BASE_URL` sont affichées en clair dans `/admin` ; les autres restent masquées.

**Validation à la saisie** (action `sauvegarderConfig`) :

- `PUBLIC_BASE_URL` : format URL valide + `https://` + résolution DNS du domaine (bloquant)
- `RESEND_API_KEY` : format `re_` + appel test `GET api.resend.com/domains` (HTTP 401 = clé invalide)
- `TICKETMASTER_API_KEY` : format alphanumérique 16–64 chars + appel test Discovery API (HTTP 401 = clé invalide)
- Timeout 5 s sur les appels API — en cas d'indisponibilité du service externe, la sauvegarde passe quand même

⚠️ Ne jamais changer les clés VAPID sans supprimer les abonnements push existants (`DELETE FROM push_abonnements`) — les anciens endpoints deviennent invalides.

**Pattern lecture** :

- Web : `apps/web/src/lib/server/config.ts` → `lireConfig(cle)` → `sauvegarderConfig(cle, valeur)` → `lireConfigsPresentes()`
- Worker : `apps/worker/src/config.ts` (même API, import `db` du worker)

**Pattern worker** (`apps/worker/src/sources.ts`) : `obtenirTicketmaster()` lit la clé TM depuis la DB avec cache mémoire TTL 5 min + fallback `process.env["TICKETMASTER_API_KEY"]`. Les jobs BullMQ appellent `obtenirTicketmaster()` en début de traitement (pas de singleton statique).

## Authentification

Better-Auth, username/password. Invite-only : admin crée le compte en saisissant uniquement
l'**email**. Le système génère un username temporaire (`u_` + 8 chars aléatoires) et un mot
de passe temporaire (16 chars base64url). L'admin communique ces identifiants à l'utilisateur.

À la première connexion, `must_change_credentials = true` → redirection automatique vers
`/first-login` (dans `hooks.server.ts`). L'utilisateur y choisit son nom, identifiant et un
nouveau mot de passe (12 caractères minimum). Le flag passe à `false` après validation.

Login par **identifiant** (insensible à la casse, normalisé en minuscules). L'email sert
uniquement à la récupération de compte (reset mot de passe — v 1.2).
Rôles : `user` | `admin`.

**Politique mot de passe** : 12 caractères minimum (NIST SP 800-63B, longueur seule).
S'applique à `/setup`, `/parametres` (changePassword) et `/first-login`.

**Rate limiting login** : activé via `rateLimit` natif Better Auth dans `apps/web/src/lib/auth.ts` :

- Route `/sign-in/username` : 5 tentatives max / 15 minutes (fenêtre 900s)
- Toutes les autres routes auth : 100 requêtes / 10 secondes (limite souple globale)
- Storage : mémoire (suffisant pour instance unique)

**Validation des entrées** :

- Paramètre de recherche `q` : 100 chars max (`.slice(0, 100)`) dans toutes les routes ILIKE
- Filtre `genre` dans `/spectacles` : 50 chars max
- Tableaux `genres[]`, `classifications[]` dans `/decouverte` : 20 valeurs max
- Import préférences : `nom`, `normalise`, `salleId` bornés à 200 chars ; `tmVenueId` validé via regex `^[A-Za-z0-9]{1,30}$`

**Cap par utilisateur** : 500 artistes max, 200 salles max (vérifié à l'ajout et à l'import).

**Cookie `theme`** : `httpOnly: false` (lu par JS pour le mode `auto`), `secure: true`, `sameSite: "lax"`.

Plugin `username` de Better-Auth : colonnes `username` + `display_username` sur `users`.
Colonne `must_change_credentials` (boolean, default false) : flag premier login.
Annotation `@__NO_SIDE_EFFECTS__` de better-auth strippée dans `vite.config.ts` via plugin
`renderChunk` pour éviter une erreur Rollup dans l'étape adapter-node.

## Dédoublonnage inter-sources (packages/core)

Portage de `src/merge.py` du projet CLI. Deux passes :

1. Exacte sur `(artiste_normalise, date, ville_normalisee)`
2. Fuzzy sur `(date, ville_normalisee)` avec similarité artiste ≥ 0.85 (SequenceMatcher)

Index secondaire `(date, salle_normalisee)` en fallback pour les salles où TM et Eventim
donnent des villes différentes (Stade de France, Arkea Arena, LDLC Arena).

### Particularité TM — casse des noms de salles

L'API TM Events (et Venues) retourne les noms de salles en TOUT MAJUSCULE pour les événements
France. Deux couches corrigent ce problème :

**Couche 1 — déduplication** (`packages/core/src/deduplication.ts`, `fusionner`) : quand TM
et Eventim couvrent le même concert, `salle` **et** `artiste` sont choisies indépendamment
du gagnant global — la valeur avec le plus de diacritiques est préférée dans les deux cas.
Eventim retourne les noms avec accents et casse correcte.

**Couche 2 — source TM** (`packages/sources/src/ticketmaster.ts`, `normaliserCasse`) : pour
les concerts présents uniquement chez TM, `mapEvenement()` applique un title-case français
sur les noms tout-majuscule. Les articles courants (de, du, le, la, les, l', d', en, au,
aux…) restent en minuscules sauf en début de chaîne. La lettre suivant une apostrophe
(droite `'` ou typographique `'`) est également capitalisée (`L'OLYMPIA` → `L'Olympia`).
Les accents ne sont pas restaurés (information perdue dans le tout-majuscule).

**Couche 3 — resolver** (`packages/sources/src/resolver.ts`, `resoudreNomCanonique`) : pour
tous les noms d'artistes après déduplication, le worker appelle MusicBrainz (`/ws/2/artist/`,
score ≥ 90) puis Deezer en fallback. Le cache Redis absorbe le coût — un nom absent du cache
déclenche un appel API, les suivants sont servis depuis Redis. Délai 1,1 s entre requêtes MB
(rate limit). Résultats mis en cache Redis `strapontin:nom:canonique:{type}:{norm}` (TTL 7j).
Timeout réseau 8 s (`AbortController`). Résultat Deezer validé par similitude brute ≥ 0.7 (pas
de score natif, contrairement à MB). Résultats toujours mis en cache Redis même si le nom n'a
pas changé (évite les appels répétés à chaque cron). Appelée dans `resoudreArtistesConcerts()`
après `dedoublonner()`, avant `persisterConcerts()`, dans les jobs `fetch:artiste`,
`fetch:artistes:batch`, `fetch:region` et `fetch:salle`.

### Particularité TM — nom d'artiste vs titre d'événement

TM retourne parfois des événements sans `_embedded.attractions` (tableau absent ou vide).
`mapEvenement()` résout le nom d'artiste dans l'ordre de priorité suivant :

1. `_embedded.attractions[0].name` (nom canonique, toujours préféré)
2. `artisteFallback` passé par l'appelant (`fetchArtiste(nom)`) — seulement si le titre contient le nom (insensible à la casse), sinon `null` (faux positif TM rejeté)
3. Sans attraction ni fallback (fetch:region, fetchArtistesBatch) → `null` ; l'heuristique `split(" - ")` a été supprimée (produisait des faux artistes en base)

### Persistance — upsert `persisterConcerts`

Avant chaque lot, `reconcilierSalles()` interroge la base sur les `(artiste_norm, date)` du lot
et réécrit `salle_norm` des concerts entrants dont la salle est sémantiquement identique à un
existant (similarité ≥ 0.5 **ou** containment, longueur ≥ 4). Seul `salle_norm` est réécrit ;
`salle` (libellé) est conservé pour que la règle casse-mixte de l'upsert s'applique normalement.

Clé de conflit : `(artiste_norm, date, salle_norm)`.
En cas de conflit, les champs mis à jour suivent ces préférences :

- `artiste` : version avec diacritiques préférée (`unaccent(excluded.artiste) != excluded.artiste`)
- `salle` : casse mixte préférée sur tout-majuscule (`upper(excluded.salle) != excluded.salle`)

### `recalculer:matches` — matching salle

Le matching des salles suivies utilise `concerts.salle_norm` (et non `concerts.salle`) pour
être insensible à la casse — TM retourne le même lieu en tout-majuscule ou casse mixte
selon le type d'appel.

Exports `@strapontin/core` : `normaliserTexte`, `normaliserVille`, `similarite`, `levenshtein`
(distance brute — utile quand un seuil proportionnel à la longueur est préférable à `similarite`).

## UI / Design system

- **shadcn-svelte** (Svelte 5 + Tailwind v4) — CLI génère les composants dans le projet
  - Basé sur Bits UI (headless, accessible WCAG 2.1 AA nativement)
  - Pas de dépendance à mettre à jour : le code est dans le repo
- **Tailwind CSS v4** — configuration via `@theme` dans le CSS (plus de `tailwind.config.js`)
- **Typographie** : Geist ou Inter (à valider à l'œil au démarrage)
- **Thème** : `auto` (système), `clair`, `sombre` — sobre dans les deux cas
  - Mode sombre via CSS custom properties + `class="dark"` sur `<html>`
  - Persisté en DB (`user_config.theme`) + cookie (1 an) → synchronisé entre appareils, pas de FOUC
  - `auto` : script inline dans `app.html` (`%sveltekit.nonce%`) détecte `prefers-color-scheme` côté client
  - Résolution : cookie en priorité ; si absent → lecture DB + pose cookie automatique (`hooks.server.ts`)
- **Palette** : neutres (gris, blanc/noir) + une couleur accent unique (à définir Phase 0)
- **PWA** : `static/manifest.json` (name, icons, display: standalone, theme_color: #ffffff). Icônes : `icon-192.png` et `icon-512.png` générées depuis `favicon.png` (1024×1024). `<link rel="manifest">` + `<meta name="theme-color">` dans `app.html`.
- **Service Worker** : `apps/web/src/service-worker.ts` — géré automatiquement par SvelteKit. Cache les assets statiques. Gère les événements `push` (notifications Web Push) et `notificationclick` (ouvre `/spectacles`).
- **Notifications push** : opt-in dans `/parametres` → section "Notifications push". Clés VAPID stockées dans `app_config` — générées via bouton **Générer** dans `/admin` → Configuration (ou `node scripts/generer-vapid.mjs` en ligne de commande). Envoi dans `apps/worker/src/push.ts` → `envoyerPushDigest()`. Table DB : `push_abonnements`. Nettoyage auto des abonnements révoqués (codes 404/410). Cap : 10 abonnements max par utilisateur (HTTP 429 au-delà). Validation : endpoint ≤ 2 048 chars, p256dh/auth ≤ 256 chars. GET `/api/push` est intentionnellement public (le service worker doit récupérer la clé VAPID publique sans session).

## Navigation

- **Desktop (≥ 1024px)** : sidebar fixe — Accueil, **Mes spectacles**, **Découverte**, Mes artistes, Mes salles | [Admin,] Nouveautés, Aide, Paramètres en bas
- **Mobile (< 1024px)** : bottom nav 3 items fixes (Accueil, **Mes spectacles**, **Découvrir**) + "···" → drawer pour Artistes, Salles, Nouveautés, Aide, Paramètres

## Routes implémentées

Toutes les routes sont sous `apps/web/src/routes/(app)/` (layout avec sidebar + bottom nav).

| Route              | Description                                                                                                                                                                                         |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/`                | Accueil — concerts artistes suivis, groupés en tournées. Bouton "Masquer" (masque_le dans user_matches) — reste visible dans /spectacles.                                                           |
| `/artistes`        | Recherche MusicBrainz + DB → suggestions avec nom canonique → suivre/supprimer                                                                                                                      |
| `/salles`          | Recherche DB (ILIKE) + Ticketmaster Venues → suggestions → suivre/supprimer + saisie libre                                                                                                          |
| `/spectacles`      | Mes spectacles — userMatches (artistes + salles suivis). Chips : Mes artistes / Mes salles / 15j / Masqués / tri. Filtres : classification, genre, artiste, mois, budget.                           |
| `/decouverte`      | Tous les concerts en base. Chips dept, catégories, genres, tri. Filtres mois, budget. Bannière si dept hors prefs (timestamp Redis + Actualiser / Ajouter aux prefs).                               |
| `/parametres`      | 5 sections : Mon compte (nom, email, identifiant, MDP), Recherche (depts, rayon), Notifications (fréquence + artistes/salles), Apparence (thème), Export/Import. Zone de danger : suppression RGPD. |
| `/forgot-password` | Saisie email → lien reset MDP via Resend (hors garde auth)                                                                                                                                          |
| `/reset-password`  | Formulaire nouveau MDP (token Better-Auth, 1h — hors garde auth)                                                                                                                                    |
| `/aide`            | Guide, RGPD, licence MIT. Lien vers `/changelog`.                                                                                                                                                   |
| `/changelog`       | Nouveautés par version.                                                                                                                                                                             |
| `/api/preferences` | `GET` — télécharge `strapontin-preferences-YYYY-MM-DD.json`. Protégé par session.                                                                                                                   |
| `/admin`           | Invitation (email) + liste + suppression + modification (nom, email) + réinit MDP — réservé admin. Badge si `must_change_credentials = true`.                                                       |
| `/setup`           | Création premier compte admin (si 0 users en base)                                                                                                                                                  |
| `/login`           | Authentification username/password (insensible à la casse)                                                                                                                                          |
| `/first-login`     | Premier login — choix nom, identifiant, MDP. Redirigé si `must_change_credentials = true`.                                                                                                          |
| `/health`          | GET — `{"status":"ok"}` ou 503, hors garde d'auth                                                                                                                                                   |

## Affichage — tournées

Sur `/` (accueil), les concerts sont regroupés par artiste. Si un artiste a ≥ 2 concerts à venir, un badge "Tournée · N dates" s'affiche. Chaque ligne de concert a son propre lien "Billets" (si `url` disponible). Pas de nouvelle table — logique purement UI dans `+page.server.ts`.

## Densité d'affichage

- **Vue cartes** (défaut, mobile + desktop) — artiste + date + salle, aérée
- **Vue tableau** (desktop uniquement) — `/spectacles` et `/decouverte` affichent un tableau sur `lg:`

## Schéma PostgreSQL

Migration unique `0000_initial.sql` dans `packages/core/migrations/` — schéma complet v1.1 (toutes tables dans leur état final, fusionné). Appliquée par `db:migrate`.

Les nouvelles migrations sont numérotées séquentiellement à partir de `0001`.

### Tables Better Auth (gérées par la lib)

| Table           | Rôle                                                                                                                     |
| --------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `users`         | Utilisateurs — colonnes Better Auth + `role` (user\|admin) + `username` + `display_username` + `must_change_credentials` |
| `sessions`      | Sessions actives                                                                                                         |
| `accounts`      | Comptes d'auth (stocke le hash du mot de passe côté credential)                                                          |
| `verifications` | Tokens de vérification email / reset password                                                                            |

`users.id` est de type `text` (Better Auth génère ses propres IDs, pas UUID).

### Tables applicatives

| Table           | Rôle                                                                                                                                                                         |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `concerts`      | Source de vérité concerts — `classification` (valeurs TM France en français : `musique`, `arts et théâtre`…) + `genre` (Rock/Pop…). CHECK sur `source`.                      |
| `user_artistes` | Artistes suivis par user — UNIQUE `(user_id, artiste_normalise)`                                                                                                             |
| `user_salles`   | Salles suivies par user — PK composite `(user_id, salle_id)`, `tmVenueId` nullable                                                                                           |
| `user_config`   | Préférences par user (départements, rayon, filtres, thème)                                                                                                                   |
| `user_matches`  | Index concerts ↔ users (calculé par le worker). Colonnes : `notifie_le`, `vu_le`, `masque_le` (nullable timestamp — concert masqué depuis l'Accueil, exclu du digest email). |
| `fetch_jobs`    | Historique et planning des jobs BullMQ                                                                                                                                       |

### Authentification — premier utilisateur

Pas de page d'inscription publique. Premier accès via `/setup` (accessible uniquement si aucun utilisateur en base — redirige vers `/login` sinon). Crée automatiquement un compte `role=admin`. Saisie obligatoire : nom d'affichage, identifiant (username), email, mot de passe.

## Conventions

- TypeScript strict, pas de `any`
- Noms de variables en français (cohérence avec le projet CLI)
- Commentaires en français
- Migrations Drizzle dans `packages/core/migrations/`
- Logging : pino 10, lazy `%s` (pas de template literals dans les appels logger)
- Pas de `console.log` en production
- **tsconfig des packages Node** (worker, sources) : inclure `"types": ["node"]` — requis depuis TypeScript 6 (plus d'inclusion automatique de `@types/node`)
- **`<button>` hors formulaire** : toujours `type="button"` — sans ça, le défaut HTML est `submit`, ce qui peut déclencher la soumission d'un formulaire parent sur mobile
- **Tests** : vitest dans `packages/core`, `packages/sources`, `apps/worker` — `pnpm -r test` pour tout lancer. Les tests worker mockent `../db.js` (pas de PostgreSQL requis). Nommage : `<fonction>_<scenario>_<attendu>` en français.

## Export / Import préférences

Format JSON : `{ version, exporteAt, artistes, salles, config }`.
`config` inclut : `departements`, `rayonKm`, `filtreDisponibles`, `frequenceNotifications`, `notifArtistes`, `notifSalles`, `theme`.

- **Export** : `GET /api/preferences` → téléchargement direct
- **Import** (`/parametres`) :
  - **Fusionner** : union artistes/salles + union depts (config) — ne supprime rien
  - **Écraser** : remplace tout (artistes, salles, et config si cochée)
  - Checkbox opt-in "Importer la configuration" (depts, rayon, filtres, thème)
  - Toutes les opérations DB dans une transaction → rollback complet en cas d'erreur
  - Post-import : `fetch:artistes:batch` + `fetch:salle` par salle + `fetch:region` si nouveaux depts

## Roadmap — jalons

- **v1.0** — livré 2026-04-18
- **v1.1** — terminé (#65 Bandsintown, #37 doublons, #115 → déplacé v1.2)
- **v1.2** — actif : #110 (page maintenance NPM), #115 (LXC dev), #116 (portabilité scripts), Shotgun.live, Dice.fm

# CLAUDE.md — strapontin (strapontin.kwaite.fr)

## Lancer l'application (développement)

```bash
pnpm install
pnpm dev           # SvelteKit dev server (port 3000)
pnpm worker:dev    # BullMQ worker (process séparé)
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
│   ├── provision-lxc.sh  ← Création + provisioning LXC depuis host Proxmox
│   ├── deploy.sh         ← Premier déploiement (clone + build + migrations + services)
│   ├── backup.sh         ← pg_dump + .env → /mnt/backup/ (NAS monté)
│   └── update.sh         ← backup → pull → build → migrate → restart → healthcheck
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

### Dev local (quotidien)

```bash
pnpm dev        # SvelteKit HMR (port 3000)
pnpm worker:dev # BullMQ worker (process séparé)
```

- PostgreSQL 18 + Redis 8 installés via Homebrew sur le Mac
- `.env.local` (prioritaire sur `.env`) pointe sur la DB locale
- Le dev server est accessible depuis le réseau via WireGuard (`http://192.168.100.3:3000`)
  pour tester sur mobile ou valider le SSR sans déployer

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

```yaml
steps:
  - name: typecheck
    image: node:22-alpine
    commands:
      - corepack enable
      - pnpm install --frozen-lockfile
      - printf 'DATABASE_URL=x\nBETTER_AUTH_SECRET=x\nTICKETMASTER_API_KEY=x\nBANDSINTOWN_APP_ID=x\nRESEND_API_KEY=x\nREDIS_URL=x\nPUBLIC_BASE_URL=http://localhost\n' > .env
      - pnpm --filter @strapontin/web run check
      - pnpm --filter @strapontin/worker exec tsc --noEmit
    when:
      - event: push
        branch: main

  - name: deploy
    image: alpine
    depends_on: [typecheck]
    environment:
      SSH_KEY:
        from_secret: ssh_key
      LXC_HOST:
        from_secret: lxc_host
    commands:
      - apk add openssh-client
      - echo "$$SSH_KEY" > /tmp/key && chmod 600 /tmp/key
      - ssh -i /tmp/key -o StrictHostKeyChecking=no root@$$LXC_HOST "/opt/strapontin/scripts/update.sh"
    when:
      - event: push
        branch: main
```

LXC 111 (Woodpecker, `192.168.20.88`) et LXC 113 (strapontin, `192.168.20.79`) sont tous les deux sur VLAN 20 — SSH direct sans passer par le host Proxmox.

Secrets Woodpecker : `ssh_key` (clé privée dédiée), `lxc_host` = `192.168.20.79`.

### Configuration CI/CD (une seule fois)

1. Générer la clé SSH dédiée sur le Mac :

   ```bash
   ssh-keygen -t ed25519 -C "woodpecker-strapontin" -f ~/.ssh/woodpecker_strapontin -N ""
   ```

2. Après provisioning du LXC strapontin, autoriser la clé sur le LXC :

   ```bash
   cat ~/.ssh/woodpecker_strapontin.pub | ssh root@192.168.1.99 \
     "pct exec 113 -- bash -c 'mkdir -p /root/.ssh && cat >> /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys'"
   ```

3. Créer le repo sur Codeberg puis pousser :

   ```bash
   git remote add origin https://codeberg.org/Kwaite/strapontin.git
   git push -u origin main
   ```

4. Dans Woodpecker (http://LXC-111) :
   - Activer le repo `Kwaite/strapontin` (onglet Repositories)
   - Aller dans Settings → Secrets du repo
   - Ajouter le secret `ssh_key` : coller le contenu de `~/.ssh/woodpecker_strapontin` (clé **privée**)
   - Ajouter le secret `lxc_host` : valeur `192.168.20.79`

## Scripts d'exploitation

```
scripts/
├── provision-lxc.sh     # création + provisioning LXC depuis host Proxmox (idempotent)
│                        #   pct create, réseau VLAN 20, apt, PostgreSQL, Redis, systemd, bind mount NAS
├── deploy.sh            # premier déploiement : clone → build → migrations → services
├── backup.sh            # pg_dump + .env → /mnt/backup/ (NAS monté en bind mount), purge 30j
├── update.sh            # backup → pull → install → build → migrate → restart → healthcheck
├── system-update.sh     # mise à jour OS du LXC (depuis host Proxmox) : snapshot → apt → healthcheck
├── seed-artistes.ts     # importe une liste d'artistes pour un user (tsx scripts/seed-artistes.ts)
└── artistes-seed.txt    # liste d'artistes par défaut (un par ligne, # = commentaire)
```

### seed-artistes.ts — usage

```bash
# Sur le LXC prod, après avoir sourcé .env :
DATABASE_URL=... USER_EMAIL=kwaite@example.com tsx scripts/seed-artistes.ts
# Avec un fichier custom :
DATABASE_URL=... USER_EMAIL=... tsx scripts/seed-artistes.ts /chemin/vers/artistes.txt
```

### update.sh — ordre strict

1. `backup.sh` — jamais d'update sans backup préalable
2. `git fetch origin && git reset --hard origin/main` — jamais `git pull` (évite les conflits si patches serveur)
3. `pnpm install`
4. `set -a; source .env; set +a && pnpm build` — sourcer `.env` avant build (Better Auth init au démarrage)
5. `drizzle-kit migrate`
6. `systemctl restart strapontin-web strapontin-worker`
7. healthcheck : `curl -f http://localhost:3000/health`

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

Le NAS est monté en bind mount dans le LXC sur `/mnt/backup/` — pas de rsync, écriture directe :

```bash
su - postgres -c "pg_dump strapontin" | gzip > /mnt/backup/strapontin_$(date +%Y%m%d_%H%M%S).sql.gz
cp /opt/strapontin/.env /mnt/backup/.env.$(date +%Y%m%d)
find /mnt/backup/ -name "*.sql.gz" -mtime +30 -delete
```

Note : `pg_dump` doit être exécuté en tant que `postgres` (peer auth).

Montage NAS dans le LXC :

```bash
# Dans provision-lxc.sh (à exécuter depuis le host Proxmox)
# Prérequis : créer /volume1/backup/Strapontin/ dans DSM au préalable
pct set $VMID --mp0 /mnt/synology/backup/Strapontin,mp=/mnt/backup
```

### Backup automatique

Cron systemd (`OnCalendar=daily`) sur le LXC — indépendant des mises à jour manuelles.

## Infrastructure (déploiement)

- LXC `strapontin` — **VMID 113**, **192.168.20.79**, VLAN 20, Debian 13, 2 vCPU, 2 Go RAM, 20 Go
  - Réseau : `--net0 name=eth0,bridge=vmbr0,tag=20,hwaddr=02:00:00:00:00:13,ip=192.168.20.79/24,gw=192.168.20.1`
- 4 services systemd dans un seul LXC : `postgresql` (18), `redis` (8), `strapontin-web`, `strapontin-worker`
- Nginx Proxy Manager → `strapontin.kwaite.fr` → `192.168.20.79:3000`
- Provisioning : `scripts/provision-lxc.sh` (depuis host Proxmox 192.168.1.99)
  - VMID résolu dynamiquement : `VMID=$(pvesh get /cluster/nextid)`
- Variables : `.env` sur le serveur (non commité), `.env.example` versionné

## Variables d'environnement (.env)

```
TICKETMASTER_API_KEY=
BANDSINTOWN_APP_ID=
DATABASE_URL=postgresql://strapontin:password@localhost:5432/strapontin
REDIS_URL=redis://localhost:6379
BETTER_AUTH_SECRET=
RESEND_API_KEY=
PUBLIC_BASE_URL=https://strapontin.kwaite.fr
```

## Sources de données

| Source                    | Méthode                                                                              | Priorité  |
| ------------------------- | ------------------------------------------------------------------------------------ | --------- |
| Ticketmaster Discovery v2 | API officielle (clé .env)                                                            | Core      |
| Bandsintown               | API gratuite (app_id .env)                                                           | Core      |
| Eventim France            | API publique reverse-engineered                                                      | Core      |
| Dice.fm                   | Scraping Playwright                                                                  | Phase 3   |
| Shotgun.live              | Playwright + API interne JSON (Vercel bot protection contournée via session browser) | Phase 2   |
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

### Redis — clés applicatives

| Clé                                      | Valeur                                 | TTL     | Usage                                     |
| ---------------------------------------- | -------------------------------------- | ------- | ----------------------------------------- |
| `strapontin:tm:quota`                    | JSON `{dispo, total, reset, misAJour}` | —       | Quota TM affiché en /admin                |
| `strapontin:tm:attraction:{artisteNorm}` | attractionId TM                        | 7 jours | Cache batching attractions                |
| `strapontin:region:fetched:{dept}`       | ISO timestamp                          | 8 jours | Bannière découverte — last fetch par dept |

### Pièges BullMQ

- **BullMQ v5 interdit les `:` dans les `jobId`** — utiliser `__` comme séparateur (`artiste__norm`, `artistes__batch`, `region__all`, `salle__venueId`, `matches__all`, `purge__YYYY-MM-DD`). Les `:` dans le _type de job_ (premier arg de `queue.add`) sont acceptés.

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

## UI / Design system

- **shadcn-svelte** (Svelte 5 + Tailwind v4) — CLI génère les composants dans le projet
  - Basé sur Bits UI (headless, accessible WCAG 2.1 AA nativement)
  - Pas de dépendance à mettre à jour : le code est dans le repo
- **Tailwind CSS v4** — configuration via `@theme` dans le CSS (plus de `tailwind.config.js`)
- **Typographie** : Geist ou Inter (à valider à l'œil au démarrage)
- **Thème** : clair + sombre, sobre dans les deux cas
  - Mode sombre via CSS custom properties + `class="dark"` sur `<html>`
  - Persisté en cookie (lu côté serveur SvelteKit → pas de FOUC)
- **Palette** : neutres (gris, blanc/noir) + une couleur accent unique (à définir Phase 0)

## Navigation

- **Desktop (≥ 1024px)** : sidebar fixe — Accueil, **Mes spectacles**, **Découverte**, Mes artistes, Mes salles | Settings en bas
- **Mobile (< 1024px)** : bottom nav 3 items fixes (Accueil, **Mes spectacles**, **Découvrir**) + "···" → drawer pour Artistes, Salles, Settings

## Routes implémentées

Toutes les routes sont sous `apps/web/src/routes/(app)/` (layout avec sidebar + bottom nav).

| Route              | Description                                                                                                                                                                                                         |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/`                | Accueil — prochains concerts artistes suivis uniquement (JOIN userArtistes), groupés en tournées                                                                                                                    |
| `/artistes`        | Recherche MusicBrainz + DB → suggestions avec nom canonique → suivre/supprimer                                                                                                                                      |
| `/salles`          | Recherche DB (ILIKE) + Ticketmaster Venues → suggestions → suivre/supprimer + saisie libre                                                                                                                          |
| `/spectacles`      | **Mes spectacles** — concerts userMatches (artistes + salles suivis). Chips : Mes artistes / Mes salles / 15 prochains jours / tri (Par date · Nouveautés). Filtres : classification, genre, artiste, mois, budget. |
| `/decouverte`      | Tous les concerts en base. Chips dept, catégories, genres, tri (Par date · Nouveautés). Filtres mois, budget. Bannière si dept hors prefs : timestamp last-fetch Redis + actions Actualiser / Ajouter aux prefs.    |
| `/parametres`      | user_config (depts, rayon, filtreDisponibles), thème, nom + mot de passe. Export JSON (artistes + salles + config). Import JSON avec mode fusionner / écraser, checkbox config opt-in, fetch déclenché post-import. |
| `/api/preferences` | `GET` — télécharge `strapontin-preferences-YYYY-MM-DD.json` (artistes, salles, config). Protégé par session.                                                                                                        |
| `/admin`           | Invitation (email seul) + liste des comptes + suppression — réservé admin. Badge "premier login en attente" si `must_change_credentials = true`.                                                                    |
| `/setup`           | Création premier compte admin (accessible uniquement si 0 users en base)                                                                                                                                            |
| `/login`           | Authentification username/password (identifiant insensible à la casse)                                                                                                                                              |
| `/first-login`     | Première connexion — choix du nom, identifiant et nouveau mot de passe. Redirigé automatiquement si `must_change_credentials = true`.                                                                               |
| `/health`          | GET — vérification DB (SELECT 1), retourne `{"status":"ok"}` ou 503, hors garde d'auth                                                                                                                              |

## Affichage — tournées

Sur `/` (accueil), les concerts sont regroupés par artiste. Si un artiste a ≥ 2 concerts à venir, un badge "Tournée · N dates" s'affiche. Chaque ligne de concert a son propre lien "Billets" (si `url` disponible). Pas de nouvelle table — logique purement UI dans `+page.server.ts`.

## Densité d'affichage

- **Vue cartes** (défaut, mobile + desktop) — artiste + date + salle, aérée
- **Vue tableau** (desktop uniquement) — `/spectacles` et `/decouverte` affichent un tableau sur `lg:`

## Schéma PostgreSQL

Migration initiale unique `0000_friendly_thunderbolt_ross.sql` dans `packages/core/migrations/` — reflète le schéma complet de la v1. Générée par `pnpm --filter @strapontin/core db:generate`, appliquée par `db:migrate`.

Les nouvelles migrations (post-v1) seront numérotées à partir de `0001`.

### Tables Better Auth (gérées par la lib)

| Table           | Rôle                                                                                                                     |
| --------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `users`         | Utilisateurs — colonnes Better Auth + `role` (user\|admin) + `username` + `display_username` + `must_change_credentials` |
| `sessions`      | Sessions actives                                                                                                         |
| `accounts`      | Comptes d'auth (stocke le hash du mot de passe côté credential)                                                          |
| `verifications` | Tokens de vérification email / reset password                                                                            |

`users.id` est de type `text` (Better Auth génère ses propres IDs, pas UUID).

### Tables applicatives

| Table           | Rôle                                                                                                                                                    |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `concerts`      | Source de vérité concerts — `classification` (valeurs TM France en français : `musique`, `arts et théâtre`…) + `genre` (Rock/Pop…). CHECK sur `source`. |
| `user_artistes` | Artistes suivis par user — UNIQUE `(user_id, artiste_normalise)`                                                                                        |
| `user_salles`   | Salles suivies par user — PK composite `(user_id, salle_id)`, `tmVenueId` nullable                                                                      |
| `user_config`   | Préférences par user (départements, rayon, filtres…)                                                                                                    |
| `user_matches`  | Index concerts ↔ users (calculé par le worker)                                                                                                          |
| `fetch_jobs`    | Historique et planning des jobs BullMQ                                                                                                                  |

### Authentification — premier utilisateur

Pas de page d'inscription publique. Premier accès via `/setup` (accessible uniquement si aucun utilisateur en base — redirige vers `/login` sinon). Crée automatiquement un compte `role=admin`. Saisie obligatoire : nom d'affichage, identifiant (username), email, mot de passe.

## Conventions

- TypeScript strict, pas de `any`
- Noms de variables en français (cohérence avec le projet CLI)
- Commentaires en français
- Migrations Drizzle dans `packages/core/migrations/`
- Logging : pino, lazy `%s` (pas de template literals dans les appels logger)
- Pas de `console.log` en production

## Export / Import préférences

Format JSON : `{ version, exporteAt, artistes, salles, config }`.

- **Export** : `GET /api/preferences` → téléchargement direct
- **Import** (`/parametres`) :
  - **Fusionner** : union artistes/salles + union depts (config) — ne supprime rien
  - **Écraser** : remplace tout (artistes, salles, et config si cochée)
  - Checkbox opt-in "Importer la configuration" (depts, rayon, filtres)
  - Toutes les opérations DB dans une transaction → rollback complet en cas d'erreur
  - Post-import : `fetch:artistes:batch` + `fetch:salle` par salle + `fetch:region` si nouveaux depts

## Roadmap — jalons

### v0.90 — revue globale (jalon actif)

| #   | Item                                                                  |
| --- | --------------------------------------------------------------------- |
| #41 | Erreurs silencieuses : logger les échecs Redis et les cas catch vides |
| #25 | Analyse des logs de prod avant la v1                                  |
| #24 | Configuration du conteneur Proxmox                                    |
| #18 | Revue de code                                                         |
| #16 | Audit UI/UX                                                           |
| #14 | Test de sécurité                                                      |
| #10 | RGPD                                                                  |
| #9  | Création d'une page d'aide et d'infos                                 |
| #6  | Optimiser les appels aux API externes                                 |

### Phase 2 (post-v1.0)

| Priorité | Item                                                                               |
| -------- | ---------------------------------------------------------------------------------- |
| Phase 2  | Notifications email (Resend) — `notificationsEmail` en DB, rien envoyé côté worker |
| Phase 2  | Shotgun.live — Playwright + API interne JSON                                       |
| Phase 3  | Dice.fm — scraping Playwright                                                      |

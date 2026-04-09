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
- **Better-Auth** — auth email/password, invite-only
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

### Déploiement

```
feature/xxx  →  pnpm dev (Mac)  →  validation locale / WireGuard si besoin
     ↓
  merge PR   →  push main       →  Woodpecker → update.sh sur LXC prod
```

Push sur `main` uniquement → déclenche le pipeline CI.
Pas de déploiement automatique des branches feature.

### Pipeline Woodpecker (.woodpecker.yaml)

```yaml
steps:
  - name: deploy
    image: alpine
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

LXC 111 (Woodpecker) et LXC 113 (strapontin) sont tous les deux sur VLAN 20 — SSH direct sans passer par le host Proxmox.

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

| Type                 | Déclenchement           | Description                                                            |
| -------------------- | ----------------------- | ---------------------------------------------------------------------- |
| `fetch:artiste`      | Ajout artiste + cron 2h | Fetch TM + Bandsintown + Eventim par nom artiste                       |
| `fetch:region`       | Cron 2h                 | Fetch TM + Eventim par département (depuis `user_config.departements`) |
| `fetch:salle`        | Ajout salle + cron 2h   | Fetch TM par `venueId` (stocké dans `user_salles.tmVenueId`)           |
| `recalculer:matches` | Après fetch             | Recalcule `user_matches` pour tous les users                           |

Classifications TM pour `fetch:region` : `music`, `theatre`, `opera`, `musical`.

**Déclenchement immédiat** : quand l'user ajoute un artiste depuis `/artistes`, l'action server fait `queue.add("fetch:artiste", ...)`. Quand l'user ajoute une salle avec `tmVenueId`, `queue.add("fetch:salle", ...)` est déclenché immédiatement. Le cron 2h assure les mises à jour périodiques.

### Pièges BullMQ

- **BullMQ v5 interdit les `:` dans les `jobId`** — utiliser `__` comme séparateur (`artiste__nom`, `region__all`, `salle__venueId`, `matches__all`). Les `:` dans le _type de job_ (premier arg de `queue.add`) sont acceptés.

### Interface sources (packages/sources)

```typescript
interface SourceConcerts {
  fetchArtiste(nom: string): Promise<ConcertBrut[]>;
  fetchRegion(depts: string[], classifs: string[]): Promise<ConcertBrut[]>;
  fetchSalle?(venueId: string): Promise<ConcertBrut[]>; // optionnel — TM uniquement
}
```

## Authentification

Better-Auth, email/password uniquement. Invite-only : admin crée le compte
→ user reçoit lien "définir mon mot de passe". Pas de page d'inscription publique.
Rôles : `user` | `admin`.

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

- **Desktop (≥ 1024px)** : sidebar fixe — Accueil, Mes artistes, Mes salles, Découverte, Spectacles | Settings en bas
- **Mobile (< 1024px)** : bottom nav 4 items fixes (Accueil, Artistes, Salles, Découvrir) + "···" → drawer pour Spectacles, Settings
- **À venir** : /quinzaine fusionné dans /spectacles (chip "15 prochains jours")

## Routes implémentées

Toutes les routes sont sous `apps/web/src/routes/(app)/` (layout avec sidebar + bottom nav).

| Route         | Description                                                                                                     |
| ------------- | --------------------------------------------------------------------------------------------------------------- |
| `/`           | Accueil — prochains concerts artistes suivis uniquement (JOIN userArtistes), groupés en tournées                |
| `/artistes`   | Recherche MusicBrainz + DB → suggestions avec nom canonique → suivre/supprimer                                  |
| `/salles`     | Recherche DB (ILIKE) + Ticketmaster Venues → suggestions → suivre/supprimer + saisie libre                      |
| `/quinzaine`  | **À fusionner dans /spectacles** — chip "15 prochains jours"                                                    |
| `/spectacles` | Concerts userMatches à venir, filtres : chip quinzaine, chips catégorie multi, chips genre multi, artiste, mois |
| `/decouverte` | Tous les concerts en base (non filtrés par user), filtres dept (chips auto-submit)/mois/genre, bouton "Suivre" |
| `/parametres` | user_config (depts, rayon, filtreDisponibles), thème (cookie), nom + mot de passe (Better Auth)                 |
| `/admin`      | Création de comptes (mot de passe temporaire généré) + liste + suppression — réservé admin                      |
| `/setup`      | Création premier compte admin (accessible uniquement si 0 users en base)                                        |
| `/login`      | Authentification email/password                                                                                 |
| `/health`     | GET — vérification DB (SELECT 1), retourne `{"status":"ok"}` ou 503, hors garde d'auth                          |

## Affichage — tournées

Sur `/` (accueil), les concerts sont regroupés par artiste. Si un artiste a ≥ 2 concerts à venir, un badge "Tournée · N dates" s'affiche. Chaque ligne de concert a son propre lien "Billets" (si `url` disponible). Pas de nouvelle table — logique purement UI dans `+page.server.ts`.

## Densité d'affichage

- **Vue cartes** (défaut, mobile + desktop) — artiste + date + salle, aérée
- **Vue tableau** (desktop uniquement) — `/spectacles` et `/decouverte` affichent un tableau sur `lg:`

## Schéma PostgreSQL

Migrations dans `packages/core/migrations/` — générées par `pnpm --filter @strapontin/core db:generate`, appliquées par `db:migrate`.

### Tables Better Auth (gérées par la lib)

| Table           | Rôle                                                            |
| --------------- | --------------------------------------------------------------- |
| `users`         | Utilisateurs — colonnes Better Auth + `role` (user\|admin)      |
| `sessions`      | Sessions actives                                                |
| `accounts`      | Comptes d'auth (stocke le hash du mot de passe côté credential) |
| `verifications` | Tokens de vérification email / reset password                   |

`users.id` est de type `text` (Better Auth génère ses propres IDs, pas UUID).

### Tables applicatives

| Table           | Rôle                                                                                         |
| --------------- | -------------------------------------------------------------------------------------------- |
| `concerts`      | Source de vérité concerts — `classification` (music/theatre) + `genre` (Rock/Pop…) depuis TM |
| `user_artistes` | Artistes suivis par user                                                                     |
| `user_salles`   | Salles suivies par user — `tmVenueId` (ID Ticketmaster, nullable)                            |
| `user_config`   | Préférences par user (départements, rayon, filtres…)                                         |
| `user_matches`  | Index concerts ↔ users (calculé par le worker)                                               |
| `fetch_jobs`    | Historique et planning des jobs BullMQ                                                       |

### Authentification — premier utilisateur

Pas de page d'inscription publique. Premier accès via `/setup` (accessible uniquement si aucun utilisateur en base — redirige vers `/login` sinon). Crée automatiquement un compte `role=admin`.

## Conventions

- TypeScript strict, pas de `any`
- Noms de variables en français (cohérence avec le projet CLI)
- Commentaires en français
- Migrations Drizzle dans `packages/core/migrations/`
- Logging : pino, lazy `%s` (pas de template literals dans les appels logger)
- Pas de `console.log` en production

## Points à éclaircir

- **Stratégie de déploiement** : évaluer si Woodpecker + SSH est justifié, ou si une release Codeberg + webhook (ou SSH manuel depuis le Mac) est plus simple. Woodpecker ajoute de la complexité (réseau inter-VLAN, secrets, maintenance) pour un gain limité sur un projet personnel.

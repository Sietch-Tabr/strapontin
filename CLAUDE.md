# CLAUDE.md — concert-web (strapontin.kwaite.fr)

## Lancer l'application (développement)

```bash
pnpm install
pnpm dev           # SvelteKit dev server (port 3000)
pnpm worker:dev    # BullMQ worker (process séparé)
```

## Architecture — monorepo pnpm workspaces

```
concert-web/
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
    secrets: [ssh_key, lxc_host]
    commands:
      - apk add openssh-client
      - echo "$$SSH_KEY" > /tmp/key && chmod 600 /tmp/key
      - ssh -i /tmp/key concerts@$$LXC_HOST "/opt/strapontin/scripts/update.sh"
    when:
      branch: main
```

Secrets Woodpecker : `ssh_key` (clé privée SSH), `lxc_host` (IP du LXC sur VLAN 20).

### Configuration CI/CD (une seule fois)

1. Générer la clé SSH dédiée (en local) :
   `ssh-keygen -t ed25519 -C "woodpecker-deploy" -f ~/.ssh/woodpecker_concerts -N ""`

2. Créer le repo sur Codeberg puis pousser :
   `git remote add origin https://codeberg.org/Kwaite/strapontin.git`
   `git push -u origin main`

3. Dans Woodpecker (http://LXC-111) :
   - Activer le repo `Kwaite/strapontin`
   - Ajouter le secret `ssh_key` (contenu de `~/.ssh/woodpecker_concerts`)
   - Ajouter le secret `lxc_host` = `192.168.20.79`

4. Après provisioning du LXC, copier la clé publique :
   `ssh-copy-id -i ~/.ssh/woodpecker_concerts.pub concerts@192.168.20.79`

## Scripts d'exploitation

```
scripts/
├── provision-lxc.sh   # création + provisioning LXC depuis host Proxmox (idempotent)
│                      #   pct create, réseau VLAN 20, apt, PostgreSQL, Redis, systemd, bind mount NAS
├── deploy.sh          # premier déploiement : clone → build → migrations → services
├── backup.sh          # pg_dump + .env → /mnt/backup/ (NAS monté en bind mount), purge 30j
└── update.sh          # backup → pull → install → build → migrate → restart → healthcheck
```

### update.sh — ordre strict

1. `backup.sh` — jamais d'update sans backup préalable
2. `git pull`
3. `pnpm install`
4. `pnpm build`
5. `drizzle-kit migrate`
6. `systemctl restart concerts-web concerts-worker`
7. healthcheck : `curl -f http://localhost:3000/health`

### backup.sh

Le NAS est monté en bind mount dans le LXC sur `/mnt/backup/` — pas de rsync, écriture directe :

```bash
pg_dump concerts | gzip > /mnt/backup/concerts_$(date +%Y%m%d_%H%M%S).sql.gz
cp /opt/strapontin/.env /mnt/backup/.env.$(date +%Y%m%d)
find /mnt/backup/ -name "*.sql.gz" -mtime +30 -delete
```

Montage NAS dans le LXC :

```bash
# Dans provision-lxc.sh (à exécuter depuis le host Proxmox)
# Prérequis : créer /volume1/backup/concerts/ dans DSM au préalable
pct set $VMID --mp0 /mnt/synology/backup/Strapontin,mp=/mnt/backup
```

### Backup automatique

Cron systemd (`OnCalendar=daily`) sur le LXC — indépendant des mises à jour manuelles.

## Infrastructure (déploiement)

- LXC `concerts` — **192.168.20.79**, VLAN 20, Debian 13, 2 vCPU, 2 Go RAM, 20 Go
  - VMID : `pvesh get /cluster/nextid` au moment de l'exécution (ne pas hardcoder)
  - Réseau : `--net0 name=eth0,bridge=vmbr0,tag=20,hwaddr=02:00:00:00:00:13,ip=192.168.20.79/24,gw=192.168.20.1`
- 4 services systemd dans un seul LXC : `postgresql` (18), `redis` (8), `concerts-web`, `concerts-worker`
- Nginx Proxy Manager → `strapontin.kwaite.fr` → `192.168.20.79:3000`
- Provisioning : `scripts/provision-lxc.sh` (depuis host Proxmox 192.168.1.99)
  - VMID résolu dynamiquement : `VMID=$(pvesh get /cluster/nextid)`
- Variables : `.env` sur le serveur (non commité), `.env.example` versionné

## Variables d'environnement (.env)

```
TICKETMASTER_API_KEY=
BANDSINTOWN_APP_ID=
DATABASE_URL=postgresql://concerts:password@localhost:5432/concerts
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

Jobs : `fetch:artiste:{nom}`, `fetch:region:{dept}:{classif}`, `fetch:salle:{id}`
Cron : toutes les 2h pour chaque user actif.
Déclenchement immédiat si un user modifie ses artistes ou salles.

Interface commune pour toutes les sources (packages/sources) :

```typescript
interface SourceConcerts {
  fetchArtiste(nom: string): Promise<Concert[]>;
  fetchSalle(id: string): Promise<Concert[]>;
  fetchRegion(dept: string[], classifs: string[]): Promise<Concert[]>;
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

- **Desktop (≥ 1024px)** : sidebar fixe — Accueil, Mes artistes, Mes salles, Découverte, Quinzaine, Spectacles | Settings en bas
- **Mobile (< 1024px)** : bottom nav 4 items fixes (Accueil, Artistes, Salles, Découvrir) + "···" → drawer pour Quinzaine, Spectacles, Settings

## Densité d'affichage

Vue switchable sur toutes les pages liste, toggle persisté en `localStorage` :

- **Vue cartes** (défaut, mobile + desktop) — artiste + date + salle, aérée
- **Vue tableau** (desktop uniquement) — dense, filtrable, proche de l'app CLI

## Conventions

- TypeScript strict, pas de `any`
- Noms de variables en français (cohérence avec le projet CLI)
- Commentaires en français
- Migrations Drizzle dans `packages/core/migrations/`
- Logging : pino, lazy `%s` (pas de template literals dans les appels logger)
- Pas de `console.log` en production

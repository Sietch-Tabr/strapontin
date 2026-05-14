# Strapontin

Ne ratez plus les concerts de vos artistes préférés. Strapontin agrège automatiquement les événements depuis Ticketmaster et Eventim, vous notifie dès qu'une nouvelle date est annoncée, et centralise tout en un seul endroit — sans avoir à surveiller chaque billetterie séparément.

<p align="center">
  <img src="https://codeberg.org/Kwaite/strapontin/raw/branch/main/docs/screenshots/concerts-desktop.png" alt="Liste des concerts — vue desktop" width="780" />
</p>

<p align="center">
  <img src="https://codeberg.org/Kwaite/strapontin/raw/branch/main/docs/screenshots/concerts-mobile.png" alt="Liste des concerts — vue mobile" width="320" />
</p>

## Fonctionnalités

**Suivi**
- Artistes et salles favoris (jusqu'à 500 artistes, 200 salles)
- Actualisation automatique toutes les 2 heures depuis Ticketmaster et Eventim
- Dédoublonnage inter-sources automatique

**Notifications**
- Email ou notification push (immédiat ou récapitulatif quotidien à 8h)

**Interface**
- Filtrage par département, mois, budget, catégorie
- Thème clair / sombre / automatique
- Application installable (PWA)
- Export / import des préférences au format JSON

## Stack

- [SvelteKit](https://kit.svelte.dev/) + TypeScript — frontend SSR
- [Drizzle ORM](https://orm.drizzle.team/) + PostgreSQL — persistance
- [BullMQ](https://bullmq.io/) + Redis — jobs background
- [Better Auth](https://www.better-auth.com/) — authentification
- [Resend](https://resend.com/) — emails transactionnels
- pnpm workspaces — monorepo

## Installation

Voir le [wiki Déploiement](https://codeberg.org/Kwaite/strapontin/wiki/Déploiement) pour les instructions complètes (LXC Proxmox, variables d'environnement, scripts de provisioning).

## Licence

MIT — voir [LICENSE](LICENSE) ou la page `/aide` de l'application.

# Strapontin

Agrégateur de concerts personnel. Suivez vos artistes et salles préférés — Strapontin récupère automatiquement les événements depuis Ticketmaster et Eventim toutes les 2 heures et vous notifie par email ou notification push.

## Fonctionnalités

- Suivi d'artistes et de salles (500 artistes, 200 salles max par compte)
- Actualisation automatique toutes les 2 heures
- Filtrage par département, mois, budget, catégorie
- Notifications email et push (immédiat ou récapitulatif quotidien à 8h)
- Application installable (PWA)
- Thème clair / sombre / automatique
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

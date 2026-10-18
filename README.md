# Strapontin

Ne ratez plus les concerts de vos artistes préférés. Strapontin agrège automatiquement les événements depuis Ticketmaster et Eventim, vous notifie dès qu'une nouvelle date est annoncée, et centralise tout en un seul endroit — sans avoir à surveiller chaque billetterie séparément.

<p align="center">
  <a href="https://codeberg.org/Kwaite/strapontin/raw/branch/main/docs/screenshots/concerts-desktop.png">
    <img src="https://codeberg.org/Kwaite/strapontin/raw/branch/main/docs/screenshots/concerts-desktop.png" alt="Vue desktop" width="560" />
  </a>
  &nbsp;
  <a href="https://codeberg.org/Kwaite/strapontin/raw/branch/main/docs/screenshots/concerts-mobile.png">
    <img src="https://codeberg.org/Kwaite/strapontin/raw/branch/main/docs/screenshots/concerts-mobile.png" alt="Vue mobile" width="190" />
  </a>
</p>

## Fonctionnalités

**Suivi**

- Artistes et salles favoris (jusqu'à 500 artistes et 200 salles)
- Concerts récupérés automatiquement chez Ticketmaster et Eventim, 11 fois par jour, et une fois par jour sur le site de quelques salles hors de ces billetteries
- Un même concert publié par plusieurs billetteries n'apparaît qu'une fois
- Prévente, complet, report et annulation signalés sur chaque concert
- Page Découverte : les concerts des départements choisis, même sans suivre l'artiste

**Notifications**

- Email ou notification push, à chaque nouvelle date ou en récapitulatif quotidien (8h)

**Interface**

- Filtres par mois, genre et catégorie, tri au choix
- Thème clair, sombre ou automatique
- Application installable (PWA), utilisable sur mobile
- Export et import des préférences (JSON)

**Comptes**

- Accès sur invitation, double authentification (TOTP) facultative
- Administration : santé des sources, anomalies à traiter, référentiel des salles et artistes, communication aux utilisateurs

## Stack

- [Spring Boot 4](https://spring.io/projects/spring-boot), Java 26 : API REST (`web`) et jobs de récupération (`worker`)
- [Vue 3](https://vuejs.org/) + [Vite](https://vite.dev/) : application côté client
- [Spring Security 7](https://spring.io/projects/spring-security) + Spring Session JDBC : authentification, sessions persistantes
- [Quartz](https://www.quartz-scheduler.org/) : planification des jobs
- [PostgreSQL 18](https://www.postgresql.org/) + [Flyway 13](https://flywaydb.org/) : persistance et migrations
- [Resend](https://resend.com/) : emails ; Web Push (VAPID) : notifications
- Wikidata, OpenStreetMap et Deezer : noms canoniques et position des salles
- [Maven](https://maven.apache.org/) : monorepo (`core`, `sources`, `web`, `worker`)

## Installation

Voir le [wiki Déploiement](https://codeberg.org/Kwaite/strapontin/wiki/Déploiement) pour les instructions complètes (LXC Proxmox, variables d'environnement, scripts de provisioning).

## Licence

MIT — voir [LICENSE](LICENSE) ou la page `/aide` de l'application.

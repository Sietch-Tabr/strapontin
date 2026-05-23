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

- [Spring Boot 4](https://spring.io/projects/spring-boot) + [Thymeleaf](https://www.thymeleaf.org/) — rendu serveur, Java 25
- [Spring Security 7](https://spring.io/projects/spring-security) + Spring Session JDBC — authentification, sessions persistantes
- [Quartz Scheduler](http://www.quartz-scheduler.org/) — jobs background (fetch concerts)
- [Flyway 12](https://flywaydb.org/) + PostgreSQL 18 — migrations et persistance
- [Resend](https://resend.com/) — emails transactionnels
- [Maven](https://maven.apache.org/) — monorepo (modules `core`, `sources`, `web`, `worker`)

## Installation

Voir le [wiki Déploiement](https://codeberg.org/Kwaite/strapontin/wiki/Déploiement) pour les instructions complètes (LXC Proxmox, variables d'environnement, scripts de provisioning).

## Licence

MIT — voir [LICENSE](LICENSE) ou la page `/aide` de l'application.

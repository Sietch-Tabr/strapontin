---
paths:
  - "java/web/src/main/resources/db/migration/**"
---

# Migrations Flyway

**Ne jamais modifier une migration déjà appliquée/committée à la main** — toujours une nouvelle migration.

Un seul jeu : `java/web/.../db/migration/`. Seul web migre la production ; les tests d'intégration worker appliquent ce même jeu (`spring.flyway.locations=filesystem:../web/...` dans `application-test.properties`), le schéma des tests worker est donc par construction celui de la prod. `users.id` : type `text` (UUID généré par Java). `salles.id` : PK uuid ; ancienne clé texte conservée en `salles.salle_cle` (UNIQUE).

Toute clé étrangère reçoit un index dont elle forme le préfixe (suppressions en cascade, `SET NULL`, vérification `NO ACTION`), sauf choix explicite documenté dans la migration.

Schéma actuel table par table : [wiki Schéma](https://github.com/Sietch-Tabr/strapontin/wiki/Schéma-de-base-de-données) ; historique des migrations : [wiki Roadmap](https://github.com/Sietch-Tabr/strapontin/wiki/Roadmap).

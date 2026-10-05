---
paths:
  - "java/**/*.java"
---

# Conventions Java

- Identifiants en français (`nombreUtilisateurs`, `calculerTotal`).
- Logging : SLF4J, jamais `System.out`.
- Commentaires : jamais de référence à un numéro de ticket Codeberg — le commentaire sert le code et le projet, pas l'historique de sa résolution ; les tickets sont référencés dans les messages de commit et les PR, jamais dans le code source.
- Nommage tests : `<methode>_<scenario>_<resultatAttendu>`.

## Tests

- `mvn -pl core,sources,web test` (unitaires — nécessite Docker, `AdminServiceIntegrationTest`/`SalleRepositoryIntegrationTest` montent un vrai PostgreSQL via Testcontainers).
- `mvn -pl worker test -am` (requiert Docker, Testcontainers — exécute aussi ses propres migrations Flyway pour de vrai en test, contrairement à la prod).
- Toujours `mvn -f java/pom.xml clean test -pl <modules> -am` — `mvn test` sans `clean` peut masquer une vraie erreur de compilation cross-module via le cache incrémental. `java/core` doit être `mvn install`é localement avant des tests ciblés sur `sources`/`worker`/`web` en isolation.
- Jamais `-DskipTests`.

## Pièges de session récurrents

- Le hook de formatage post-Edit strip les imports inutilisés au moment exact de l'Edit — ne jamais ajouter un import dans un Edit séparé de son usage (l'un avant que l'usage existe, l'un après sans le réajouter).
- `@Autowired` explicite dès qu'une classe Spring a plus d'un constructeur.
- `CAST(:param AS uuid)` avec `NamedParameterJdbcTemplate` — jamais `::uuid` (non supporté par le driver de paramètres nommés).

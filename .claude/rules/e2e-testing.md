---
paths:
  - "e2e/**/*.ts"
---

# Tests E2E (Playwright)

Nécessite le LXC dev actif (port 8080). Lancer : `cd e2e && npx playwright test` (mdp surchargeable via `E2E_ADMIN_MOT_DE_PASSE`/`E2E_USER_MOT_DE_PASSE`) — **toujours sans `--project`**, les 5 navigateurs (chromium, firefox, webkit, mobile-chrome, mobile-safari) doivent tourner.

**Deux comptes dédiés** créés par `bash scripts/seed-e2e.sh root@192.168.20.78` : `e2eadmin`/`e2euser`. Idempotent, supprime et recrée les users (CASCADE DELETE) — après relance, supprimer `e2e/tests/e2e/.auth-*.json` pour forcer la reconnexion. Réinitialise aussi `user_config.departements` à `{}` pour `e2euser` (un filtre resté positionné fait disparaître silencieusement ses matches au cycle suivant de `RecalculerMatchesJob`).

`global-setup.ts` ouvre 2 sessions (`.auth-admin.json`, `.auth-user.json`) via `POST /api/auth/login`, puis réinitialise les buckets rate-limit (`DELETE /api/salles/rate-limits/signalement`, profil `dev` uniquement). Échoue immédiatement (avant tout login) si un `playwright-mcp` externe persistant est détecté — une session de debug manuelle active en parallèle peut ralentir toute la suite d'un facteur ~4x sans jamais la faire échouer clairement.

Tests "Scénario: API auth" et le test signalement valide restreints à chromium (rate limiter bucket4j). `invitation_tokenValide` ne tourne que sur chromium — crée sa propre invitation via l'API admin, indépendant du seed.

Nommage : `<methode>_<scenario>_<resultatAttendu>`.

## Diagnostiquer un échec avant de conclure "flaky"

Un test "flaky" (échec puis succès au retry) n'est pas un run propre — lire le détail complet de l'erreur, pas juste l'exit code. Toute théorie "environnement" doit être prouvée par des données concrètes, jamais déduite par corrélation :

- **Rate-limiter login épuisé** (`RateLimitingLoginFilter`, Caffeine en mémoire, 15 tentatives/15 min par IP, perdu au redémarrage) : chercher `Rate limit connexion atteint` dans `logs_admin` (source `auth`) — se vide par un redémarrage de `strapontin-web`.
- **Mac en veille répétée** : `pmset -g log` (cycles `Idle Sleep`) et `pmset -g batt` avant tout run long ; `caffeinate -dis` en prévention (ne bloque pas une veille forcée manuellement, capot fermé).
- **Instabilité driver Playwright-WebKit sous charge soutenue** (webkit/mobile-safari) : `page.goto` qui timeout sans aucune requête réseau (page blanche) — classe connue, absorbée par `retries: 2` dans `playwright.config.ts`, pas un bug applicatif si le retry réussit en quelques secondes.
- **`playwright-mcp` externe** qui relance en boucle un navigateur (facteur ~4x) — vérifier les process `ps aux` avant un run.

Toujours écarter ces causes connues avec des preuves avant de conclure à un bug de code — mais ne jamais les invoquer pour excuser un échec qui n'y correspond pas exactement.

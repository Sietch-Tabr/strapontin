---
paths:
  - "e2e/**/*.ts"
---

# Tests E2E (Playwright)

Nécessite le LXC dev actif (port 8080). Lancer : `cd e2e && npx playwright test` (mdp surchargeable via `E2E_ADMIN_MOT_DE_PASSE`/`E2E_USER_MOT_DE_PASSE`) — **toujours sans `--project`**, les 5 navigateurs (chromium, firefox, webkit, mobile-chrome, mobile-safari) doivent tourner.

**Deux comptes dédiés** créés par `bash scripts/seed-e2e.sh root@192.168.20.78` : `e2eadmin`/`e2euser`. Idempotent, supprime et recrée les users (CASCADE DELETE) — après relance, supprimer `e2e/tests/e2e/.auth-*.json` pour forcer la reconnexion. Réinitialise aussi `user_config.departements` à `{}` pour `e2euser` (un filtre resté positionné fait disparaître silencieusement ses matches au cycle suivant de `RecalculerMatchesJob`).

`global-setup.ts` ouvre 2 sessions (`.auth-admin.json`, `.auth-user.json`) via `POST /api/auth/login`, puis réinitialise les buckets rate-limit (`DELETE /api/salles/rate-limits/signalement`) la cadence d'invitations (`DELETE /api/admin/invitations/envois`) et les limiteurs de connexion et de création de compte (`DELETE /api/admin/rate-limits/connexion`), profil `dev` uniquement : plus besoin de redémarrer `strapontin-web` entre deux runs rapprochés. Échoue immédiatement (avant tout login) si un `playwright-mcp` externe persistant est détecté — une session de debug manuelle active en parallèle peut ralentir toute la suite d'un facteur ~4x sans jamais la faire échouer clairement.

Tests "Scénario: API auth" et le test signalement valide restreints à chromium (rate limiter bucket4j). `invitation_tokenValide` ne tourne que sur chromium — crée sa propre invitation via l'API admin, indépendant du seed.

Nommage : `<methode>_<scenario>_<resultatAttendu>`.

## Diagnostiquer un échec avant de conclure "flaky"

Un test "flaky" (échec puis succès au retry) n'est pas un run propre — lire le détail complet de l'erreur, pas juste l'exit code. Toute théorie "environnement" doit être prouvée par des données concrètes, jamais déduite par corrélation :

- **Rate-limiter login épuisé** (`RateLimitingLoginFilter`, Caffeine en mémoire, 15 tentatives/15 min par IP, perdu au redémarrage) : chercher `Rate limit connexion atteint` dans `logs_admin` (source `auth`) — se vide par un redémarrage de `strapontin-web`.
- **Quotas du serveur dev épuisés par des runs rapprochés** : création de compte par lien (`/api/auth/reset-password`, 30 par heure et par IP, consommée par `onboarding`/`invitation`, environ 10 par run complet, retries compris) et mot de passe actuel erroné (5 par 15 min et par compte, test `api_mdp_mauvais_actuel_retourne_400` limité à chromium desktop). Symptôme : alerte « Trop de tentatives » ou 429 sur des tests qui passent après un redémarrage de `strapontin-web`. Avant un run de validation suivant d'autres runs, redémarrer `strapontin-web` (prouvé le 01/11/2026 : 7 échecs d'`onboarding`, 9 réussites après redémarrage).
- **Mac en veille répétée** : `pmset -g log` (cycles `Idle Sleep`) et `pmset -g batt` avant tout run long ; `caffeinate -dis` en prévention (ne bloque pas une veille forcée manuellement, capot fermé).
- **Instabilité driver Playwright-WebKit sous charge soutenue** (webkit/mobile-safari) : `page.goto` qui timeout sans aucune requête réseau (page blanche) — classe connue, absorbée par `retries: 2` dans `playwright.config.ts`, pas un bug applicatif si le retry réussit en quelques secondes.
- **`page.route()` sous WebKit** : toute interception active fait passer *toutes* les requêtes de la page par Playwright, qui attribue parfois à une URL la réponse d'une autre (CSS reçu en `text/javascript`, script en `text/html`, navigation initiale jamais terminée) — environ 1 fois sur 170, aucune sur 600 sans interception (mesuré le 05/10/2026). N'activer `page.route()` qu'une fois l'application chargée, puis revenir sur la page par un clic de navigation interne (voir `navigation.e2e.ts`, indicateur de chargement).
- **`playwright-mcp` externe** qui relance en boucle un navigateur (facteur ~4x) — vérifier les process `ps aux` avant un run.

Toujours écarter ces causes connues avec des preuves avant de conclure à un bug de code — mais ne jamais les invoquer pour excuser un échec qui n'y correspond pas exactement.

## Outils scriptés

- `bash e2e/lancer-e2e.sh [journal]` : le run complet dans les mêmes conditions à chaque fois (arrête les `playwright-mcp` du poste, vérifie que le dev répond, `caffeinate`) et une ligne de bilan « N réussis, N échecs, N instables, N ignorés ». Il relève aussi chaque minute la charge du poste et du dev (`/tmp/e2e-charge.log`, maximum rappelé au bilan) et affiche la durée cumulée par projet, avec un avertissement au-delà de deux fois la médiane. Code de sortie nul seulement sans échec ni instable.
- `node e2e/outils/releve-console.mjs [pages…]` : console (error, warning), erreurs de page et HTTP ≥ 400 en session utilisateur puis admin, chromium, firefox et webkit. Le seul relevé connu, compté à part : WebKit signale une violation `style-src-attr` par `<select>` sous la politique `style-src 'self'` (une page vide avec un `<select>` la produit aussi, Chromium jamais) ; l'assouplir affaiblirait la politique, rien n'est donc changé.
- `node e2e/outils/verifier-service-worker.mjs [dossier] [port]` : enregistrement, précache et rechargement hors ligne du `sw.js` construit, sur `localhost` (le dev en HTTP sur IP privée ne l'expose pas, voir `pwa.e2e.ts`).

## Règles de la suite (3.1.4)

- Durée normale d'un run complet : environ 15 minutes (cinq projets, `workers: 1`, `trace: on-first-retry`). `lancer-e2e.sh` affiche la durée par projet et avertit au-delà de deux fois la médiane ; un run à 40 minutes ou plus est une anomalie à diagnostiquer, pas une durée normale.
- Jamais de tolérance de statut (`[401, 429]`) ni de `test.skip` faute de donnée : la précondition est posée (seed, API, compte jetable `creerCompteJetableEtSeConnecter`) ou le test échoue. `e2euser` suit plus de 150 concerts (pagination de Mes spectacles).
- Pas de `networkidle` comme substitut de « page prête » : attendre un élément ou un état (`toPass` pour un rendu refait). Les nettoyages passent par `nettoyer()`, qui journalise l'échec.
- Montée de Playwright : `npx playwright install` pour télécharger les navigateurs de la nouvelle version (sinon 1 281 échecs `Executable doesn't exist`).
- Ordre des tests : `npx playwright test --shuffle` (graine affichée, rejouable) révèle un test qui dépend d'un autre. Évalué sur chromium avec deux graines en 3.1.5 : 342 réussis, aucun échec. À rejouer (diagnostic, un projet suffit) après l'ajout de tests partageant un état (comptes, suivis, salles).

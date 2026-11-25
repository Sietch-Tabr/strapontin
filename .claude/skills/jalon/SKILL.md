---
name: Jalon
description: >
  Implémente un jalon de bout en bout : vérification des tickets, mises à jour de dépendances,
  analyse validée par l'utilisateur, code par groupes commités, tests complets, déploiement dev,
  déclenchement des 12 jobs Quartz, console navigateur, revue, documentation et PR.
when_to_use: >
  Invoquer dès qu'un message commence par "Tickets du jalon X.Y.Z à implémenter" ou par
  "/jalon X.Y.Z". La version du jalon est celle indiquée dans ce message.
---

Jalon à implémenter : la version donnée dans le message qui t'invoque.

Terminé = run final de tests à zéro échec (chiffre exact), 12 jobs sur 12 sans erreur, console navigateur propre (session utilisateur et admin), PR créée.

1. Invoque le skill check-deps (mode jalon).
2. Vérifie en ligne les tickets du jalon (`gh issue view N --comments`) : titre, corps ET commentaires. Si un ticket décrit une anomalie de données en prod à corriger, recherche systématiquement s'il existe aussi un moyen de la corriger par le code (cause racine), même si le ticket ne le demande pas, et remonte ce point dans l'analyse de l'étape 5.
3. Commentaires dans le code : 2 lignes maximum, le pourquoi seulement, aucune référence à un ticket, à un jalon ni à leur résolution. Le commentaire sert au code et au projet, pas à ta mémoire.
4. Périmètre : ne modifie que ce qui sert les tickets du jalon. Tout écart repéré en route est corrigé dans la foulée s'il est trivial, sinon ticketé via `/ticket`.
5. Analyse, résume-moi ce que tu vas faire, puis attends mon go avant tout code. Après mon go, écris le plan et l'avancement par étape dans la mémoire (mis à jour à chaque commit), puis code sur une nouvelle branche.
6. Bump `<revision>` pour correspondre au jalon si ce n'est pas déjà fait, en premier commit. Les mises à jour de dépendances de check-deps (étape 1) vont dans un deuxième commit distinct.
7. Commits :
   - au fur et à mesure, au minimum un commit par groupe ou phase cohérente, jamais un unique commit final, même si les changements s'imbriquent entre tickets ;
   - commit automatique autorisé, sans redemander, dès que le groupe est validé sur le dev (étape 13) et que les tests concernés passent ; le commit de bump et les commits de documentation n'attendent pas ;
   - documentation et wiki mis à jour à chaque commit ; le wiki est un dépôt séparé (`../strapontin-wiki`), commité et poussé indépendamment.
8. Mets à jour le changelog utilisateur.
9. Crée et mets à jour les tests (Java, Vitest, E2E).
10. Règle de rigueur, valable pour toute la suite (tests, warnings, jobs, console navigateur) : aucune erreur ni aucun warning n'est présumé préexistant ni excusé par une cause externe (rate-limit, ressource partagée...). Diagnostique toujours la cause racine réelle (jamais une hypothèse non vérifiée), corrige le code ou l'environnement, puis reboucle jusqu'à un résultat strictement propre.
11. Docker Desktop (Mac) n'est ouvert qu'au besoin : le démarrer (`open -a Docker`) et attendre `docker info` avant le premier usage (build Maven avec tests, suite de tests, `analyse-dependances.sh`, E2E si concerné), puis le quitter (`osascript -e 'quit app "Docker"'`) dès qu'aucune étape ne le requiert plus, comme le LXC dev. Ne pas le laisser ouvert entre deux phases sans usage prévu.
12. Lance la suite complète avec le skill `/test` (Java, Vitest, E2E) en appliquant l'étape 10, jusqu'à un run unique de bout en bout à zéro échec. Le run final doit suivre le dernier changement de code ou de configuration : toute correction invalide les runs précédents. Rapporte le chiffre exact (passed/failed/skipped) de ce run final.
13. Déploie sur le LXC dev avec `/deploy-dev`. Avant, démarre le LXC dev (VMID 114) s'il est arrêté (`pct start 114`, via `.claude/scripts/acces.local.sh`).
14. Après déploiement, déclenche tous les jobs Quartz planifiés, y compris les nocturnes, avec `declencher-jobs-dev.sh` (ne pas réécrire la boucle), pour détecter une erreur SQL ou applicative qui ne se manifeste qu'à l'exécution réelle. Un par un, jamais en parallèle (Ticketmaster limite à 2 jobs simultanés avant 429), pour chacun des 12 types : fetch:artistes:batch, fetch:region, fetch:salles:all, purger:concerts, purger:salles, purger:artistes, purger:logs-admin, revoquer:admins, recalculer:matches, notifier:digest, reconciliation:nocturne, suivi:cycles-jobs. Vérifie `logs_admin`, `fetch_jobs` et `journalctl` après chaque job et rapporte « N examinés sur 12 ».
15. Vérifie toutes les erreurs et warnings de la console navigateur (Playwright MCP ou Safari MCP) sur les pages impactées par le jalon, en session utilisateur (`e2euser`) ET en session admin (`e2eadmin`), pas seulement via les tests automatisés.
16. Si une anomalie apparaît aux étapes 12, 14 ou 15 : applique l'étape 10, corrige, redéploie (étape 13), puis recommence les étapes 12 à 15 jusqu'à un passage propre. Si la même anomalie résiste après 5 cycles diagnostic-correction, ou si la correction suppose un choix qui me revient, arrête-toi et expose le diagnostic.
17. Une fois les vérifications terminées, arrête le LXC dev (`pct stop 114`, via `.claude/scripts/acces.local.sh`) et quitte Docker Desktop si aucune autre session de travail n'est prévue dans l'immédiat.
18. Revue en contexte neuf avant la PR, par les agents selon les fichiers touchés : `code-reviewer` (toujours), `sql-reviewer` (migrations ou requêtes), `frontend-reviewer` (Vue, CSS, JS), `security-reviewer` (JDBC, authentification, secrets). Corrige les anomalies selon l'étape 10 ; si du code a changé, reprends les étapes 12 à 15.
19. Mets à jour CLAUDE.md, la mémoire (`todo.md`, `MEMORY.md` : jalon courant, tickets fermés), la roadmap et le wiki (dépôt séparé, commit indépendant). Avant d'ajouter la doc, vérifie qu'aucun lot précédent n'a été oublié dans la roadmap et l'historique. Lance `bash scripts/analyse-dependances.sh local` avant la PR et propose toute exception OSV datée avant la PR, jamais après le merge.
20. Crée la PR avec `/pre-pr` : `Closes #N` répété devant chaque ticket du jalon, dans le corps (`--body-file`).

Compte rendu final : tableau étape par étape avec la preuve de chacune (commande exécutée et résultat obtenu), chiffre exact du run final de tests, « N examinés sur M » pour les jobs.

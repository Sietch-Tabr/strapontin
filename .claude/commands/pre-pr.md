Met à jour toute la documentation impactée par les changements de la branche courante, puis crée la PR sur GitHub.

## 1. Analyser les changements

```bash
git log $(git merge-base HEAD origin/HEAD)..HEAD --oneline
```

Compléter avec `detect_changes` (code-review-graph) pour identifier le blast-radius et les composants touchés.

## 2. Wiki

Lire les pages wiki dans `strapontin-wiki/` potentiellement impactées par les changements :
Home.md, Manuel-utilisateur.md, Préférences-export-import.md, Administration.md,
Spécifications.md, Limitations.md, Glossaire.md, ADR.md, Architecture.md,
Sources-de-données.md, Schéma-de-base-de-données.md, Routes.md, Design-et-navigation.md,
Authentification.md, Sécurité.md, Notifications-email.md, Notifications-push.md,
Déploiement.md, Exploitation.md, Développement-local.md. Roadmap.md : une section par
version, reprise de `front/src/pages/changelogVersions.js`.

Pour chaque page concernée : comparer avec l'implémentation réelle, proposer les mises à jour, attendre validation, éditer.

Les modifications du wiki sont committées et poussées dans `strapontin-wiki/` au jalon même, jamais accumulées. Contrôle obligatoire après le push : `git ls-remote github refs/heads/master` (le wiki GitHub est sur `master`, le clone local sur `main` : `git push github main:master`) doit donner le même hash que `git rev-parse HEAD`. Sinon, s'arrêter et le signaler : le dépôt local ne prouve pas que le serveur a reçu les commits.

## 3. CLAUDE.md

Mettre à jour `CLAUDE.md` si l'architecture, la stack, les commandes, les jobs Quartz, les sources de données ou les conventions ont changé. Le détail propre à un package va dans le `CLAUDE.md` imbriqué de son dossier (`persister/`, `sante/`, `core/`…), pas à la racine : la racine reste sous 20 Ko (`wc -c CLAUDE.md`).

## 4. Changelog utilisateur

Ajouter la version en tête de `front/src/pages/changelogVersions.js` (page `/changelog`), sans dépasser la version de `<revision>` du pom.
Uniquement les changements visibles utilisateur ou admin (nouvelles fonctionnalités, corrections visibles, suppressions), formulés pour eux.
Ne pas mentionner les refactors internes, corrections de sécurité non visibles, ou changements d'infrastructure.

## 5. README

Mettre à jour `README.md` si les prérequis, la description ou les instructions de build ont changé.

## 6. Analyse des vulnérabilités des dépendances

```bash
bash scripts/analyse-dependances.sh local
```

Bloquant : même analyse OSV que l'étape `analyse-dependances` de la pipeline de `main`, qui n'existe pas sur les branches. Un avis nouveau est signalé à l'utilisateur avant la PR (correction, ou exception datée dans `osv-scanner.toml` avec sa raison), jamais découvert après le merge.

## 7. Contrôle de cohérence documentation et code

```bash
scripts/verifier-doc.sh
```

Compare le wiki, `CLAUDE.md` et le changelog au code : liens du wiki, routes, tables du schéma, versions annoncées, entrée de la Roadmap pour chaque version publiée et pour la version en cours, entrée du changelog utilisateur. Tout `[ERREUR]` est corrigé avant la PR (le wiki est un dépôt séparé : ce contrôle ne peut pas tourner en pipeline, il passe donc ici à chaque jalon).

## 8. Commit documentation

Proposer le message de commit (`docs: ...`), attendre validation, puis committer.

## 9. Créer la PR

```bash
gh pr create --title "..." --body-file <fichier.md> --label "<bug|enhancement>" --milestone "<jalon>"
```

Le fichier contient `Closes #N` (mot-clé répété devant chaque ticket : `Closes #A, closes #B`), puis `## Résumé`.

- Toujours inclure `Closes #N` dans le corps (pas seulement le titre) ; le mot-clé n'agit que sur une PR qui vise la branche par défaut.
- Base : ne pas spécifier `--base` (branche par défaut du dépôt).
- Étiquettes : `bug` ou `enhancement`.
- La CI ne tourne que sur `main` : rejouer ici les commandes des jobs `tests-java`, `analyse-statique`, `front` et `dependances` avant la PR.

## 10. Suite

Afficher l'URL de la PR et s'arrêter. Le merge, la vérification de la prod, le tag, la release et la fermeture du jalon relèvent de `/cloture-jalon`, lancé par l'utilisateur.

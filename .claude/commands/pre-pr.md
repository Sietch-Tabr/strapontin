Met à jour toute la documentation impactée par les changements de la branche courante, puis crée la PR sur Codeberg.

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

## 3. CLAUDE.md

Mettre à jour `CLAUDE.md` si l'architecture, la stack, les commandes, les jobs Quartz, les sources de données ou les conventions ont changé. Le détail propre à un package va dans le `CLAUDE.md` imbriqué de son dossier (`persister/`, `sante/`, `core/`…), pas à la racine : la racine reste sous 20 Ko (`wc -c CLAUDE.md`).

## 4. Changelog utilisateur

Ajouter la version en tête de `front/src/pages/changelogVersions.js` (page `/changelog`), sans dépasser la version de `<revision>` du pom.
Uniquement les changements visibles utilisateur ou admin (nouvelles fonctionnalités, corrections visibles, suppressions), formulés pour eux.
Ne pas mentionner les refactors internes, corrections de sécurité non visibles, ou changements d'infrastructure.

## 5. README

Mettre à jour `README.md` si les prérequis, la description ou les instructions de build ont changé.

## 6. Commit documentation

Proposer le message de commit (`docs: ...`), attendre validation, puis committer.

## 7. Créer la PR

```bash
tea pr create -r Kwaite/strapontin \
  --title "..." \
  --description "$(cat <<'EOF'
Closes #N

## Résumé
...
EOF
)"
```

- Toujours inclure `Closes #N` dans le corps (pas seulement le titre).
- Base : branche d'intégration courante (ne pas spécifier `--base` pour utiliser la branche par défaut du dépôt).
- Labels : selon nature (`Bug`, `Feature`, `Task`…).

## 8. Suite

Afficher l'URL de la PR et s'arrêter. Le merge, la vérification de la prod, le tag, la release et la fermeture du jalon relèvent de `/cloture-jalon`, lancé par l'utilisateur.

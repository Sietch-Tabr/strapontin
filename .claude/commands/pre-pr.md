Met à jour toute la documentation impactée par les changements de la branche courante, puis crée la PR sur Codeberg.

## 1. Analyser les changements

```bash
git log $(git merge-base HEAD origin/HEAD)..HEAD --oneline
```

Compléter avec `detect_changes` (code-review-graph) pour identifier le blast-radius et les composants touchés.

## 2. Wiki

Lire les pages wiki dans `strapontin-wiki/` potentiellement impactées par les changements :
Architecture.md, Authentification.md, Design-et-navigation.md, Déploiement.md,
Développement-local.md, Exploitation.md, Routes.md, Schéma-de-base-de-données.md,
Sources-de-données.md, Notifications-email.md, Notifications-push.md,
Préférences-export-import.md, Manuel-utilisateur.md.

Pour chaque page concernée : comparer avec l'implémentation réelle, proposer les mises à jour, attendre validation, éditer.

## 3. CLAUDE.md

Mettre à jour `CLAUDE.md` si l'architecture, la stack, les commandes, les jobs Quartz, les sources de données ou les conventions ont changé.

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

## 8. Merge et nettoyage

Après création de la PR, afficher l'URL et attendre la confirmation explicite de l'utilisateur avant de merger.

Une fois le merge confirmé :

```bash
# Merger la PR
tea pr merge -r Kwaite/strapontin <PR_ID> --merge

# Supprimer la branche locale
git branch -d <branche>

# Supprimer la branche distante
git push origin --delete <branche>

# Se repositionner sur la branche d'intégration et mettre à jour
git checkout <branche-base>
git pull
```

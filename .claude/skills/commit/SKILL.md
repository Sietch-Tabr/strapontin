---
name: commit
description: Prépare et soumet un commit — LSP check, tests, message format projet. Demande confirmation avant d'agir.
---

Avant toute action, résumer ce qui va être commité et attendre un "oui" explicite.

1. Déléguer à l'agent `typecheck` la vérification des fichiers `.ts` et `.svelte` listés par `git diff --name-only`. Corriger toute erreur signalée avant de continuer.
2. Si des fichiers de test couvrent les modules modifiés, lancer `pnpm -r test`.
3. Afficher `git diff --stat`.
4. Proposer un message de commit au format `type: description courte en français`.
   Types valides : feat, fix, refactor, docs, test, style, perf, chore.
5. Attendre validation explicite du message.
6. `git add -A && git commit -m "..."`

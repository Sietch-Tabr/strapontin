---
paths:
  - "front/src/**/*.vue"
  - "front/src/**/*.js"
---

# UI / Design system (Vue 3 SPA)

- **Structure** : `front/src/pages/` (pages), `front/src/components/` (composants), `front/src/composables/` (logique réutilisable avec état/cycle de vie Vue), `front/src/utils/` (fonctions pures sans dépendance Vue, testables par Vitest, ex. `statutReferentiel.js` partagé entre `AdminSalles.vue`/`AdminArtistes.vue`).
- **CSS vanilla** — variables CSS pour thème clair/sombre, dans `front/src/styles/` (importés dans `main.js` → fingerprinting Vite vers `/assets/index-*.css`) pour les classes réellement partagées entre plusieurs composants (boutons, badges, dialogs, formulaires, tableaux…). Depuis v2.8.7, une classe utilisée par un seul composant vit dans son propre `<style scoped>` (Vue génère alors un chunk CSS par page). Classes en français kebab-case, BEM (`--modificateur`, `__element`) — seuls quelques termes techniques déjà établis dans le code/la documentation restent en anglais (`auth`, `job`/`jobs`, `log`/`logs`, `worker`, `toast`, `checklist`, `pagination`, `chips`).
  - ⚠️ **Piège du scoping découvert en v2.8.7** : une classe posée hors de l'arbre de rendu Vue (`.sombre`/`.clair` sur `<html>` via `useTheme.js`) ne doit **jamais** être considérée mono-composant même si un grep textuel brut la trouve dans un seul fichier — une recherche naïve sur tout le texte du fichier confond un usage de classe réel avec une simple valeur de chaîne sans rapport (ex. `<option value="sombre">`). Extraire les classes candidates **uniquement** depuis les attributs `class=`/`:class=` du bloc `<template>`, jamais du fichier entier.
  - ⚠️ **Second piège** : un sélecteur composé combinant une classe mono-composant avec un modificateur d'état (`.X.actif`, `.X:hover`, `.sombre .X`) doit rester entièrement dans le même fichier que la règle de base de `.X` — les séparer (base en scoped, modificateur resté global) crée un conflit de spécificité/ordre de cascade silencieux dès que la propriété est la même des deux côtés (ex. `.modif-ligne`/`.modif-ligne.visible`, `.nav-bas-plus`/`.nav-bas-plus.actif`). Un sélecteur descendant ciblant un **autre** élément (`.X enfant`, `.X .Y` où Y n'est pas mono-composant) reste sûr à laisser scindé, aucune propriété n'entre en conflit.
- **PWA** : `@unplugin-pwa/vite` (stratégie `buildSW`) compile `front/src/sw.js` → `static/sw.js` (artifact gitignored). Le précache (Workbox) couvre les bundles, les assets/`index.html`/`offline-fallback.js`. Mise à jour automatique : `controllerchange` → `window.location.reload()` dans `App.vue`. `front/public/offline-fallback.js` : filet de secours hors bundle (script externe, jamais inline — bloqué par la CSP `script-src 'self'`) affichant un message si `#app` reste vide 4s après chargement.
- **Badge "Nouveau"** : basé sur `user_matches.vu_le IS NULL` — remis à `NULL` à la visite de `/`. `notification_envoyee_le` est une colonne distincte pour le suivi des envois email/push.
- **Thème** : cookie `theme` (1 an) + `user_config.theme` → pas de FOUC.
- **Navigation** : Vue Router (`front/src/router/`) — sidebar desktop, bottom nav + drawer mobile. `useEstMobile()` (`front/src/composables/useBreakpoint.js`, seuil 1024px) contrôle le rendu conditionnel via `v-if` — pas de duplication DOM ni de `display: none`.
- **Pages à onglets** (`admin`, `parametres`) : orchestrateur léger + sous-composants autonomes (`KeepAlive` + `v-if`), chaque sous-composant fetch ses données dans `onMounted`. `<select v-model>` sur mobile, `<nav v-else>` sur desktop, rendu conditionnel via `useEstMobile()`. Onglet actif géré par Vue (`:class="{ actif }"`) — pas de sélecteurs CSS `:has()`.
  - Admin : `front/src/components/admin/`, 8 onglets : Tableau de bord (AdminJobs), À traiter (AdminATraiter = AdminSignalements + AdminConcerts), Référentiel (AdminReferentiel = AdminSalles/AdminArtistes, entité et recherche dans l'URL), Sources, Logs, Configuration, Communication (AdminBanniere), Utilisateurs. Les anciennes ancres (`#salles`, `#signalements`…) redirigent vers l'onglet qui les regroupe.
  - Paramètres : `front/src/components/parametres/` (ParametresPreferences, ParametresNotifications, ParametresCompte, ParametresDonnees)
- **Toasts** : Popover API, `afficherToast(message, type)` retourne un `id` pour fermeture ciblée. `App.vue` gère offline/online (toast persistant à la perte de connexion, fermé à la reconnexion). Position mobile (`components.css`, breakpoint mobile) inclut `env(safe-area-inset-bottom)` — sinon masqué par la bottom nav sur iPhone avec home indicator, même piège que `.nav-bas`/`.contenu-principal`.
- **CSS mobile-first** : les media queries du projet sont `min-width` (mobile = comportement par défaut, desktop en surcharge) — pas `max-width`. Attention aux interactions shorthand/longhand (ex. `border-radius` vs `border-bottom-*-radius`) lors de toute conversion/ajout.
- **Tableaux** : toujours dans un conteneur `table-conteneur table-cartes` (cartes sur mobile, `styles/components.css`), chaque `<td>` avec un `data-label` (vide pour la cellule d'actions). **Dialogues** : titre `<h2 class="dialog-titre" id="…">` relié par `aria-labelledby`. Les deux sont vérifiés par `src/gabarits.test.js`, l'accessibilité des gabarits par `eslint-plugin-vuejs-accessibility`.
- **Données hors ligne** : tout appel passe par `appelApi`, qui garde les listes de l'utilisateur pour le hors ligne ; une nouvelle liste à rendre disponible hors ligne s'ajoute à `CHEMINS_CACHABLES` (`utils/cacheHorsLigne.js`), jamais une recherche. Détail : [`front/CLAUDE.md`](../../front/CLAUDE.md).
- **Fetch/AbortController** : tout fetch de montage (`onMounted`)/action destructive doit avoir un try/catch et, pour les pages avec fetch réactif au changement de route/filtre, un `AbortController` annulant l'appel précédent avant d'en lancer un nouveau.
- **Onboarding** (`front/src/components/ModaleEtapes.vue`) : composant générique à étapes (dialog natif, Suivant/Précédent/Terminer), réutilisable pour un futur flux similaire (ex. annonce de mise à jour majeure) — piloté par `useOnboarding.js`/`OnboardingModal.vue` pour la modale de bienvenue actuelle. `verifierOnboarding()` doit être rappelée non seulement au montage de `App.vue` mais aussi dans son `watch(estPageAuth)` (sortie d'une page d'authentification) — l'activation d'un compte invité fait un `router.push` SPA, pas un rechargement complet, donc l'unique `onMounted` s'exécute avant la connexion et échoue en 401 sans ce second point d'entrée (même pattern déjà en place pour `chargerSession`/`chargerBanniere`).

## Charte graphique

Un rôle = un style, partout. Contrôlée par `src/charte.test.js` (Vitest, bloquant en CI) : toute nouvelle classe ou variante doit y trouver sa place, jamais une exception locale.

| Rôle | Classes | Exemples |
|---|---|---|
| Action principale (une par zone ou dialogue) | `bouton bouton-primaire` | Enregistrer, Envoyer, Notifier, Confirmer |
| Action secondaire | `bouton bouton-secondaire` | Billets, Rafraîchir, Fermer (dialogue sans autre choix) |
| Annulation, action discrète (soulignée : cliquable sans survol) | `bouton bouton-texte` | Annuler, Masquer, Réafficher, Déconnexion |
| Action destructive ou irréversible | `bouton bouton-danger` | Supprimer, Fusionner, Révoquer, Effacer, Publier un incident |
| Choix parmi des options (`aria-pressed`) | `bouton bouton-bascule` + `actif` sur l'option retenue | Thème, niveaux de logs, Salles/Artistes |
| Icône seule (`aria-label` obligatoire) | `bouton-icone` | fermeture de toast ou de bannière |

- Taille : `bouton-petit` dans les zones denses (lignes de tableau, cartes, en-têtes de groupe), `bouton-mini` pour une action secondaire posée à côté d'une action plus importante (Masquer près de Billets), sinon taille normale ; tous les boutons d'une même zone ont la même taille. `bouton-pleine-largeur` réservé aux pages d'authentification. Un lien stylé en bouton suit les mêmes règles.
- Dialogue : action d'abord (principale ou destructive), « Annuler » ensuite en `bouton-texte`. Confirmation simple : composant partagé `DialogueConfirmation.vue` (`demander({ titre, message, action })` → promesse booléenne).
- Boutons dans `styles/components.css` uniquement ; badges (`badge-*`) et alertes (`alerte-*`) dans les feuilles partagées `styles/*.css`. Un composant peut placer un élément de la charte (position, marges, flex, largeur), jamais en changer l'aspect.
- Autres éléments partagés : badge `badge` + variante sémantique (`badge-succes`, `badge-avertissement`, `badge-danger`, `badge-info`, statuts de concert) ; alerte `alerte alerte-{info,succes,avertissement,erreur}` ; champ `form-champ` > `form-label` + `form-control` ; dialogue `dialog-titre`, `dialog-corps`, `dialog-pied` ; bloc de réglage `param-bloc` (`param-bloc-danger` pour une action exceptionnelle).
- Exception documentée : les éléments de navigation (`nav-bas-plus`) suivent le style des liens de navigation.

### Aspect porté par les variables du thème

L'aspect (couleurs, ombres, icônes, tailles de police, espacements, rayons) ne dépend que des variables de `styles/base.css` : jamais une valeur en dur dans une feuille ou un composant. Changer de thème, c'est redéfinir ces variables (le thème sombre est un bloc `.sombre` de variables, sans règle par élément).

- Couleurs : remplissage de l'action principale et de l'option active (`--action-bg`/`--action-fg`, grises en sombre pour ne pas dominer l'écran, contraste vérifié par `charte.test.js`) ; variables sémantiques (`--info-*`, `--avertissement-*`, `--danger-*`, `--succes-*`) et teintes de statut des badges (`--teinte-{bleu,rouge,jaune,orange,violet,cyan,gris,ardoise}-{bg,fg}`). Voiles, ombres et halos : `--voile-*`, `--ombre-*`, `--nouveaute-*`. Icônes dont le trait suit le thème (SVG en `url()`) : `--icone-chevron`, `--icone-recherche`, `--icone-coche`.
- Espacement : `--espace-N`, avec N en huitièmes de rem (`--espace-4` = 0,5rem, `--espace-8` = 1rem). Police : `--texte-{4xs…2xl}` (`--texte-m` = 0,875rem, `--texte-reduit` en `em`). Rayons : `--radius`, `--radius-s`, `--radius-l`, `--radius-xl`, `--radius-pill`. Autres : `--trait-fin`, `--cible-tactile`, `--contenu-largeur`.
- Une variable utilisée est toujours définie, sans valeur de repli (`var(--x, …)` interdit).
- Rôles partagés : `cadre` (+ `cadre-fond`, `cadre-large`, `cadre-serre`, `cadre-avertissement`), `texte-secondaire`/`texte-note`/`texte-discret`, `nav-lien` (+ `nav-lien-large`), `champ-compact` (input/select de zone dense). Un composant les pose en classe et ne réécrit pas leur aspect.
- Une règle qui ne sert qu'un composant, état compris (`.X:hover`, `.X.actif`), vit dans son `<style scoped>`, jamais dans une feuille partagée ; inversement un composant ne redéfinit pas une classe d'une feuille partagée.
- Exception : la page hors ligne de `sw.js`, autonome par construction, porte ses propres valeurs.
- Contrôlé par `src/charte.test.js` : couleur ou dimension en dur, règle `.sombre` hors variables, variable non définie ou à repli, classe partagée redéfinie localement, rôle réécrit, distinction sémantique perdue (filtres de niveau des logs).
- Toute classe d'une feuille partagée doit être utilisée (classes construites par concaténation et noms de `<Transition>` reconnus) : une règle orpheline est supprimée.

- **Quitter une page avec saisie non enregistrée** : `onBeforeRouteLeave` (vue-router) + `DialogueConfirmation.choisir({ titre, message, action, secondaire, annuler })`, qui rend `"action"`, `"secondaire"` ou `"annuler"` (`demander` reste le raccourci booléen) ; `beforeunload` couvre la fermeture de l'onglet. Modèle : `ParametresNotifications.vue` (`modifie` compare le formulaire aux dernières préférences enregistrées).

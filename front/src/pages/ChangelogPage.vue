<template>
  <div>
    <div class="page-header">
      <h1>Nouveautés</h1>
    </div>

    <div class="max-w-xl changelog">
      <p class="page-subtitle">Historique des améliorations et corrections apportées à Strapontin.</p>

      <section>
        <h2 class="changelog-version">v 2.2.3 — 11 juin 2026</h2>
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Interface</p>
            <ul>
              <li>Le bandeau d'information système ne réapparaît plus après avoir disparu automatiquement.</li>
              <li>Les animations de transition entre les pages sont désormais identiques depuis le menu mobile.</li>
            </ul>
          </div>
        </div>
      </section>

      <details>
        <summary><h2 class="changelog-version">v 2.2.2 — 11 juin 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Application installée (PWA)</p>
            <ul>
              <li>Un appui long sur l'icône de l'application propose des raccourcis directs vers Mes spectacles, Découverte et les nouveaux concerts.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Notifications push</p>
            <ul>
              <li>Un bouton « Envoyer une notification de test » dans les paramètres permet de vérifier que les notifications push fonctionnent sur votre appareil.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Accessibilité</p>
            <ul>
              <li>Un lien « Aller au contenu » apparaît au clavier pour passer directement la navigation.</li>
              <li>L'interface respecte désormais la taille de police choisie dans votre navigateur.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Administration</p>
            <ul>
              <li>L'invitation d'un nouvel utilisateur fonctionne même sans service email configuré : le lien d'invitation est alors affiché à l'administrateur pour transmission manuelle.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.2.1 — 11 juin 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Notifications push</p>
            <ul>
              <li>Clic sur une notification push : l'application défile directement jusqu'à la carte de l'artiste concerné et efface le badge de l'application sur l'écran d'accueil.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Interface</p>
            <ul>
              <li>Les alertes visuelles (toasts) s'affichent correctement dans tous les cas, y compris lors des actions d'administration.</li>
              <li>Une alerte s'affiche quand vous perdez votre connexion à Internet ; elle disparaît automatiquement quand la connexion est rétablie.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Connexion</p>
            <ul>
              <li>En cas de trop nombreuses tentatives de connexion, un message explicite indique qu'il faut patienter avant de réessayer.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Administration</p>
            <ul>
              <li>Les liens d'invitation sont désormais valables 7 jours (au lieu de 24 heures).</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.2.0 — 6 juin 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Navigation</p>
            <ul>
              <li>L'application est désormais entièrement côté client (Vue 3) — la navigation entre pages est instantanée, sans rechargement.</li>
              <li>Les transitions entre pages sont animées : le contenu glisse ou fondu en douceur lors de chaque changement de page.</li>
              <li>L'icône « Mes spectacles » dans la barre de navigation est maintenant distincte du bouton « Acheter des billets ».</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Paramètres &amp; Administration</p>
            <ul>
              <li>Les onglets chargent leur contenu à la demande — le premier onglet s'affiche immédiatement, les autres au premier clic.</li>
              <li>L'état de chaque onglet est conservé quand on navigue vers une autre page puis qu'on revient.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Interface</p>
            <ul>
              <li>Les versions précédentes dans cette page s'ouvrent et se referment avec une animation progressive.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Correctifs</p>
            <ul>
              <li>La création de compte par invitation se fait en une seule étape — le lien d'invitation ouvre directement un formulaire pour choisir son identifiant et son mot de passe, sans connexion intermédiaire requise.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.1.3 — 3 juin 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Notifications</p>
            <ul>
              <li>Les préférences email et push sont maintenant indépendantes — une grille permet de choisir séparément pour les artistes et les salles : recevoir l'alerte par email, par push, les deux, ou aucun.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Correctifs</p>
            <ul>
              <li>La session reste active après un changement de réseau (WiFi ↔ mobile) ou un passage en arrière-plan sur PWA — plus de déconnexions inopinées.</li>
              <li>Le clic sur une notification push fait défiler correctement jusqu'à la carte de l'artiste, même quand son nom contient des espaces.</li>
              <li>Les notifications push pour un concert de salle renvoient désormais vers la liste des spectacles plutôt qu'une ancre inexistante sur l'accueil.</li>
              <li>Le bouton "Voir mes spectacles" dans l'email digest renvoie vers l'accueil.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.1.2 — 2 juin 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Notifications push</p>
            <ul>
              <li>Le clic sur une notification ouvre directement l'accueil et fait défiler jusqu'à la carte de l'artiste concerné — sans rechargement si l'application est déjà ouverte.</li>
              <li>Les notifications push sont désormais reçues sur iPhone et iPad (correction d'un problème de chiffrement des clés d'abonnement Apple).</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Correctifs</p>
            <ul>
              <li>La déconnexion depuis le menu de navigation fonctionne correctement après une navigation rapide entre pages.</li>
              <li>Le menu tiroir se ferme automatiquement après un clic sur un lien interne.</li>
              <li>L'onglet actif se met à jour correctement dans la barre latérale et le menu tiroir après chaque changement de page.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.1.1 — 2 juin 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Performance</p>
            <ul>
              <li>La navigation entre les pages est plus rapide : seule la zone de contenu est rechargée côté serveur, sans renvoyer la barre latérale et la navigation mobile à chaque clic.</li>
              <li>Les pages HTML, CSS et JSON sont désormais compressés (Gzip) avant envoi, réduisant la taille des réponses de 60 à 70 %.</li>
              <li>La police et HTMX sont mis en cache par le service worker dès la première visite, évitant de les recharger à chaque session sur mobile et en mode PWA.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Interface</p>
            <ul>
              <li>Les titres des sections dans les pages Administration et Paramètres flottent désormais sur la bordure supérieure de chaque bloc, à la manière d'une légende de formulaire.</li>
              <li>La boîte de confirmation avant la suppression d'un compte utilisateur ou la régénération des clés VAPID s'affiche correctement et attend votre confirmation avant d'agir.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.1.0 — 29 mai 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Interface</p>
            <ul>
              <li>Les actions (ajout, suppression d'artiste ou de salle, sauvegarde des paramètres, activation des notifications push) affichent désormais un message de confirmation en bas de l'écran, qui disparaît automatiquement. Les erreurs restent visibles jusqu'à fermeture manuelle.</li>
              <li>Les administrateurs peuvent désormais publier un message système visible par tous les utilisateurs connectés. Le message apparaît en haut de chaque page et se ferme automatiquement (10 s pour les informations, 20 s pour les avertissements) ou manuellement pour les alertes.</li>
              <li>Le badge « Nouveau » sur l'icône Accueil apparaît dès qu'un concert non encore vu est disponible — sur la barre de navigation mobile et dans le menu latéral sur ordinateur. Il disparaît à la prochaine visite de la page d'accueil.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Mes spectacles &amp; Découverte</p>
            <ul>
              <li>Refonte du tableau de concerts : nom de l'artiste en gras, date en couleur, lieu et ville fusionnés en une seule colonne.</li>
              <li>Les badges de statut (Annulé, Reporté, Complet, Prévente) s'affichent directement sous le nom de l'artiste.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">PWA</p>
            <ul>
              <li>Rechargement automatique de l'application lors du retour de connexion (après une coupure réseau).</li>
              <li>La page hors ligne respecte désormais le thème sombre ou clair choisi par l'utilisateur.</li>
              <li>Le bouton « Réessayer » sur la page hors ligne redirige correctement vers l'accueil sans nécessiter de fermer puis rouvrir l'application.</li>
              <li>Le badge sur l'icône d'accueil (Android, iOS 18+) reflète le nombre de nouveaux concerts — il se met à jour à chaque ouverture et lors des notifications push.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Administration</p>
            <ul>
              <li>Les bounces email (adresses invalides ou inexistantes) sont désormais détectés via les webhooks Resend. L'administrateur reçoit une notification email avec l'adresse concernée. Nécessite la configuration de la clé <code>RESEND_WEBHOOK_SECRET</code> depuis l'administration.</li>
              <li>La page Configuration regroupe désormais les clés par catégorie : Ticketmaster, Email (Resend), Général et Notifications push (VAPID).</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Correctifs</p>
            <ul>
              <li>Le bouton « Acheter des billets » était invisible en vue desktop — corrigé.</li>
              <li>La page Administration était inaccessible et renvoyait vers la connexion — corrigé.</li>
              <li>Les boutons « Copier » et « Fermer » de la modale d'invitation (création d'utilisateur) n'avaient aucun effet — corrigé.</li>
              <li>Après une action dans l'administration (suppression d'utilisateur, sauvegarde de configuration…), la page restait sur le premier onglet au lieu de revenir sur l'onglet d'origine — corrigé.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.0.5 — 29 mai 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Mes spectacles</p>
            <ul>
              <li>Suppression du filtre budget — non fonctionnel, sans données de prix disponibles.</li>
              <li>L'état vide distingue maintenant « aucun artiste ni salle suivi » de « aucun concert avec ces filtres ».</li>
              <li>L'heure du concert s'affiche dans la colonne Date quand elle est disponible.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Découverte</p>
            <ul>
              <li>Suppression du filtre budget. Ajout des badges de statut (Annulé, Reporté, Complet). Bouton Actualiser intégré.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Artistes &amp; Salles</p>
            <ul>
              <li>Confirmation requise avant la suppression d'un artiste ou d'une salle — plus de suppression accidentelle en un clic.</li>
              <li>La salle ajoutée déclenche maintenant un message de confirmation.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Paramètres</p>
            <ul>
              <li>Le toggle « Concerts disponibles uniquement » passe au vert quand il est activé.</li>
              <li>Les cases à cocher des notifications ont un style cohérent avec le reste de l'interface.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Aide</p>
            <ul>
              <li>Limite à 3 signalements par heure par adresse IP.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.0.4 — 28 mai 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Paramètres</p>
            <ul>
              <li>Confirmation par email lors du changement d'adresse email — la nouvelle adresse est validée avant d'être appliquée.</li>
              <li>Messages de confirmation différenciés selon l'action (TOTP activé/désactivé, identifiant modifié, email confirmé).</li>
              <li>Compteur de longueur en temps réel sur le champ mot de passe.</li>
              <li>Notifications push : le bouton d'activation est désactivé si la fréquence email est sur "Jamais".</li>
              <li>Import de préférences : résumé du nombre d'artistes et de salles importés, avec les éléments rejetés le cas échéant.</li>
              <li>Compteur de capacité restante (X/500 artistes — Y/200 salles) visible dans l'onglet Données.</li>
              <li>Suppression de compte : saisie obligatoire du mot "supprimer" pour confirmer l'action.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Correctifs</p>
            <ul>
              <li>Notifications push : les notifications push PWA sont désormais réellement envoyées (un problème de configuration interne empêchait tout envoi depuis la mise en ligne).</li>
              <li>Badge "Nouveau" : le badge apparaît correctement sur l'accueil et dans Mes spectacles pendant 7 jours après la découverte d'un concert — il n'était pas affiché même pour les concerts récents.</li>
              <li>Navigation PWA : le menu "+" et la liste déroulante des onglets fonctionnent de nouveau après une navigation.</li>
              <li>Accueil : les concerts des salles suivies apparaissent désormais correctement (ils n'étaient pas affichés malgré le badge "Nouveau").</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.0.3 — 27 mai 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Correctif</p>
            <ul>
              <li>Les notifications digest ne sont plus silencieusement bloquées depuis la v2.0.2.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Administration — Tableau de bord</p>
            <ul>
              <li>La prochaine date d'exécution planifiée est affichée pour chaque job.</li>
              <li>Le quota Ticketmaster affiche un avertissement visuel quand il descend sous 20 % (orange) ou 10 % (rouge).</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Administration — Logs</p>
            <ul>
              <li>Les sources disponibles dans le filtre sont corrigées (web, push) et la limite est portée à 200 entrées.</li>
              <li>Boutons « Rafraîchir » et « Réinitialiser » ajoutés pour mettre à jour les logs sans recharger la page.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Administration — Configuration</p>
            <ul>
              <li>La régénération des clés VAPID affiche une confirmation avant d'invalider tous les abonnements push existants.</li>
              <li>Une aide contextuelle explique le rôle des clés VAPID et les conséquences d'une régénération.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.0.2 — 26 mai 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Accueil</p>
            <ul>
              <li>Tous vos concerts à venir sont désormais affichés — la limite de 50 concerts sur la page d'accueil est supprimée.</li>
              <li>Les dates de concerts dont l'année est différente de l'année courante affichent maintenant l'année (ex. « jeu. 8 avr. 2027 »).</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Navigation</p>
            <ul>
              <li>L'onglet actif de la barre de navigation mobile se met à jour correctement lors des changements de page.</li>
              <li>L'onglet Accueil ne reste plus actif en double lors de la navigation vers une autre page.</li>
              <li>La déconnexion depuis le menu latéral (desktop) redirige correctement vers la page de connexion.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Notifications</p>
            <ul>
              <li>Les préférences de notification sont désormais respectées : les concerts des salles suivies sont inclus, la désactivation par artiste est honorée, et les fréquences « temps réel » et « quotidien » fonctionnent indépendamment.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Sources de concerts</p>
            <ul>
              <li>Plus de concerts récupérés depuis Ticketmaster : les concerts publiés uniquement en anglais sont désormais inclus, et jusqu'à 200 résultats supplémentaires sont chargés à chaque cycle.</li>
              <li>Les concerts Ticketmaster sans date confirmée (« à annoncer ») ne consomment plus de quota inutilement.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.0.1 — 24 mai 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Spectacles</p>
            <ul>
              <li>Les concerts Ticketmaster avec une prévente affichent désormais un badge « Prévente le JJ/MM » dans la liste de vos spectacles.</li>
              <li>Navigation instantanée entre les pages grâce à Turbo Drive : la barre de navigation reste fixe, sans rechargement complet.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Compte</p>
            <ul>
              <li>La session reste active pendant 30 jours d'inactivité — vous n'êtes plus déconnecté après 30 minutes.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.0.0 — 24 mai 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Infrastructure</p>
            <ul>
              <li>Migration complète vers Java / Spring Boot — l'application ne dépend plus de Node.js.</li>
              <li>Spring Security remplace Better-Auth pour la gestion des sessions et de l'authentification.</li>
              <li>Interface serveur Thymeleaf (rendu côté serveur) en remplacement de SvelteKit.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Administration</p>
            <ul>
              <li>La section « Actualisations » affiche désormais un badge animé « En cours » pour chaque job en cours d'exécution, et se recharge automatiquement à la fin des fetches.</li>
              <li>Le bouton « Relancer les fetches » donne un retour visuel immédiat et recharge la page sans intervention manuelle.</li>
              <li>Les dates et heures s'affichent en français dans toutes les sections (jobs, logs, utilisateurs).</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Corrections</p>
            <ul>
              <li>Accueil : les concerts d'artistes en résidence (plusieurs dates dans la même salle) s'affichent désormais correctement — toutes les dates sont conservées au lieu d'une seule.</li>
              <li>Accueil : certains concerts (artistes en tournée, concerts liés à une salle suivie) n'apparaissaient pas — corrigé.</li>
              <li>Import des préférences : les fichiers exportés depuis la v1 sont de nouveau acceptés.</li>
              <li>Salles : l'ajout de salles arbitraires (sans résultat de recherche) n'est plus possible, pour préserver la cohérence des données.</li>
              <li>Administration : le bouton « Copier » du lien d'invitation fonctionne désormais sur les accès en HTTP local.</li>
              <li>Administration : les liens d'invitation et de réinitialisation de mot de passe utilisent désormais l'adresse réelle du serveur.</li>
              <li>Aide : le formulaire de signalement envoie désormais les messages à la bonne adresse, même sans configuration explicite.</li>
              <li>Mes spectacles : le filtre « Mes salles » affiche désormais correctement les concerts des salles suivies.</li>
              <li>Notifications : les alertes push sont désormais envoyées immédiatement après chaque actualisation, et non plus uniquement à 8 h du matin.</li>
              <li>Découverte : déselectionner un département fonctionne de nouveau correctement.</li>
              <li>Découverte : la sélection de département est conservée lors de la navigation entre les pages de résultats.</li>
              <li>Affichage sur grands écrans (24" et plus) : le contenu est désormais centré — la moitié droite de l'écran n'est plus laissée vide.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 1.4.0 — 23 mai 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Accueil</p>
            <ul>
              <li>Nouveau bouton « Masquer » sur chaque groupe d'artiste pour masquer toute la tournée en un clic.</li>
              <li>Nouveau filtre « Masqués » pour retrouver et réafficher les concerts masqués.</li>
              <li>L'accueil affiche désormais un avertissement quand plus de 50 concerts sont disponibles, avec un lien vers Mes spectacles.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Mes spectacles</p>
            <ul>
              <li>Pagination par 100 concerts avec navigation entre les pages.</li>
              <li>Les mois disponibles dans le filtre sont calculés sur l'ensemble de vos concerts, pas uniquement la page en cours.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Paramètres</p>
            <ul>
              <li>La page est désormais organisée en quatre onglets : Préférences, Notifications, Compte, Données. L'onglet actif est mémorisé dans l'URL.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Artistes</p>
            <ul>
              <li>Un indicateur de chargement apparaît à côté de l'artiste après son ajout, pendant la recherche des concerts.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Aide</p>
            <ul>
              <li>La page d'aide est réorganisée avec des sections claires et des instructions pour installer l'application (iOS, Android, Desktop).</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 1.3.1 — Mai 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Recherche d'artiste</p>
            <ul>
              <li>La recherche d'artiste trouve désormais les résultats quelle que soit l'orthographe des accents (« Adèle » trouve « Adele », « Etienne Daho » trouve « Étienne Daho »).</li>
              <li>Les suggestions s'appuient maintenant sur Deezer, plus fiable depuis ce serveur.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 1.3.0 — Mai 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Découverte</p>
            <ul>
              <li>Nouvelle page Découverte — explorez tous les concerts en base par département, sans vous limiter à vos artistes suivis.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Notifications</p>
            <ul>
              <li>Les concerts dont les billets ne sont plus disponibles n'apparaissent plus comme « Nouveaux » et ne déclenchent plus de notification.</li>
              <li>Le clic sur une notification ouvre directement les spectacles de l'artiste concerné.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Double authentification</p>
            <ul>
              <li>La double authentification TOTP est disponible dans Paramètres → Double authentification. Fonctionne avec Google Authenticator, Aegis, 1Password et toute application compatible TOTP. Elle est optionnelle pour tous les utilisateurs.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 1.2 — Avril 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Notifications push</p>
            <p>Recevez des notifications directement sur votre appareil. Activez-les dans Paramètres → Notifications push.</p>
          </div>
          <div>
            <p class="changelog-categorie">Application installable</p>
            <p>Strapontin peut désormais être installé comme application sur votre appareil (Android, iOS, desktop) via le menu de votre navigateur.</p>
          </div>
          <div>
            <p class="changelog-categorie">Masquer un concert</p>
            <p>Un bouton « Masquer » est disponible sur chaque concert de l'Accueil. Le concert disparaît de la page d'accueil mais reste visible dans Mes spectacles. Pour le réafficher, utilisez le chip « Masqués ».</p>
          </div>
          <div>
            <p class="changelog-categorie">Mot de passe oublié</p>
            <p>Réinitialisez votre mot de passe depuis la page de connexion sans passer par l'admin.</p>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 1.0 — Avril 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div><p>Version initiale.</p></div>
        </div>
        </div>
      </details>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'ChangelogPage' })

import { onMounted } from 'vue'

onMounted(async () => {
  try {
    await fetch('/api/changelog/marquer-vue', { method: 'POST' })
  } catch {}
})
</script>

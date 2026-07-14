<template>
  <div>
    <div class="page-header">
      <h1>Nouveautés</h1>
    </div>

    <div class="max-w-xl changelog">
      <p class="page-subtitle">Historique des améliorations et corrections apportées à Strapontin.</p>

      <!-- Convention (#775) : à chaque nouvelle version, convertir la <section> précédente en
           <details>/<summary> (même gabarit que les versions ci-dessous) pour qu'une seule
           version reste dépliée à la fois. Un test E2E (changelog_versionsAnciennes_repliees)
           échoue si cette conversion est oubliée. -->
      <section>
        <h2 class="changelog-version">v 2.7.0 — 8 juillet 2026</h2>
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Administration</p>
            <ul>
              <li>Dans le référentiel Artistes, un statut « À vérifier » ne s'affiche plus jamais à tort : seul un statut « Invalide » confirmé par une vérification active apparaît désormais, l'ancien indicateur intermédiaire générait trop de faux positifs sur des artistes pourtant bien actifs.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Navigation</p>
            <ul>
              <li>Sur mobile, un appui sur un lien du menu « plus » pouvait parfois refermer le menu au lieu de naviguer — corrigé.</li>
            </ul>
          </div>
        </div>
      </section>

      <details>
        <summary><h2 class="changelog-version">v 2.6.5 — 5 juillet 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Administration</p>
            <ul>
              <li>Régénérer les clés VAPID affiche désormais immédiatement le nombre d'abonnements push invalidés par le changement.</li>
              <li>L'onglet Bannière affiche désormais l'état actuellement publié (message et type) plutôt que de laisser l'admin deviner.</li>
              <li>Le libellé « Jamais connecté » a été remplacé par « Aucune session active », plus fidèle pour un utilisateur dont la session a simplement expiré.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.6.4 — 5 juillet 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Compte</p>
            <ul>
              <li>Un identifiant contenant un point ou un tiret est maintenant accepté à l'enregistrement, comme annoncé par le formulaire.</li>
              <li>Un nom personnalisé par un administrateur n'est plus écrasé silencieusement quand vous changez votre identifiant.</li>
              <li>Une coupure réseau brève ne vous déconnecte plus par erreur — seule une session réellement expirée vous renvoie vers la connexion.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Accueil / Mes spectacles</p>
            <ul>
              <li>Le badge « Nouveau » est désormais cohérent entre l'accueil et Mes spectacles.</li>
              <li>Masquer un artiste masque maintenant bien tous ses concerts, même ceux détectés sous une orthographe légèrement différente selon la source.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Aide</p>
            <ul>
              <li>Le message de confirmation après l'envoi d'un signalement s'affiche désormais avant la fermeture du formulaire.</li>
              <li>Si aucun moyen de contact n'est configuré, un message clair s'affiche à la place d'un lien de contact cassé.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Notifications push</p>
            <ul>
              <li>Cliquer sur une notification fonctionne à nouveau correctement dans tous les cas de configuration.</li>
              <li>Le bouton d'activation des notifications push est désactivé quand la fréquence de notification est sur « Jamais ».</li>
              <li>Le bouton « Envoyer une notification de test » cible désormais l'appareil depuis lequel vous cliquez.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Bannière système</p>
            <ul>
              <li>Une bannière refermée automatiquement réapparaît de nouveau à la page suivante, comme annoncé — elle restait auparavant masquée jusqu'au rechargement complet.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Qualité des données</p>
            <ul>
              <li>Fusionner deux fiches d'un même artiste dans l'administration ne laisse plus de concert en double.</li>
              <li>L'import de vos préférences reconnaît désormais un artiste ou une salle déjà suivi même sous une variante orthographique proche, au lieu de créer un doublon.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Administration</p>
            <ul>
              <li>Un slug Digitick déjà utilisé par une autre salle est désormais refusé explicitement plutôt que d'être repris silencieusement.</li>
              <li>Modifier le nom ou l'email d'un utilisateur en même temps qu'une rétrogradation administrateur n'efface plus ces modifications.</li>
              <li>Réinitialiser le mot de passe d'un utilisateur affiche un lien de secours si l'envoi d'email n'est pas disponible.</li>
              <li>Sélectionner deux éléments à fusionner sur des pages différentes de la liste ne casse plus la boîte de dialogue de fusion.</li>
              <li>Une plainte spam sur un email envoyé est désormais visible et notifiée, comme un rejet d'email.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Nouveautés</p>
            <ul>
              <li>Un indicateur signale désormais qu'une nouvelle version contient des nouveautés pas encore consultées.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.6.3 — 4 juillet 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Accueil</p>
            <ul>
              <li>Le badge « Nouveau » de vos concerts n'est plus effacé à tort par une simple visite de l'accueil quand il concernait une salle suivie — il reste visible jusqu'à consultation réelle dans Mes spectacles.</li>
              <li>Cliquer sur une notification puis changer de filtre sur l'accueil ne re-déclenche plus un défilement automatique intempestif vers le concert d'origine.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Qualité des données</p>
            <ul>
              <li>Deux séances distinctes du même spectacle le même jour (matinée et soirée) ne sont plus fusionnées à tort en une seule.</li>
              <li>Les doublons résiduels entre Ticketmaster/Eventim et Digitick sont désormais nettoyés, pas seulement entre Ticketmaster et Eventim.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Notifications</p>
            <ul>
              <li>Un concert n'est plus marqué à tort comme notifié quand seul l'un de vos deux canaux (email/push) a réellement réussi à vous prévenir.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Sécurité</p>
            <ul>
              <li>Changer son mot de passe déconnecte désormais aussi vos sessions actives sur les autres appareils.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.6.2 — 2 juillet 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Salles</p>
            <ul>
              <li>Les suggestions à la recherche d'une salle reconnaissent désormais aussi les salles classées différemment sur OpenStreetMap (ex. stades, salles polyvalentes) — une salle de concert n'était pas toujours proposée selon sa catégorisation, tandis que des lieux non pertinents (bornes de recharge…) pouvaient s'afficher à tort.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Artistes</p>
            <ul>
              <li>Rechercher un artiste avec un terme partiel affiche désormais correctement « Vous suivez déjà » quand un artiste correspondant est déjà dans vos suivis, plutôt que « Aucun résultat ».</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Administration</p>
            <ul>
              <li>Les cartes Ticketmaster, Eventim et Deezer de l'onglet Sources affichent désormais le nombre de salles/artistes suivis via chaque source, comme c'était déjà le cas pour Digitick et Wikidata.</li>
              <li>Les statuts « Vivant »/« Suspect »/« Mort » des onglets Salles/Artistes sont renommés en « À jour »/« À vérifier »/« Invalide », plus explicites.</li>
              <li>Simplification d'une mention technique superflue sur la carte Wikidata.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Qualité des données</p>
            <ul>
              <li>Les salles dont le nom contient une apostrophe (ex. « L'Empreinte ») peuvent désormais être rapprochées correctement des données OpenStreetMap, qui utilisent parfois une apostrophe typographique différente de celle de nos sources.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Fiabilité</p>
            <ul>
              <li>Sur la PWA installée sur iPhone, les notifications ne sont plus masquées par la barre de navigation du bas.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Interface</p>
            <ul>
              <li>Les anciennes versions de cette page se replient désormais automatiquement — seule la dernière reste visible par défaut.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.6.1 — 2 juillet 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Ajout d'artistes et de salles</p>
            <ul>
              <li>Les suggestions à l'ajout d'un artiste affichent désormais une description (source Wikidata) pour vous aider à choisir le bon artiste parmi plusieurs homonymes.</li>
              <li>Les suggestions à l'ajout d'une salle s'appuient désormais aussi sur OpenStreetMap, en complément de Ticketmaster — plus de salles proposées, badge « osm » sur les résultats correspondants.</li>
              <li>Un nom d'attraction de type « hommage à » ou « tribute to » n'est plus proposé à l'ajout : suivez directement l'artiste original.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Qualité des données</p>
            <ul>
              <li>Les artistes non-musiciens (humoristes notamment) peuvent désormais être enrichis avec leur fiche Wikidata.</li>
              <li>Amélioration du rapprochement automatique des salles dont le nom a changé au fil du temps (renommages commerciaux).</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Fiabilité</p>
            <ul>
              <li>Correction d'un écran noir pouvant apparaître au tout premier lancement de l'application installée (PWA) sans connexion — un message clair s'affiche désormais.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Administration</p>
            <ul>
              <li>Un job de maintenance jamais encore exécuté est désormais visible et peut être déclenché manuellement depuis l'onglet « Jobs ».</li>
              <li>Alerte email automatique en cas de nouvelles erreurs détectées dans les journaux d'administration.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.6.0 — 1er juillet 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Qualité des données</p>
            <ul>
              <li>Les noms des salles et des artistes sont désormais progressivement enrichis avec leur nom canonique (source Wikidata/OpenStreetMap), pour plus de cohérence dans l'affichage.</li>
              <li>Un job nocturne détecte automatiquement les identifiants de salles/artistes devenus invalides chez Ticketmaster et tente de les retrouver.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Administration</p>
            <ul>
              <li>Nouveaux onglets « Salles » et « Artistes » dans l'administration : vue d'ensemble du référentiel, correction manuelle du nom affiché, fusion de doublons.</li>
              <li>Wikidata apparaît désormais dans l'onglet « Sources » de l'administration, avec le nombre de salles et d'artistes enrichis.</li>
              <li>Filtre « Sans Wikidata » et bouton « Réinitialiser » dans les onglets « Salles »/« Artistes » pour repérer et corriger facilement les entrées non enrichies ou mal associées.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.5.7 — 1er juillet 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Qualité des données</p>
            <ul>
              <li>Les concerts issus de Digitick sont désormais correctement rattachés à un artiste suivi, ce qui les fait apparaître dans vos correspondances et notifications.</li>
              <li>Correction d'un bug pouvant empêcher certains concerts d'être enregistrés quand deux sources (Ticketmaster et Eventim) nommaient la même salle différemment lors d'un même cycle de fetch.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.5.6 — 27 juillet 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Sécurité</p>
            <ul>
              <li>Le compteur d'échecs du code d'authentification à deux facteurs est maintenant persisté en base de données. Après 5 tentatives incorrectes, le compte est verrouillé de façon permanente et un administrateur doit réinitialiser la 2FA pour le déverrouiller.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.5.5 — 22 juillet 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Performances</p>
            <ul>
              <li>La page Découverte se charge plus rapidement : les requêtes de filtres disponibles (genres, mois, classifications) et les données de synchronisation sont maintenant calculées en parallèle.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.5.4 — 15 juillet 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Notifications</p>
            <ul>
              <li>Les notifications push peuvent désormais être activées indépendamment des emails — il n'est plus nécessaire d'avoir une fréquence email active pour recevoir des notifications sur l'appareil.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.5.3 — 12 juillet 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Accessibilité</p>
            <ul>
              <li>La page de connexion indique désormais son titre aux lecteurs d'écran (h1 invisible à l'affichage, lisible pour les technologies d'assistance).</li>
              <li>Les messages d'erreur et de succès sur la page de connexion sont automatiquement lus par les lecteurs d'écran dès leur apparition.</li>
              <li>Les liens de navigation (sidebar, barre mobile, menu « Plus ») indiquent aux technologies d'assistance quel est le lien correspondant à la page active (<code>aria-current="page"</code>).</li>
              <li>Le menu « Plus » sur mobile est maintenant déclaré comme un dialogue modal, empêchant les lecteurs d'écran de parcourir le contenu situé derrière.</li>
              <li>Le lien « Aller au contenu principal » (navigation clavier) s'affiche avec un contour visible au focus — le ratio de contraste est passé de 1,09:1 à plus de 12:1.</li>
              <li>L'indicateur de focus sur les champs de saisie est désormais un contour solide (2 px, couleur primaire) au lieu d'un halo semi-transparent peu visible.</li>
              <li>Le contraste des boutons de texte secondaires a été amélioré pour atteindre le seuil WCAG 2.1 AA (4,5:1).</li>
              <li>La hauteur minimale des liens du menu mobile est portée à 44 px pour faciliter l'interaction tactile (WCAG 2.5.5).</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.5.0 — 29 juin 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Aide</p>
            <ul>
              <li>La page Aide indique désormais que la suppression de compte se fait directement depuis Paramètres → Compte, sans passer par l'administrateur.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Paramètres</p>
            <ul>
              <li>Le champ de modification d'identifiant accepte maintenant les points (.) et tirets (-), en cohérence avec la validation serveur.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.4.1 — 29 juin 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Compte</p>
            <ul>
              <li>Les identifiants de connexion peuvent désormais contenir des points (.) et des tirets (-) en plus des lettres, chiffres et underscores.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Concerts</p>
            <ul>
              <li>Des concerts d'un artiste homonyme ou dont le nom contient celui d'un artiste suivi (ex. « Anuv Jain » lors d'un fetch de « Jain ») pouvaient s'afficher à tort — corrigé.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Administration</p>
            <ul>
              <li>La liste des utilisateurs affiche maintenant le nombre d'artistes et de salles suivis par chaque compte.</li>
              <li>Un lien Signal (groupe ou contact) peut être configuré depuis Admin &gt; Configuration. S'il est renseigné, il apparaît sur la page Aide.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Compatibilité</p>
            <ul>
              <li>Sur certains navigateurs (Chrome, Safari), la mise à jour de l'application PWA pouvait laisser la page bloquée sur l'écran « Mise à jour en cours… » — corrigé.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.4.0 — 25 juin 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Concerts</p>
            <ul>
              <li>Digitick est désormais intégré comme source de concerts : les salles configurées avec un identifiant Digitick récupèrent automatiquement leur programme depuis web.digitick.com — couverture des salles non disponibles via Ticketmaster ou Eventim (ex. L'Empreinte, Savigny-le-Temple).</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Salles</p>
            <ul>
              <li>Les salles Digitick configurées apparaissent désormais dans les résultats de recherche avec un badge « Digitick », même avant l'import de leurs premiers concerts. Elles sont également identifiées dans « Mes salles ».</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Administration</p>
            <ul>
              <li>L'onglet Sources permet désormais de créer, modifier et supprimer des salles Digitick directement (nom, slug, ville, département) — sans passer par un import manuel. Le bouton « Tester » vérifie que le slug retourne bien des concerts avant la création.</li>
              <li>Les concerts des salles configurées (Ticketmaster, Eventim, Digitick) sont désormais rechargés périodiquement en arrière-plan — sans attendre une action manuelle ou l'ajout d'une salle par un utilisateur.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Compatibilité</p>
            <ul>
              <li>Lors d'une recherche de salle, une même salle pouvait apparaître en double si elle était liée à plusieurs sources (Digitick et Eventim) — corrigé.</li>
              <li>Sur iPhone, une mise à jour de l'application affichait un écran entièrement blanc pendant la transition. Un écran de chargement (icône + "Mise à jour en cours…") s'affiche désormais pendant cette courte transition.</li>
              <li>Le nom de certaines salles Eventim s'affichait en majuscules (ex. "LEMPREINTE SAVIGNY LE TEMPLE CEDEX"). Les noms sont désormais normalisés en casse lisible.</li>
              <li>Le nom de certaines salles Ticketmaster s'affichait entièrement en majuscules dans les résultats de recherche — corrigé.</li>
              <li>Lors de la recherche d'une salle, les salles déjà connues (avec des concerts en base) apparaissent désormais dans les suggestions locales sans déclencher un appel à Ticketmaster — évite les résultats en double ou la confusion entre plusieurs identifiants pour la même salle.</li>
              <li>Certaines salles Eventim qui changent de nom entre deux mises à jour (ex. « Grand Kursaal » / « Kursaal Besancon », « Auditorium Megacite » / « Megacite Amiens ») créaient des concerts en double — corrigé.</li>
              <li>Les artistes Ticketmaster dont le nom contient des caractères stylistiques comme « $ » ou « &amp; » (ex. « Chri$tian Gates ») ne créent plus de doublons lorsque Ticketmaster corrige le nom lors d'un fetch ultérieur.</li>
              <li>Pour les pages de festival (ex. Solidays) dont le nom de l'artiste varie selon le cycle de mise à jour, le concert n'est désormais créé qu'une seule fois — les notifications répétées sur le même concert sont supprimées.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.3.0 — 23 juin 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Interface</p>
            <ul>
              <li>Un indicateur de chargement (animation) s'affiche désormais pendant la récupération des données sur toutes les pages — plus d'écran vide ou de message "aucun résultat" trompeur pendant le chargement.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Concerts</p>
            <ul>
              <li>Les artistes dont le nom contient des caractères nordiques ou slaves non convertibles (Ø, Ł, Đ, Ŋ) ne recevaient aucun concert — ces lettres sont désormais correctement transcrites lors de la recherche (ex. MØØNSPELL → Moonspell).</li>
              <li>Les concerts découverts via la recherche régionale Eventim n'apparaissaient plus — une incompatibilité entre les catégories Ticketmaster et Eventim empêchait tout résultat. La recherche régionale Eventim est à nouveau fonctionnelle.</li>
              <li>Un concert peut désormais être correctement attribué à un artiste dont le nom contient une apostrophe (ex. Shy'm) même si Eventim référence un homonyme sous un nom différent.</li>
              <li>Le nom d'artiste s'affiche désormais tel que vous le suivez — si vous suivez "MØØNSPELL", les concerts ne s'affichent plus sous le nom retourné par Ticketmaster ("Moonspell"). Cela s'applique à l'accueil, à la liste des spectacles, à la découverte et aux notifications.</li>
              <li>Les artistes "Krash Karma" et "Krashkarma" étaient enregistrés en double — ils sont désormais unifiés sous "Krash Karma". Concerts et préférences des deux noms sont conservés.</li>
              <li>Eventim ne trouvait pas les artistes dont le nom est plus court que celui qu'il utilise (ex. "-M-" non reconnu car Eventim liste "-M- Matthieu Chedid") — ces artistes récupèrent désormais leurs concerts Eventim.</li>
              <li>Des concerts sold-out Eventim s'affichaient à tort avec le badge « Prévente » — corrigé. L'API Eventim ne distinguant pas prévente et complet, aucun badge n'est désormais affiché pour ces concerts.</li>
              <li>Les spectacles à séances multiples (matinée et soirée le même jour dans la même salle) sont maintenant affichés comme deux événements distincts plutôt que fusionnés.</li>
              <li>Des concerts de l'artiste "Shy" (Ticketmaster) pouvaient apparaître à tort pour l'artiste Shy'm suivi — la correspondance exacte est désormais vérifiée parmi plusieurs résultats.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Administration</p>
            <ul>
              <li>Un nouvel onglet « Sources » dans l'administration affiche l'état de chaque source de données : quota API Ticketmaster avec jauge de consommation, dernier fetch Eventim, statut Deezer et nombre de salles configurées pour Digitick.</li>
              <li>La liste des salles sans source inclut désormais les salles sans identifiant Eventim, pas uniquement celles sans identifiant Ticketmaster — le bouton est renommé « Sans source » en conséquence. Les salles Eventim disposent désormais d'un identifiant interne : la liste « Sans source » est vide par défaut et réservée à la future intégration Digitick.</li>
              <li>L'onglet Jobs affiche maintenant deux sections distinctes : « Fetches » (mises à jour concerts, avec bouton de relance) et « Maintenance » (purge concerts/salles/artistes, recalcul matchs) — la purge des salles et artistes orphelins est maintenant visible dans cette section.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Salles</p>
            <ul>
              <li>Sept salles existaient en double (une version Ticketmaster, une version Eventim avec un nom légèrement différent) — les concerts dupliqués dans la découverte disparaissent, et les préférences sont conservées.</li>
              <li>Des doublons de salles créés lors de récupérations précédentes (notamment pour les grandes salles parisiennes) ont été fusionnés — les concerts et préférences associés sont conservés.</li>
              <li>Le nom de plusieurs salles a été corrigé pour inclure les accents manquants (ex. Elysée Montmartre, Opéra Bastille, Théâtre Mogador).</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Compatibilité</p>
            <ul>
              <li>Sur Safari (macOS et iOS), naviguer vers le site depuis un lien (email, autre application) ne provoquait plus de redirection vers la page de connexion — la session est désormais correctement conservée.</li>
              <li>Après une mise à jour, les feuilles de style pouvaient rester en version ancienne pendant plusieurs heures. Le rechargement est maintenant forcé dès la mise à jour disponible.</li>
              <li>Sur les appareils ayant installé l'application (PWA), l'onglet « Sources » de l'administration pouvait ne pas apparaître suite à un cache non mis à jour — résolu.</li>
              <li>Lorsque l'application était relancée sans réseau (PWA sur iOS), une page blanche s'affichait au lieu de la page hors ligne — corrigé.</li>
              <li>Après un retour de connexion, le lien « Administration » pouvait rester masqué jusqu'au prochain rechargement complet — la session est désormais rechargée automatiquement à la reconnexion.</li>
              <li>L'architecture du cache PWA a été entièrement revue : les feuilles de style sont désormais empaquetées avec les bundles JS (fingerprinting Vite) et précachées par Workbox. Les pages, images et icônes bénéficient d'une stratégie cohérente entre le cache HTTP et le service worker — les onglets qui disparaissaient après une perte de réseau sont définitivement corrigés.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.2.5 — 18 juin 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Interface</p>
            <ul>
              <li>Les fenêtres de confirmation (suppression, réinitialisation…) s'affichent désormais centrées à l'écran.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Compte et sécurité</p>
            <ul>
              <li>Il n'est plus possible de supprimer son compte lorsqu'on est le seul administrateur de l'instance — un message d'erreur l'indique clairement.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Concerts</p>
            <ul>
              <li>Un concert dont le nom de l'artiste varie légèrement entre deux récupérations (ex. ajout d'un numéro ou d'un article) n'apparaît plus en double.</li>
              <li>La ville de certaines salles de la petite couronne (ex. La Seine Musicale) n'est plus affichée à tort comme « Paris ».</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Salles</p>
            <ul>
              <li>La recherche de salle fonctionne désormais avec les apostrophes, accents et tirets — taper « l'empreinte » ou « zénith » retrouve les salles correspondantes.</li>
              <li>Si une salle n'est pas référencée, un bouton « Signaler cette salle » apparaît sous le message d'absence de résultat. Un formulaire permet d'envoyer le nom, la ville, le site et un commentaire à l'administrateur — une confirmation est envoyée par email.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Administration</p>
            <ul>
              <li>Une confirmation est désormais demandée avant de réinitialiser le mot de passe ou la double authentification d'un utilisateur.</li>
              <li>L'utilisateur dont le compte est supprimé reçoit maintenant un email de notification.</li>
              <li>Le nom d'affichage d'un utilisateur peut désormais être modifié depuis le panneau d'administration.</li>
              <li>Un email est envoyé automatiquement à un utilisateur lorsqu'il est promu administrateur.</li>
              <li>La rétrogradation d'un administrateur est désormais différée de 7 jours, avec envoi d'un email de préavis. La révocation peut être annulée pendant ce délai.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.2.4 — 12 juin 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Interface</p>
            <ul>
              <li>Un badge « Prévente » signale les concerts dont la billetterie n'est pas encore ouverte, sur l'accueil et dans Mes spectacles.</li>
              <li>Les concerts dont la vente n'a pas encore commencé ne sont plus affichés « Complet » à tort.</li>
            </ul>
          </div>
          <div>
            <p class="changelog-categorie">Compte et sécurité</p>
            <ul>
              <li>Un administrateur peut réinitialiser la double authentification (2FA) d'un utilisateur qui a perdu l'accès à son application d'authentification.</li>
              <li>La connexion avec 2FA pouvait renvoyer à tort vers l'écran de connexion : c'est corrigé.</li>
              <li>Le message affiché après trop de tentatives de connexion est désormais plus clair.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

      <details>
        <summary><h2 class="changelog-version">v 2.2.3 — 11 juin 2026</h2></summary>
        <div class="details-corps">
        <div class="changelog-entrees">
          <div>
            <p class="changelog-categorie">Interface</p>
            <ul>
              <li>Le bandeau d'information système ne réapparaît plus après avoir disparu automatiquement.</li>
              <li>Les animations de transition entre les pages sont désormais identiques depuis le menu mobile.</li>
            </ul>
          </div>
        </div>
        </div>
      </details>

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

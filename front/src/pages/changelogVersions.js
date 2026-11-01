// Contenu du changelog utilisateur — historique des versions affichées sur /changelog.
// La première entrée du tableau est affichée dépliée (<section>), toutes les suivantes
// repliées (<details>) — voir ChangelogPage.vue. Ajouter une nouvelle version en tête de
// tableau : aucune conversion manuelle n'est nécessaire.
export const versionsChangelog = [
  {
    entete: "v 3.1.2 — 1 novembre 2026",
    categories: [
      {
        categorie: "Concerts et salles",
        items: [
          "Les billets de visite d'un musée, les entrées de parc de loisirs et les produits de parking ne sont plus affichés comme des concerts.",
          "Un spectacle que la billetterie republie à une autre date ou à une autre heure reste une seule fiche : l'ancienne n'est plus affichée à côté de la nouvelle.",
          "Une fiche absente n'est plus fusionnée dans une autre date de la même série, et une fiche gardée par un administrateur n'est jamais absorbée.",
          "Deux salles voisines dont les noms ne diffèrent que par un article collé (« Essaion » / « Théâtre de l'Essaion ») sont reconnues comme un seul lieu.",
          "La ville d'une salle suit sa commune réelle quand la billetterie annonce la grande ville voisine (Le K est à Tinqueux, pas à Reims).",
          "Une séance publiée sans heure par une billetterie et avec une heure par l'autre n'apparaît plus en double (festival Aluna).",
          "Deux productions de même titre dans des salles éloignées d'une même ville, et les spectacles simultanés d'un théâtre à plusieurs salles, ne sont plus signalés comme des doublons.",
          "Un artiste dont le nom est publié plus long par Eventim (« Drag Race France Live ») n'est plus signalé à tort comme appartenant à un autre artiste.",
          "Dans les concerts à confirmer, l'administrateur voit si le même produit est vivant sur une autre fiche, si une autre billetterie l'annonce encore ou s'il est introuvable.",
        ],
      },
      {
        categorie: "Application",
        items: [
          "Hors ligne juste après une connexion, sans aucune donnée gardée, l'application affiche tout de suite l'écran « Vous êtes hors ligne » au lieu de messages d'erreur, puis se recharge d'elle-même au retour de la connexion.",
        ],
      },
    ],
  },
  {
    entete: "v 3.1.1 — 27 octobre 2026",
    categories: [
      {
        categorie: "Concerts et salles",
        items: [
          "Une date annulée chez Eventim n'est plus affichée comme disponible, et une annulation vue par une seule billetterie est signalée à l'administrateur.",
          "Un concert qui change d'heure chez Ticketmaster ne revient plus en double le lendemain d'une fusion, et un spectacle annulé n'absorbe plus le spectacle qui le remplace le même soir.",
          "Les hommages (tribute) restent détachés de l'artiste imité, même quand la billetterie garde la même adresse.",
          "Une séance publiée dans deux salles (changement de salle, salle et sous-salle) est signalée, et la fiche d'un concert reprend le département de sa salle.",
          "Les concerts Eventim encore en vente ne sont plus signalés « à confirmer » quand ils sortent des catégories de la recherche par région : ils sont revus par lieu.",
          "Une salle liée à un lieu détruit sur Wikidata (l'Alhambra) ou à une gare (Le Hasard Ludique) n'en garde plus la position, une salle qui a un élément Wikidata reçoit sa position, et la ville d'une salle suit sa commune réelle (Le Transbordeur : Villeurbanne).",
        ],
      },
      {
        categorie: "Notifications et compte",
        items: [
          "L'appareil est nommé de la même façon partout (« PWA sur iPhone », « Chrome sur Android »...) : email de nouvelle connexion, liste des sessions et appareils de notification.",
          "Une mise à jour de votre navigateur ou de votre système ne déclenche plus l'email « Nouvelle connexion détectée » ; un nouveau navigateur, un nouveau système ou l'application installée en déclenchent toujours un.",
          "Quand le dernier appareil de notification push est retiré, vous en êtes prévenu par email quel que soit votre autre réglage, avec la cause et ce qu'il faut refaire ; vos choix push sont alors décochés.",
          "Un appareil n'est plus retiré pour un échec passager du service de notification : il faut cinq échecs répartis sur cinq jours, et jamais lorsque tout le service échoue.",
        ],
      },
    ],
  },
  {
    entete: "v 3.1.0 — 24 octobre 2026",
    categories: [
      {
        categorie: "Concerts et salles",
        items: [
          "Un concert dont le titre ou l'horaire change chez Eventim, ou dont la page change de titre sur le site d'une salle, reste une seule fiche au lieu d'apparaître en double.",
          "Quand Ticketmaster reporte une date, vous êtes prévenu du nouveau jour (le digest l'indique : « Date reportée »), et la date d'origine, encore publiée ailleurs, n'est plus affichée comme un autre concert.",
          "Les spectacles de théâtre et d'humour de vos départements, jusque-là revus trop rarement, sont maintenant mis à jour chaque jour, et ceux qui ne sont plus en vente disparaissent.",
          "Le concert d'un hommage n'est plus rattaché à l'artiste imité, même quand il vient du programme d'une salle.",
          "Une salle ne change plus de nom en alternance quand une billetterie en publie deux libellés, et « La Merise - Trappes » n'est plus prise pour une salle distincte de « La Merise ».",
          "La position d'une salle suit celle de Wikidata dès quelques centaines de mètres d'écart, et l'Opéra royal de Versailles retrouve sa fiche Wikidata.",
          "Les salles de Guadeloupe lues sur Allmol Tickets gardent leur commune, même composée avec un trait d'union (Baie-Mahault).",
          "Les recherches contenant un accent ou une virgule fonctionnent de nouveau pour retrouver les salles et les artistes à l'ajout.",
          "« Rose » n'est plus remplacée par « ROSÉ », et un humoriste ne reçoit plus la fiche Deezer d'un musicien homonyme.",
        ],
      },
      {
        categorie: "Notifications",
        items: [
          "Paramètres > Notifications : les contenus de chaque canal sont décalés sous leur case, la Fréquence a son propre cadre, les appareils sont séparés de l'action « Activer sur cet appareil », et un message signale les modifications non enregistrées.",
          "Quitter Paramètres > Notifications avec des modifications non enregistrées demande si vous enregistrez, abandonnez ou restez sur la page.",
        ],
      },
      {
        categorie: "Compte",
        items: [
          "L'email d'invitation dit que vous êtes invité à rejoindre Strapontin, puis vous demande de créer votre compte, au lieu d'annoncer un compte déjà créé.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "L'onglet Sources liste chaque site de salle (Le Triton, EMB Sannois, Usine à Chapeaux, Allmol Tickets) avec ses salles, son dernier passage et son état, et le dernier passage de chaque source lui est propre.",
          "« Annuler (garder) » dans les concerts à confirmer garde le concert pour de bon : il n'est plus proposé à la suppression.",
          "Un signalement traité à la main (deux fiches au même créneau, candidat Wikidata écarté) ne revient plus la nuit suivante tant que rien ne change ; un candidat Wikidata situé à des centaines de kilomètres n'est plus signalé.",
          "Nouveaux signalements : un même produit Eventim sur deux fiches, des fiches à venir restées sans mise à jour, une salle restée sans commune.",
          "Les erreurs JavaScript remontées par les navigateurs portent leur pile d'appel pour en retrouver l'origine.",
        ],
      },
    ],
  },
  {
    entete: "v 3.0.6 — 18 octobre 2026",
    categories: [
      {
        categorie: "Concerts et salles",
        items: [
          "Les concerts de trois salles absentes de Ticketmaster et d'Eventim (Le Triton, l'EMB de Sannois et L'Usine à Chapeaux) et ceux de la billetterie Allmol Tickets (Antilles et Guyane) sont maintenant relevés une fois par jour sur leur propre site, dans le respect de leurs consignes d'accès.",
          "Quand un département ou une salle suivie est mal couvert par nos billetteries, l'application le dit au lieu d'afficher « aucun concert » : c'est fréquent outre-mer, où les concerts passent surtout par des billetteries locales.",
          "Un concert annoncé à une heure légèrement différente par deux billetteries, ou republié après un changement de salle, n'apparaît plus en double.",
          "Le concert d'un hommage ou d'un tribute n'est plus rattaché à l'artiste qu'il imite, et les noms de ville des salles sont mieux renseignés.",
        ],
      },
      {
        categorie: "Notifications",
        items: [
          "Aucune notification push entre 22 h et 7 h (heure de Paris) : celles qui tombent la nuit sont envoyées à 7 h. Les emails ne changent pas.",
          "Paramètres > Notifications se règle canal par canal : fréquence, email, push avec la liste de vos appareils, leur dernier refus éventuel et un bouton pour activer le push sur l'appareil en cours.",
          "Une notification vous prévient quand une nouvelle version de l'application est disponible, sauf si vous avez choisi « Jamais ».",
          "Le chiffrement des notifications push suit le format attendu par les navigateurs, que certains refusaient.",
          "Les digests du jour partent à 12 h.",
        ],
      },
      {
        categorie: "Hors ligne et connexion lente",
        items: [
          "Sans connexion, ou avec une connexion trop lente, l'application affiche vos derniers concerts connus avec leur ancienneté, au lieu d'une page vide. Ils sont remplacés d'eux-mêmes dès que la connexion revient.",
          "Une connexion lente est signalée par un message au bout de quelques secondes.",
          "Ces données sont effacées de l'appareil à la déconnexion.",
        ],
      },
      {
        categorie: "Compte et sécurité",
        items: [
          "Les essais répétés sont limités : connexion à un même compte (10 échecs en 15 minutes), mot de passe oublié (3 demandes par heure et par adresse), changement d'adresse email, changement de mot de passe et désactivation de la double authentification (5 essais), recherches d'artistes et de salles (15 par minute).",
          "Le formulaire de l'identifiant, celui des départements et celui du mot de passe suivent la même disposition, et le champ du code de désactivation de la double authentification explique à quoi il sert.",
        ],
      },
      {
        categorie: "Interface",
        items: [
          "Les boutons de Paramètres ont la largeur de leur libellé, et l'import de préférences propose « Fusionner » ou « Remplacer » avec l'effet de chacun affiché à côté.",
          "« Masquer » et « Réafficher » sont plus petits et soulignés pour se reconnaître comme des boutons sans survol ; le lien de l'Aide qui rouvre le tour guidé le dit mieux.",
          "En thème sombre, le bouton principal et l'option active sont moins éclatants tout en restant lisibles.",
          "Mes spectacles explique ce qu'il contient, et Mes artistes comme Mes salles disent qu'il faut cliquer sur « Rechercher » après avoir saisi au moins 2 caractères.",
          "La recherche d'artistes retrouve « Krash Karma » sous « Krashkarma » et inversement, et chaque résultat indique sa provenance et, quand elle existe, sa popularité.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "À traiter : les adresses d'une anomalie s'ouvrent d'un clic, une anomalie de doublon ou de Wikidata partagé nomme l'autre salle et propose de les comparer puis de les fusionner, et une position de salle éloignée de son élément Wikidata peut être alignée sur lui.",
          "Le Référentiel des salles gère le lieu Eventim de chaque salle (voir, modifier, tester, lancer un fetch), filtre les salles sans source et explique chaque statut « À vérifier » ou « Invalide » (source, nombre de fetchs vides, date).",
          "L'onglet Sources liste les départements sans aucun concert à venir, que l'on peut déclarer légitimes.",
          "Les essais de connexion trop nombreux sur un compte et les dépassements de limite sont consignés dans les logs.",
        ],
      },
    ],
  },
  {
    entete: "v 3.0.5 — 15 octobre 2026",
    categories: [
      {
        categorie: "Notifications",
        items: [
          "Choisir la fréquence « Jamais » coupe aussi les notifications push : les cases push sont décochées et ne peuvent plus rester cochées sans effet, y compris après l'import de vos préférences.",
        ],
      },
      {
        categorie: "Concerts",
        items: [
          "Un concert d'une salle n'est plus absorbé par celui d'une autre salle donné le même soir sous un intitulé identique : seule une séance réellement déplacée est regroupée.",
          "Une salle n'est plus rattachée à une rue ou une impasse trouvée sur Wikidata, et une position de salle très éloignée de sa fiche Wikidata est désormais signalée.",
        ],
      },
      {
        categorie: "Interface",
        items: [
          "Tous les écrans partagent une même charte graphique : couleurs, tailles de texte, espacements, cartes et liens de navigation viennent de réglages communs, clair comme sombre. Le rendu reste le même, à quelques pixels près dans l'administration.",
          "Les pastilles de source de l'onglet Sources et certains textes de l'administration sont plus lisibles, en particulier en thème sombre.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "Logs : les filtres de niveau retrouvent leur couleur (bleu, orange, rouge).",
          "L'alerte « taux de réussite chronique trop bas » de l'enrichissement des salles ne part plus quand les sources répondent : elle ne signale qu'une source en panne ou qui ne renvoie plus aucun résultat.",
        ],
      },
    ],
  },
  {
    entete: "v 3.0.4 — 12 octobre 2026",
    categories: [
      {
        categorie: "Concerts",
        items: [
          "Une soirée à plusieurs artistes (festival, plateau partagé) apparaît pour chacun d'eux : si vous suivez un artiste qui n'est pas en tête d'affiche, vous êtes notifié et la soirée s'affiche sous son nom, avec la tête d'affiche.",
          "Les départements d'outre-mer sont interrogés chez Ticketmaster. Un département choisi où aucune billetterie ne propose de concert est signalé dans Paramètres et sur la page Découverte.",
          "Un concert dont la billetterie omet la ville reprend celle de sa salle.",
        ],
      },
      {
        categorie: "Accueil et Mes spectacles",
        items: [
          "Le filtre « Indisponibles » montre toujours ce qui est réellement affiché, quel que soit votre réglage par défaut, et prévient quand il n'y a aucun concert indisponible à ajouter.",
          "Masquer un concert ou tous les concerts d'un artiste demande une confirmation et rappelle que le filtre « Masqués » permet de les réafficher.",
          "Le bouton « Masquer » d'un artiste reste aligné à droite, quel que soit son nom.",
          "Les boutons suivent partout la même présentation selon leur rôle (action principale, annulation, suppression), et la bannière d'information reprend ses couleurs d'avertissement et d'erreur.",
        ],
      },
      {
        categorie: "Notifications",
        items: [
          "Un appareil dont les notifications push échouent à répétition est retiré après cinq échecs consécutifs, quelle qu'en soit la raison.",
          "L'email qui vous prévient que vous ne recevez plus les nouveaux concerts explique pourquoi votre appareil a été retiré et comment réactiver les notifications.",
          "Si vous avez choisi les notifications push sans qu'aucun appareil n'ait été enregistré, un bandeau vous le signale, puis un email dès qu'un concert vous a échappé.",
          "L'alerte de connexion depuis un nouvel appareil n'est plus envoyée plusieurs fois pour une même connexion.",
        ],
      },
      {
        categorie: "Salles",
        items: [
          "Deux salles d'une même ville ne sont plus réunies au seul motif du nom de la ville, ni deux théâtres au seul mot « Comédie ».",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "La notification de nouvelle version passe dans l'onglet Communication ; la communication d'incident de sécurité y est placée en dernier, nettement à part.",
          "À traiter : les boutons gardent leur taille en mobile, un concert signalé est nommé (artiste, date, salle) et peut être supprimé sur place.",
          "Le signalement « Date de festival attribuée à tort » ne vise plus les concerts légitimes (billet couplé, date d'un autre artiste du festival).",
          "Le signalement « Notifications non envoyées » ne remonte plus que les envois en échec vers un compte joignable, avec une date propre à ce compte.",
          "La cause d'une révocation d'appareil push est tracée dans les logs.",
        ],
      },
    ],
  },
  {
    entete: "v 3.0.3 — 7 octobre 2026",
    categories: [
      {
        categorie: "Notifications",
        items: [
          "Nouveau : un récapitulatif hebdomadaire, chaque dimanche à 10h. C'est le réglage d'un nouveau compte, qui reçoit par email les nouveaux concerts de ses artistes sans rien configurer.",
          "L'accueil propose directement d'activer l'email et les notifications push, au lieu de renvoyer vers Paramètres.",
          "La notification de test et les notifications push des communications d'incident sont de nouveau envoyées.",
          "Un échec d'envoi de la notification de test explique pourquoi et quoi faire.",
          "Paramètres indique quand cet appareil n'est plus enregistré pour les notifications push et propose de le réactiver.",
          "Si plus aucune notification ne peut vous parvenir (dernier appareil qui ne les accepte plus, email désactivé), vous en êtes prévenu par email et par un bandeau dans l'application.",
          "La checklist « Premiers pas » ne coche l'étape des notifications que si une notification peut réellement vous parvenir.",
        ],
      },
      {
        categorie: "Interface",
        items: [
          "Sur mobile et tablette, le bouton « Filtres » de Mes spectacles et de Découverte replie et déplie bien les filtres.",
          "La recherche par nom se lance avec son propre bouton « Rechercher » ; les autres filtres s'appliquent dès leur sélection.",
          "Les messages affichés après une action sont rédigés en français courant, sans termes techniques.",
        ],
      },
      {
        categorie: "Compte",
        items: [
          "À la création de votre compte, l'identifiant et le mot de passe ont chacun leur consigne, visible pendant la saisie.",
          "La même règle d'identifiant (3 à 30 caractères) s'applique partout, avec le même message.",
          "L'email prévenant de la suppression d'un compte inactif indique bien le nombre de jours restants.",
        ],
      },
      {
        categorie: "Concerts",
        items: [
          "Une soirée de festival où votre artiste ne joue pas ne lui est plus attribuée, même quand son titre ne nomme que le festival.",
        ],
      },
      {
        categorie: "Salles",
        items: [
          "Un même lieu publié sous deux noms par deux billetteries ne forme plus qu'une salle.",
          "Une salle interne d'un lieu (comme la Salle Jean Tardieu du Théâtre du Rond-Point) est rattachée à son lieu.",
          "La position d'une salle sur la carte suit celle de sa fiche Wikidata.",
          "Une salle n'est plus associée à la fiche Wikidata d'un monument d'une autre ville.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "La répartition d'une salle mélangeant plusieurs lieux montre tous les lieux des billetteries, Ticketmaster compris, et ceux qui resteraient en place ; les deux salles ne sont plus réunies ensuite.",
          "Une fusion de salles garde tous les lieux des deux salles, et chaque lieu reporté ou supprimé est tracé dans les logs.",
          "Le signalement « Notifications non envoyées » nomme le compte et en donne la cause.",
          "Nouveaux signalements : concert en double sur deux salles déclarées distinctes, date de festival attribuée à tort.",
        ],
      },
    ],
  },
  {
    entete: "v 3.0.2 — 3 octobre 2026",
    categories: [
      {
        categorie: "Sécurité",
        items: [
          "Les notifications push ne peuvent plus être envoyées qu'aux services de push des navigateurs.",
          "Quand un nouveau mot de passe est exigé après un incident, la connexion est refusée tant qu'il n'a pas été défini par « Mot de passe oublié », et les autres sessions sont fermées.",
          "Protection renforcée contre les actions déclenchées à votre insu depuis un autre site.",
        ],
      },
      {
        categorie: "Compte",
        items: [
          "Un compte utilisé régulièrement n'est plus considéré comme inactif : l'utilisation de l'application compte, pas seulement la connexion.",
          "Une invitation relancée reste valable jusqu'à la date indiquée dans l'email de relance : elle n'est plus annulée dès le lendemain.",
          "La page Aide précise les données conservées et la suppression d'un compte inutilisé pendant 6 mois.",
        ],
      },
      {
        categorie: "Concerts",
        items: [
          "Un concert reporté par Ticketmaster n'apparaît plus en double : sa date et son heure sont mises à jour.",
          "Un concert republié par une billetterie remplace l'ancienne fiche au lieu de s'y ajouter.",
          "Les artistes dont le nom contient &, # ou + sont de nouveau trouvés.",
          "Un artiste connu sous un nom de scène (comme -M-) est reconnu chez Eventim.",
          "Les concerts déjà passés ne sont plus ajoutés.",
          "Un même concert publié par deux billetteries sous deux affiches (« Meryl » et « Meryl + 1ère partie », co-plateaux) n'apparaît plus qu'une fois.",
          "Un festival ne vous signale plus les dates des autres artistes de l'affiche : seules celles où votre artiste joue sont retenues.",
          "Un concert déplacé dans une autre salle rejoint sa nouvelle fiche au lieu de rester en double.",
          "Un spectacle annoncé sous deux titres différents selon la billetterie (« Lady Macbeth de Mzensk » / « of Mtsensk ») n'apparaît plus qu'une fois.",
          "Une billetterie momentanément indisponible ne fait plus passer ses concerts pour disparus, et deux séances d'un même spectacle le même jour ne sont plus confondues.",
          "Les dates Eventim continuent d'arriver quand Ticketmaster limite ses réponses pour la journée.",
          "Les spectacles toujours programmés ne sont plus proposés à tort à la suppression.",
          "Le nom d'un spectacle ne change plus d'une mise à jour à l'autre quand deux billetteries l'écrivent différemment.",
          "Ajouter un artiste ne recherche plus que ses concerts : les concerts de tous les artistes suivis n'étaient plus mis à jour à temps, faute de quota Ticketmaster.",
        ],
      },
      {
        categorie: "Salles",
        items: [
          "Les salles sont reconnues grâce à l'adresse publiée par la billetterie : deux salles homonymes de villes différentes ne sont plus confondues.",
          "Le nom d'une salle ne change plus d'une mise à jour à l'autre.",
          "Deux salles voisines aux noms différents (comme l'Élysée Montmartre et le Théâtre Montmartre-Galabru, ou le Théâtre Montparnasse et le Théâtre de Poche) ne sont plus réunies.",
          "La ville d'une salle est sa commune : « Paris » au lieu d'un arrondissement ou d'un quartier comme « Saint-Thomas-d'Aquin ».",
          "Une salle n'est plus placée sur la carte chez un homonyme d'une autre région, et une salle étrangère n'est plus rangée dans un département français.",
        ],
      },
      {
        categorie: "Notifications",
        items: [
          "Paramètres signale des notifications push activées sans aucun appareil abonné, qui ne vous parvenaient pas.",
          "La fréquence « Jamais » coupe aussi les notifications push, comme l'indique la page Paramètres.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "Nouvel onglet « À traiter » : les anomalies sont décrites en clair, avec la salle ou l'artiste concerné, et se traitent sur place, comme les concerts à confirmer.",
          "Nouvel onglet « Référentiel » réunissant salles et artistes, avec la répartition des concerts d'une salle qui mélange plusieurs lieux.",
          "Tableau de bord : les tâches automatiques sont présentées en cartes par thème, avec leur rôle.",
          "Des contrôles automatiques vérifient chaque nuit la cohérence des données ; les signalements sans anomalie réelle (artiste suivi sans date annoncée, salles voisines, communes d'une même agglomération) ont disparu.",
          "L'onglet Bannière devient « Communication », avec un modèle de message d'incident.",
          "Les tableaux de l'administration sont lisibles sur mobile.",
        ],
      },
      {
        categorie: "Accessibilité",
        items: [
          "Les fenêtres de confirmation et le champ des départements sont correctement annoncés par les lecteurs d'écran.",
        ],
      },
    ],
  },
  {
    entete: "v 3.0.1 — 29 septembre 2026",
    categories: [
      {
        categorie: "Sécurité",
        items: [
          "En cas d'incident de sécurité, un message d'information s'affiche désormais sur toutes les pages, y compris la page de connexion, et peut être accompagné d'un email ou d'une notification push.",
          "Si un nouveau mot de passe est exigé suite à un incident, l'application vous redirige vers Paramètres → Compte jusqu'à ce qu'il soit changé.",
        ],
      },
      {
        categorie: "Concerts",
        items: [
          "Les concerts à Paris et dans les départements les plus actifs étaient partiellement récupérés dans la page Découverte : ils le sont désormais en totalité.",
          "Les concerts Eventim d'un département ne se limitaient plus qu'aux tout prochains jours : ils couvrent de nouveau les mois à venir.",
          "Un artiste ayant beaucoup de dates chez Eventim (plus de 50) pouvait n'en afficher qu'une partie.",
          "Un concert pouvait disparaître à tort après une coupure réseau passagère lors de sa récupération.",
          "Un concert pouvait être rattaché à une salle homonyme d'une autre ville.",
        ],
      },
      {
        categorie: "Salles",
        items: [
          "La source Digitick, qui ne couvrait plus aucune salle, a été retirée : le badge Digitick n'apparaît plus sur les salles.",
          "Les concerts de L'Empreinte (Savigny-le-Temple), qui n'étaient plus récupérés depuis le changement de sa billetterie, le sont de nouveau via Eventim.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "Nouvelle communication d'incident de sécurité dans l'onglet Bannière.",
          "Onglet Sources : une salle peut être associée à son lieu Eventim, avec un test du nombre de concerts trouvés.",
          "Onglet Signalements : une contamination ou un doublon de salles jugé faux positif peut être écarté définitivement.",
        ],
      },
    ],
  },
  {
    entete: "v 3.0.0 — 23 septembre 2026",
    categories: [
      {
        categorie: "Sécurité",
        items: [
          "Un compte peut désormais être verrouillé par un administrateur en cas de besoin (ex. compromission suspectée) — l'utilisateur concerné ne peut plus se connecter tant que le verrou n'est pas levé.",
          "Une alerte email est envoyée en cas de connexion depuis un nouvel appareil, ou de tentatives de connexion répétées échouées sur un compte.",
        ],
      },
      {
        categorie: "Compte",
        items: [
          "Un compte inactif depuis longtemps reçoit désormais un email de relance avant toute suppression pour inactivité, avec un délai de grâce.",
        ],
      },
      {
        categorie: "Paramètres",
        items: [
          "Une nouvelle option dans Paramètres → Notifications permet d'être notifié par push lors de la sortie d'une nouvelle version de l'application.",
        ],
      },
      {
        categorie: "Concerts",
        items: [
          "Plusieurs cas rares de concerts affichés en double (rapprochement automatique entre sources) ont été corrigés.",
          "Un concert pouvait, dans de rares cas, ne jamais afficher sa salle — il est désormais rattaché automatiquement dès que possible.",
        ],
      },
    ],
  },
  {
    entete: "v 2.9.1 — 18 septembre 2026",
    categories: [
      {
        categorie: "Concerts",
        items: [
          "Un nom d'artiste contenant une apostrophe (ex. Youssou N'Dour) pouvait ne pas être reconnu comme le même artiste selon la source d'origine des concerts.",
          "Un complexe multi-salles suivi (ex. La Seine Musicale) pouvait afficher certains concerts en double lorsque les sources ne s'accordaient pas sur la sous-salle exacte.",
          "La page Spectacles ou Découverte pouvait échouer à se charger lors d'une première visite sans aucun filtre actif.",
        ],
      },
      {
        categorie: "Salles",
        items: [
          "La ville affichée pour un concert pouvait redevenir incorrecte lors d'un nouveau cycle de récupération, après avoir déjà été corrigée.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "Le signalement automatique de salles en doublon dans le référentiel ne signale plus à tort deux salles légitimement distinctes reliées par une hiérarchie complexe/sous-salle, ni une salle dont l'identifiant technique n'est que son propre nom complété d'une ville.",
        ],
      },
    ],
  },
  {
    entete: "v 2.9.0 — 10 septembre 2026",
    categories: [
      {
        categorie: "Administration",
        items: [
          "Un nouvel onglet « Signalements » recense les incohérences détectées automatiquement dans le référentiel (salles, artistes) et permet de les résoudre manuellement.",
          "Un événement webhook Resend (email) en échec de livraison peut désormais être rejoué manuellement depuis la configuration.",
        ],
      },
      {
        categorie: "Salles",
        items: [
          "Deux salles distinctes dont le nom ne diffère que par un mot très court (ex. un simple article) ne sont plus mélangées à tort lors du rapprochement automatique.",
        ],
      },
    ],
  },
  {
    entete: "v 2.8.8 — 9 septembre 2026",
    categories: [
      {
        categorie: "Apparence",
        items: [
          "Le thème « Automatique » suit à nouveau la préférence sombre/claire du système — il restait bloqué en clair depuis la dernière mise à jour.",
        ],
      },
    ],
  },
  {
    entete: "v 2.8.4 — 31 août 2026",
    categories: [
      {
        categorie: "Notifications",
        items: [
          "Dans un digest regroupant plusieurs artistes, une séparation visuelle plus nette apparaît désormais entre les concerts d'artistes différents (email et notification push).",
          "La mention « Prévente » apparaît désormais dans la notification push même quand plusieurs concerts sont regroupés dans le même digest — jusqu'ici visible uniquement dans l'email.",
          "Une offre VIP/pack liée à un concert n'est plus notifiée à tort comme une ouverture de vente standard.",
        ],
      },
      {
        categorie: "Compte",
        items: [
          "Un problème de connexion réseau lors d'une demande de réinitialisation de mot de passe affiche désormais un message d'erreur clair, au lieu du message de confirmation habituel.",
        ],
      },
    ],
  },
  {
    entete: "v 2.8.2 — 26 août 2026",
    categories: [
      {
        categorie: "Concerts",
        items: [
          "Un concert marqué « À vérifier » n'est plus affiché indéfiniment si aucune source ne le reconfirme jamais — il est retiré après plusieurs jours de silence continu.",
        ],
      },
    ],
  },
  {
    entete: "v 2.8.1 — 21 août 2026",
    categories: [
      {
        categorie: "Administration",
        items: [
          "Un écart purement cosmétique entre le nom suivi et le nom retourné par Ticketmaster (espace, article) ne fait plus apparaître à tort un artiste comme « Invalide » dans le référentiel Artistes.",
          "Un nom de scène déjà explicitement accepté (bouton « Ignorer ») n'est plus resignalé indéfiniment à chaque vérification nocturne — seul un nouveau changement de nom déclenche à nouveau l'alerte.",
        ],
      },
    ],
  },
  {
    entete: "v 2.8.0 — 20 août 2026",
    categories: [
      {
        categorie: "Premiers pas",
        items: [
          "Une présentation de bienvenue s'affiche automatiquement à votre première connexion (suivi, filtres, notifications, installation) — rejouable à tout moment depuis Aide → « Revoir la présentation de bienvenue ».",
          "Une checklist des premiers pas reste visible sur Aide tant que vous n'avez pas configuré vos départements, suivi un artiste ou une salle, et activé une notification.",
          "Un rappel s'affiche après votre tout premier ajout d'artiste ou de salle pour vous inviter à activer les notifications.",
        ],
      },
      {
        categorie: "Application installée (PWA)",
        items: [
          "Sur Chrome/Edge desktop, une bannière propose désormais l'installation de l'application après quelques visites.",
        ],
      },
    ],
  },
  {
    entete: "v 2.7.9 — 12 août 2026",
    categories: [
      {
        categorie: "Navigation",
        items: [
          "Un clic sur un lien de navigation depuis une page défilée remonte désormais en haut de la page cible, au lieu de conserver la position de défilement précédente.",
        ],
      },
    ],
  },
  {
    entete: "v 2.7.8 — 5 août 2026",
    categories: [
      {
        categorie: "Compte",
        items: [
          "Nouvel écran dans Paramètres → Compte : consultez vos appareils connectés (session actuelle repérée) et révoquez-en un à distance.",
          "Vos abonnements aux notifications push sont désormais listés et révocables individuellement au même endroit.",
        ],
      },
      {
        categorie: "Concerts",
        items: [
          "Un badge « À vérifier » signale désormais un concert que la source n'a pas reconfirmé depuis plusieurs vérifications — l'information est peut-être obsolète, mais le concert reste affiché normalement.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "Un admin peut désormais déconnecter à distance toutes les sessions actives d'un utilisateur.",
        ],
      },
    ],
  },
  {
    entete: "v 2.7.7 — 31 juillet 2026",
    categories: [
      {
        categorie: "Application installée (PWA)",
        items: [
          "Un message d'erreur pouvait rester caché derrière la barre de navigation en bas d'écran sur mobile — corrigé.",
          "Après un redémarrage de l'application hors connexion, un clic sur le menu pouvait vous déconnecter à tort alors que votre session restait valide — corrigé.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "Un artiste mal rattaché à un lien de billetterie erroné peut désormais être corrigé directement, sans passer par une fusion.",
        ],
      },
    ],
  },
  {
    entete: "v 2.7.6 — 23 juillet 2026",
    categories: [
      {
        categorie: "Concerts",
        items: [
          'Un artiste suivi ("Furies") pouvait réafficher les concerts d\'un homonyme même après correction manuelle — corrigé durablement, plus seulement en surface.',
        ],
      },
      {
        categorie: "Administration",
        items: [
          "Certains humoristes ne pouvaient jamais recevoir de lien Wikidata dans le référentiel Artistes — corrigé.",
          "Fusionner deux salles ou artistes ayant chacun un identifiant du même type de billetterie ne laissait plus qu'un seul identifiant, plutôt que de les faire coexister indéfiniment.",
        ],
      },
    ],
  },
  {
    entete: "v 2.7.5 — 22 juillet 2026",
    categories: [
      {
        categorie: "Salles",
        items: [
          "Plusieurs salles enregistrées deux fois sous des noms légèrement différents (avec ou sans le nom de la ville) faisaient apparaître leurs concerts en double — fusionnées.",
        ],
      },
      {
        categorie: "Concerts",
        items: [
          "Certains artistes suivis pouvaient afficher les concerts d'un spectacle hommage ou d'un homonyme plutôt que les leurs — corrigé.",
          "Des spectacles d'humour pouvaient s'afficher sous le nom d'un musicien sans rapport — corrigé.",
        ],
      },
    ],
  },
  {
    entete: "v 2.7.4 — 20 juillet 2026",
    categories: [
      {
        categorie: "Navigation",
        items: [
          "Un clic sur un lien juste après une mise à jour de l'application, sans avoir rechargé la page, pouvait bloquer la navigation sur la page en cours — corrigé.",
        ],
      },
      {
        categorie: "Salles",
        items: [
          "Plusieurs grandes salles bien connues (Palais Garnier, Accor Arena, Le Bataclan…) ne pouvaient jamais afficher leur lien Wikidata dans le référentiel — corrigé.",
        ],
      },
    ],
  },
  {
    entete: "v 2.7.2 — 18 juillet 2026",
    categories: [
      {
        categorie: "Navigation",
        items: [
          "L'indicateur de nouveauté sur « Nouveautés » est désormais un point doré discret plutôt qu'un point rouge, moins associé à une erreur qu'à une actualité.",
        ],
      },
      {
        categorie: "Salles",
        items: [
          "Deux salles portant le même nom mais situées dans des villes différentes pouvaient être fusionnées à tort, mélangeant leurs concerts — corrigé.",
        ],
      },
      {
        categorie: "Application installée (PWA)",
        items: [
          "Le filet de secours affiché en cas de problème de chargement pouvait ne jamais apparaître pour un visiteur non encore connecté — corrigé.",
        ],
      },
    ],
  },
  {
    entete: "v 2.7.1 — 17 juillet 2026",
    categories: [
      {
        categorie: "Concerts",
        items: [
          "Certains concerts d'une salle suivie restaient bloqués sur un statut ou un prix ancien sans jamais se mettre à jour — corrigé.",
          "Certains concerts (ex. AC/DC au Metronum, Dorothée au Palais des Congrès de Paris) n'étaient jamais rattachés à leur salle malgré un nom bien identifiable — corrigé.",
        ],
      },
    ],
  },
  {
    entete: "v 2.7.0 — 17 juillet 2026",
    categories: [
      {
        categorie: "Administration",
        items: [
          "Dans le référentiel Artistes, un statut « À vérifier » ne s'affiche plus jamais à tort : seul un statut « Invalide » confirmé par une vérification active apparaît désormais, l'ancien indicateur intermédiaire générait trop de faux positifs sur des artistes pourtant bien actifs.",
          "Une adresse dédiée aux alertes système (panne, bounce email) peut désormais être configurée séparément de l'adresse d'expédition des emails.",
        ],
      },
      {
        categorie: "Navigation",
        items: [
          "Sur mobile, un appui sur un lien du menu « plus » pouvait parfois refermer le menu au lieu de naviguer — corrigé.",
        ],
      },
      {
        categorie: "Concerts",
        items: [
          "Certains concerts d'une salle suivie via plusieurs sources (billetteries) n'apparaissaient plus en double lorsque l'orthographe du nom variait légèrement d'une source à l'autre.",
          "Les concerts Eventim, indisponibles depuis plusieurs jours suite à une panne technique côté billetterie, sont de nouveau détectés.",
        ],
      },
      {
        categorie: "Salles",
        items: [
          "Ajouter une salle dont l'identifiant Wikidata était déjà associé à une autre salle provoquait une erreur au lieu de l'ajouter normalement — corrigé.",
        ],
      },
      {
        categorie: "Notifications",
        items: [
          "Les notifications email de nouveaux concerts pouvaient échouer silencieusement à chaque cycle — corrigé.",
        ],
      },
      {
        categorie: "Application installée (PWA)",
        items: [
          "La page affichée lors d'un tout premier lancement hors ligne respecte désormais le thème sombre au lieu d'afficher un flash blanc.",
        ],
      },
    ],
  },
  {
    entete: "v 2.6.5 — 5 juillet 2026",
    categories: [
      {
        categorie: "Administration",
        items: [
          "Régénérer les clés VAPID affiche désormais immédiatement le nombre d'abonnements push invalidés par le changement.",
          "L'onglet Bannière affiche désormais l'état actuellement publié (message et type) plutôt que de laisser l'admin deviner.",
          "Le libellé « Jamais connecté » a été remplacé par « Aucune session active », plus fidèle pour un utilisateur dont la session a simplement expiré.",
        ],
      },
    ],
  },
  {
    entete: "v 2.6.4 — 5 juillet 2026",
    categories: [
      {
        categorie: "Compte",
        items: [
          "Un identifiant contenant un point ou un tiret est maintenant accepté à l'enregistrement, comme annoncé par le formulaire.",
          "Un nom personnalisé par un administrateur n'est plus écrasé silencieusement quand vous changez votre identifiant.",
          "Une coupure réseau brève ne vous déconnecte plus par erreur — seule une session réellement expirée vous renvoie vers la connexion.",
        ],
      },
      {
        categorie: "Accueil / Mes spectacles",
        items: [
          "Le badge « Nouveau » est désormais cohérent entre l'accueil et Mes spectacles.",
          "Masquer un artiste masque maintenant bien tous ses concerts, même ceux détectés sous une orthographe légèrement différente selon la source.",
        ],
      },
      {
        categorie: "Aide",
        items: [
          "Le message de confirmation après l'envoi d'un signalement s'affiche désormais avant la fermeture du formulaire.",
          "Si aucun moyen de contact n'est configuré, un message clair s'affiche à la place d'un lien de contact cassé.",
        ],
      },
      {
        categorie: "Notifications push",
        items: [
          "Cliquer sur une notification fonctionne à nouveau correctement dans tous les cas de configuration.",
          "Le bouton d'activation des notifications push est désactivé quand la fréquence de notification est sur « Jamais ».",
          "Le bouton « Envoyer une notification de test » cible désormais l'appareil depuis lequel vous cliquez.",
        ],
      },
      {
        categorie: "Bannière système",
        items: [
          "Une bannière refermée automatiquement réapparaît de nouveau à la page suivante, comme annoncé — elle restait auparavant masquée jusqu'au rechargement complet.",
        ],
      },
      {
        categorie: "Qualité des données",
        items: [
          "Fusionner deux fiches d'un même artiste dans l'administration ne laisse plus de concert en double.",
          "L'import de vos préférences reconnaît désormais un artiste ou une salle déjà suivi même sous une variante orthographique proche, au lieu de créer un doublon.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "Un slug Digitick déjà utilisé par une autre salle est désormais refusé explicitement plutôt que d'être repris silencieusement.",
          "Modifier le nom ou l'email d'un utilisateur en même temps qu'une rétrogradation administrateur n'efface plus ces modifications.",
          "Réinitialiser le mot de passe d'un utilisateur affiche un lien de secours si l'envoi d'email n'est pas disponible.",
          "Sélectionner deux éléments à fusionner sur des pages différentes de la liste ne casse plus la boîte de dialogue de fusion.",
          "Une plainte spam sur un email envoyé est désormais visible et notifiée, comme un rejet d'email.",
        ],
      },
      {
        categorie: "Nouveautés",
        items: [
          "Un indicateur signale désormais qu'une nouvelle version contient des nouveautés pas encore consultées.",
        ],
      },
    ],
  },
  {
    entete: "v 2.6.3 — 4 juillet 2026",
    categories: [
      {
        categorie: "Accueil",
        items: [
          "Le badge « Nouveau » de vos concerts n'est plus effacé à tort par une simple visite de l'accueil quand il concernait une salle suivie — il reste visible jusqu'à consultation réelle dans Mes spectacles.",
          "Cliquer sur une notification puis changer de filtre sur l'accueil ne re-déclenche plus un défilement automatique intempestif vers le concert d'origine.",
        ],
      },
      {
        categorie: "Qualité des données",
        items: [
          "Deux séances distinctes du même spectacle le même jour (matinée et soirée) ne sont plus fusionnées à tort en une seule.",
          "Les doublons résiduels entre Ticketmaster/Eventim et Digitick sont désormais nettoyés, pas seulement entre Ticketmaster et Eventim.",
        ],
      },
      {
        categorie: "Notifications",
        items: [
          "Un concert n'est plus marqué à tort comme notifié quand seul l'un de vos deux canaux (email/push) a réellement réussi à vous prévenir.",
        ],
      },
      {
        categorie: "Sécurité",
        items: [
          "Changer son mot de passe déconnecte désormais aussi vos sessions actives sur les autres appareils.",
        ],
      },
    ],
  },
  {
    entete: "v 2.6.2 — 3 juillet 2026",
    categories: [
      {
        categorie: "Salles",
        items: [
          "Les suggestions à la recherche d'une salle reconnaissent désormais aussi les salles classées différemment sur OpenStreetMap (ex. stades, salles polyvalentes) — une salle de concert n'était pas toujours proposée selon sa catégorisation, tandis que des lieux non pertinents (bornes de recharge…) pouvaient s'afficher à tort.",
        ],
      },
      {
        categorie: "Artistes",
        items: [
          "Rechercher un artiste avec un terme partiel affiche désormais correctement « Vous suivez déjà » quand un artiste correspondant est déjà dans vos suivis, plutôt que « Aucun résultat ».",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "Les cartes Ticketmaster, Eventim et Deezer de l'onglet Sources affichent désormais le nombre de salles/artistes suivis via chaque source, comme c'était déjà le cas pour Digitick et Wikidata.",
          "Les statuts « Vivant »/« Suspect »/« Mort » des onglets Salles/Artistes sont renommés en « À jour »/« À vérifier »/« Invalide », plus explicites.",
          "Simplification d'une mention technique superflue sur la carte Wikidata.",
        ],
      },
      {
        categorie: "Qualité des données",
        items: [
          "Les salles dont le nom contient une apostrophe (ex. « L'Empreinte ») peuvent désormais être rapprochées correctement des données OpenStreetMap, qui utilisent parfois une apostrophe typographique différente de celle de nos sources.",
        ],
      },
      {
        categorie: "Fiabilité",
        items: [
          "Sur la PWA installée sur iPhone, les notifications ne sont plus masquées par la barre de navigation du bas.",
        ],
      },
      {
        categorie: "Interface",
        items: [
          "Les anciennes versions de cette page se replient désormais automatiquement — seule la dernière reste visible par défaut.",
        ],
      },
    ],
  },
  {
    entete: "v 2.6.1 — 2 juillet 2026",
    categories: [
      {
        categorie: "Ajout d'artistes et de salles",
        items: [
          "Les suggestions à l'ajout d'un artiste affichent désormais une description (source Wikidata) pour vous aider à choisir le bon artiste parmi plusieurs homonymes.",
          "Les suggestions à l'ajout d'une salle s'appuient désormais aussi sur OpenStreetMap, en complément de Ticketmaster — plus de salles proposées, badge « osm » sur les résultats correspondants.",
          "Un nom d'attraction de type « hommage à » ou « tribute to » n'est plus proposé à l'ajout : suivez directement l'artiste original.",
        ],
      },
      {
        categorie: "Qualité des données",
        items: [
          "Les artistes non-musiciens (humoristes notamment) peuvent désormais être enrichis avec leur fiche Wikidata.",
          "Amélioration du rapprochement automatique des salles dont le nom a changé au fil du temps (renommages commerciaux).",
        ],
      },
      {
        categorie: "Fiabilité",
        items: [
          "Correction d'un écran noir pouvant apparaître au tout premier lancement de l'application installée (PWA) sans connexion — un message clair s'affiche désormais.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "Un job de maintenance jamais encore exécuté est désormais visible et peut être déclenché manuellement depuis l'onglet « Jobs ».",
          "Alerte email automatique en cas de nouvelles erreurs détectées dans les journaux d'administration.",
        ],
      },
    ],
  },
  {
    entete: "v 2.6.0 — 2 juillet 2026",
    categories: [
      {
        categorie: "Qualité des données",
        items: [
          "Les noms des salles et des artistes sont désormais progressivement enrichis avec leur nom canonique (source Wikidata/OpenStreetMap), pour plus de cohérence dans l'affichage.",
          "Un job nocturne détecte automatiquement les identifiants de salles/artistes devenus invalides chez Ticketmaster et tente de les retrouver.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "Nouveaux onglets « Salles » et « Artistes » dans l'administration : vue d'ensemble du référentiel, correction manuelle du nom affiché, fusion de doublons.",
          "Wikidata apparaît désormais dans l'onglet « Sources » de l'administration, avec le nombre de salles et d'artistes enrichis.",
          "Filtre « Sans Wikidata » et bouton « Réinitialiser » dans les onglets « Salles »/« Artistes » pour repérer et corriger facilement les entrées non enrichies ou mal associées.",
        ],
      },
    ],
  },
  {
    entete: "v 2.5.7 — 1er juillet 2026",
    categories: [
      {
        categorie: "Qualité des données",
        items: [
          "Les concerts issus de Digitick sont désormais correctement rattachés à un artiste suivi, ce qui les fait apparaître dans vos correspondances et notifications.",
          "Correction d'un bug pouvant empêcher certains concerts d'être enregistrés quand deux sources (Ticketmaster et Eventim) nommaient la même salle différemment lors d'un même cycle de fetch.",
        ],
      },
    ],
  },
  {
    entete: "v 2.5.6 — 30 juin 2026",
    categories: [
      {
        categorie: "Sécurité",
        items: [
          "Le compteur d'échecs du code d'authentification à deux facteurs est maintenant persisté en base de données. Après 5 tentatives incorrectes, le compte est verrouillé de façon permanente et un administrateur doit réinitialiser la 2FA pour le déverrouiller.",
        ],
      },
    ],
  },
  {
    entete: "v 2.5.5 — 30 juin 2026",
    categories: [
      {
        categorie: "Performances",
        items: [
          "La page Découverte se charge plus rapidement : les requêtes de filtres disponibles (genres, mois, classifications) et les données de synchronisation sont maintenant calculées en parallèle.",
        ],
      },
    ],
  },
  {
    entete: "v 2.5.4 — 29 juin 2026",
    categories: [
      {
        categorie: "Notifications",
        items: [
          "Les notifications push peuvent désormais être activées indépendamment des emails — il n'est plus nécessaire d'avoir une fréquence email active pour recevoir des notifications sur l'appareil.",
        ],
      },
    ],
  },
  {
    entete: "v 2.5.3 — 29 juin 2026",
    categories: [
      {
        categorie: "Accessibilité",
        items: [
          "La page de connexion indique désormais son titre aux lecteurs d'écran (h1 invisible à l'affichage, lisible pour les technologies d'assistance).",
          "Les messages d'erreur et de succès sur la page de connexion sont automatiquement lus par les lecteurs d'écran dès leur apparition.",
          'Les liens de navigation (sidebar, barre mobile, menu « Plus ») indiquent aux technologies d\'assistance quel est le lien correspondant à la page active (<code>aria-current="page"</code>).',
          "Le menu « Plus » sur mobile est maintenant déclaré comme un dialogue modal, empêchant les lecteurs d'écran de parcourir le contenu situé derrière.",
          "Le lien « Aller au contenu principal » (navigation clavier) s'affiche avec un contour visible au focus — le ratio de contraste est passé de 1,09:1 à plus de 12:1.",
          "L'indicateur de focus sur les champs de saisie est désormais un contour solide (2 px, couleur primaire) au lieu d'un halo semi-transparent peu visible.",
          "Le contraste des boutons de texte secondaires a été amélioré pour atteindre le seuil WCAG 2.1 AA (4,5:1).",
          "La hauteur minimale des liens du menu mobile est portée à 44 px pour faciliter l'interaction tactile (WCAG 2.5.5).",
        ],
      },
    ],
  },
  {
    entete: "v 2.5.0 — 29 juin 2026",
    categories: [
      {
        categorie: "Aide",
        items: [
          "La page Aide indique désormais que la suppression de compte se fait directement depuis Paramètres → Compte, sans passer par l'administrateur.",
        ],
      },
      {
        categorie: "Paramètres",
        items: [
          "Le champ de modification d'identifiant accepte maintenant les points (.) et tirets (-), en cohérence avec la validation serveur.",
        ],
      },
    ],
  },
  {
    entete: "v 2.4.1 — 29 juin 2026",
    categories: [
      {
        categorie: "Compte",
        items: [
          "Les identifiants de connexion peuvent désormais contenir des points (.) et des tirets (-) en plus des lettres, chiffres et underscores.",
        ],
      },
      {
        categorie: "Concerts",
        items: [
          "Des concerts d'un artiste homonyme ou dont le nom contient celui d'un artiste suivi (ex. « Anuv Jain » lors d'un fetch de « Jain ») pouvaient s'afficher à tort — corrigé.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "La liste des utilisateurs affiche maintenant le nombre d'artistes et de salles suivis par chaque compte.",
          "Un lien Signal (groupe ou contact) peut être configuré depuis Admin > Configuration. S'il est renseigné, il apparaît sur la page Aide.",
        ],
      },
      {
        categorie: "Compatibilité",
        items: [
          "Sur certains navigateurs (Chrome, Safari), la mise à jour de l'application PWA pouvait laisser la page bloquée sur l'écran « Mise à jour en cours… » — corrigé.",
        ],
      },
    ],
  },
  {
    entete: "v 2.4.0 — 28 juin 2026",
    categories: [
      {
        categorie: "Concerts",
        items: [
          "Digitick est désormais intégré comme source de concerts : les salles configurées avec un identifiant Digitick récupèrent automatiquement leur programme depuis web.digitick.com — couverture des salles non disponibles via Ticketmaster ou Eventim (ex. L'Empreinte, Savigny-le-Temple).",
        ],
      },
      {
        categorie: "Salles",
        items: [
          "Les salles Digitick configurées apparaissent désormais dans les résultats de recherche avec un badge « Digitick », même avant l'import de leurs premiers concerts. Elles sont également identifiées dans « Mes salles ».",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "L'onglet Sources permet désormais de créer, modifier et supprimer des salles Digitick directement (nom, slug, ville, département) — sans passer par un import manuel. Le bouton « Tester » vérifie que le slug retourne bien des concerts avant la création.",
          "Les concerts des salles configurées (Ticketmaster, Eventim, Digitick) sont désormais rechargés périodiquement en arrière-plan — sans attendre une action manuelle ou l'ajout d'une salle par un utilisateur.",
        ],
      },
      {
        categorie: "Compatibilité",
        items: [
          "Lors d'une recherche de salle, une même salle pouvait apparaître en double si elle était liée à plusieurs sources (Digitick et Eventim) — corrigé.",
          "Sur iPhone, une mise à jour de l'application affichait un écran entièrement blanc pendant la transition. Un écran de chargement (icône + \"Mise à jour en cours…\") s'affiche désormais pendant cette courte transition.",
          'Le nom de certaines salles Eventim s\'affichait en majuscules (ex. "LEMPREINTE SAVIGNY LE TEMPLE CEDEX"). Les noms sont désormais normalisés en casse lisible.',
          "Le nom de certaines salles Ticketmaster s'affichait entièrement en majuscules dans les résultats de recherche — corrigé.",
          "Lors de la recherche d'une salle, les salles déjà connues (avec des concerts en base) apparaissent désormais dans les suggestions locales sans déclencher un appel à Ticketmaster — évite les résultats en double ou la confusion entre plusieurs identifiants pour la même salle.",
          "Certaines salles Eventim qui changent de nom entre deux mises à jour (ex. « Grand Kursaal » / « Kursaal Besancon », « Auditorium Megacite » / « Megacite Amiens ») créaient des concerts en double — corrigé.",
          "Les artistes Ticketmaster dont le nom contient des caractères stylistiques comme « $ » ou « & » (ex. « Chri$tian Gates ») ne créent plus de doublons lorsque Ticketmaster corrige le nom lors d'un fetch ultérieur.",
          "Pour les pages de festival (ex. Solidays) dont le nom de l'artiste varie selon le cycle de mise à jour, le concert n'est désormais créé qu'une seule fois — les notifications répétées sur le même concert sont supprimées.",
        ],
      },
    ],
  },
  {
    entete: "v 2.3.0 — 25 juin 2026",
    categories: [
      {
        categorie: "Interface",
        items: [
          "Un indicateur de chargement (animation) s'affiche désormais pendant la récupération des données sur toutes les pages — plus d'écran vide ou de message \"aucun résultat\" trompeur pendant le chargement.",
        ],
      },
      {
        categorie: "Concerts",
        items: [
          "Les artistes dont le nom contient des caractères nordiques ou slaves non convertibles (Ø, Ł, Đ, Ŋ) ne recevaient aucun concert — ces lettres sont désormais correctement transcrites lors de la recherche (ex. MØØNSPELL → Moonspell).",
          "Les concerts découverts via la recherche régionale Eventim n'apparaissaient plus — une incompatibilité entre les catégories Ticketmaster et Eventim empêchait tout résultat. La recherche régionale Eventim est à nouveau fonctionnelle.",
          "Un concert peut désormais être correctement attribué à un artiste dont le nom contient une apostrophe (ex. Shy'm) même si Eventim référence un homonyme sous un nom différent.",
          "Le nom d'artiste s'affiche désormais tel que vous le suivez — si vous suivez \"MØØNSPELL\", les concerts ne s'affichent plus sous le nom retourné par Ticketmaster (\"Moonspell\"). Cela s'applique à l'accueil, à la liste des spectacles, à la découverte et aux notifications.",
          'Les artistes "Krash Karma" et "Krashkarma" étaient enregistrés en double — ils sont désormais unifiés sous "Krash Karma". Concerts et préférences des deux noms sont conservés.',
          'Eventim ne trouvait pas les artistes dont le nom est plus court que celui qu\'il utilise (ex. "-M-" non reconnu car Eventim liste "-M- Matthieu Chedid") — ces artistes récupèrent désormais leurs concerts Eventim.',
          "Des concerts sold-out Eventim s'affichaient à tort avec le badge « Prévente » — corrigé. L'API Eventim ne distinguant pas prévente et complet, aucun badge n'est désormais affiché pour ces concerts.",
          "Les spectacles à séances multiples (matinée et soirée le même jour dans la même salle) sont maintenant affichés comme deux événements distincts plutôt que fusionnés.",
          "Des concerts de l'artiste \"Shy\" (Ticketmaster) pouvaient apparaître à tort pour l'artiste Shy'm suivi — la correspondance exacte est désormais vérifiée parmi plusieurs résultats.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "Un nouvel onglet « Sources » dans l'administration affiche l'état de chaque source de données : quota API Ticketmaster avec jauge de consommation, dernier fetch Eventim, statut Deezer et nombre de salles configurées pour Digitick.",
          "La liste des salles sans source inclut désormais les salles sans identifiant Eventim, pas uniquement celles sans identifiant Ticketmaster — le bouton est renommé « Sans source » en conséquence. Les salles Eventim disposent désormais d'un identifiant interne : la liste « Sans source » est vide par défaut et réservée à la future intégration Digitick.",
          "L'onglet Jobs affiche maintenant deux sections distinctes : « Fetches » (mises à jour concerts, avec bouton de relance) et « Maintenance » (purge concerts/salles/artistes, recalcul matchs) — la purge des salles et artistes orphelins est maintenant visible dans cette section.",
        ],
      },
      {
        categorie: "Salles",
        items: [
          "Sept salles existaient en double (une version Ticketmaster, une version Eventim avec un nom légèrement différent) — les concerts dupliqués dans la découverte disparaissent, et les préférences sont conservées.",
          "Des doublons de salles créés lors de récupérations précédentes (notamment pour les grandes salles parisiennes) ont été fusionnés — les concerts et préférences associés sont conservés.",
          "Le nom de plusieurs salles a été corrigé pour inclure les accents manquants (ex. Elysée Montmartre, Opéra Bastille, Théâtre Mogador).",
        ],
      },
      {
        categorie: "Compatibilité",
        items: [
          "Sur Safari (macOS et iOS), naviguer vers le site depuis un lien (email, autre application) ne provoquait plus de redirection vers la page de connexion — la session est désormais correctement conservée.",
          "Après une mise à jour, les feuilles de style pouvaient rester en version ancienne pendant plusieurs heures. Le rechargement est maintenant forcé dès la mise à jour disponible.",
          "Sur les appareils ayant installé l'application (PWA), l'onglet « Sources » de l'administration pouvait ne pas apparaître suite à un cache non mis à jour — résolu.",
          "Lorsque l'application était relancée sans réseau (PWA sur iOS), une page blanche s'affichait au lieu de la page hors ligne — corrigé.",
          "Après un retour de connexion, le lien « Administration » pouvait rester masqué jusqu'au prochain rechargement complet — la session est désormais rechargée automatiquement à la reconnexion.",
          "L'architecture du cache PWA a été entièrement revue : les feuilles de style sont désormais empaquetées avec les bundles JS (fingerprinting Vite) et précachées par Workbox. Les pages, images et icônes bénéficient d'une stratégie cohérente entre le cache HTTP et le service worker — les onglets qui disparaissaient après une perte de réseau sont définitivement corrigés.",
        ],
      },
    ],
  },
  {
    entete: "v 2.2.5 — 18 juin 2026",
    categories: [
      {
        categorie: "Interface",
        items: [
          "Les fenêtres de confirmation (suppression, réinitialisation…) s'affichent désormais centrées à l'écran.",
        ],
      },
      {
        categorie: "Compte et sécurité",
        items: [
          "Il n'est plus possible de supprimer son compte lorsqu'on est le seul administrateur de l'instance — un message d'erreur l'indique clairement.",
        ],
      },
      {
        categorie: "Concerts",
        items: [
          "Un concert dont le nom de l'artiste varie légèrement entre deux récupérations (ex. ajout d'un numéro ou d'un article) n'apparaît plus en double.",
          "La ville de certaines salles de la petite couronne (ex. La Seine Musicale) n'est plus affichée à tort comme « Paris ».",
        ],
      },
      {
        categorie: "Salles",
        items: [
          "La recherche de salle fonctionne désormais avec les apostrophes, accents et tirets — taper « l'empreinte » ou « zénith » retrouve les salles correspondantes.",
          "Si une salle n'est pas référencée, un bouton « Signaler cette salle » apparaît sous le message d'absence de résultat. Un formulaire permet d'envoyer le nom, la ville, le site et un commentaire à l'administrateur — une confirmation est envoyée par email.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "Une confirmation est désormais demandée avant de réinitialiser le mot de passe ou la double authentification d'un utilisateur.",
          "L'utilisateur dont le compte est supprimé reçoit maintenant un email de notification.",
          "Le nom d'affichage d'un utilisateur peut désormais être modifié depuis le panneau d'administration.",
          "Un email est envoyé automatiquement à un utilisateur lorsqu'il est promu administrateur.",
          "La rétrogradation d'un administrateur est désormais différée de 7 jours, avec envoi d'un email de préavis. La révocation peut être annulée pendant ce délai.",
        ],
      },
    ],
  },
  {
    entete: "v 2.2.4 — 12 juin 2026",
    categories: [
      {
        categorie: "Interface",
        items: [
          "Un badge « Prévente » signale les concerts dont la billetterie n'est pas encore ouverte, sur l'accueil et dans Mes spectacles.",
          "Les concerts dont la vente n'a pas encore commencé ne sont plus affichés « Complet » à tort.",
        ],
      },
      {
        categorie: "Compte et sécurité",
        items: [
          "Un administrateur peut réinitialiser la double authentification (2FA) d'un utilisateur qui a perdu l'accès à son application d'authentification.",
          "La connexion avec 2FA pouvait renvoyer à tort vers l'écran de connexion : c'est corrigé.",
          "Le message affiché après trop de tentatives de connexion est désormais plus clair.",
        ],
      },
    ],
  },
  {
    entete: "v 2.2.3 — 11 juin 2026",
    categories: [
      {
        categorie: "Interface",
        items: [
          "Le bandeau d'information système ne réapparaît plus après avoir disparu automatiquement.",
          "Les animations de transition entre les pages sont désormais identiques depuis le menu mobile.",
        ],
      },
    ],
  },
  {
    entete: "v 2.2.2 — 11 juin 2026",
    categories: [
      {
        categorie: "Application installée (PWA)",
        items: [
          "Un appui long sur l'icône de l'application propose des raccourcis directs vers Mes spectacles, Découverte et les nouveaux concerts.",
        ],
      },
      {
        categorie: "Notifications push",
        items: [
          "Un bouton « Envoyer une notification de test » dans les paramètres permet de vérifier que les notifications push fonctionnent sur votre appareil.",
        ],
      },
      {
        categorie: "Accessibilité",
        items: [
          "Un lien « Aller au contenu » apparaît au clavier pour passer directement la navigation.",
          "L'interface respecte désormais la taille de police choisie dans votre navigateur.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "L'invitation d'un nouvel utilisateur fonctionne même sans service email configuré : le lien d'invitation est alors affiché à l'administrateur pour transmission manuelle.",
        ],
      },
    ],
  },
  {
    entete: "v 2.2.1 — 11 juin 2026",
    categories: [
      {
        categorie: "Notifications push",
        items: [
          "Clic sur une notification push : l'application défile directement jusqu'à la carte de l'artiste concerné et efface le badge de l'application sur l'écran d'accueil.",
        ],
      },
      {
        categorie: "Interface",
        items: [
          "Les alertes visuelles (toasts) s'affichent correctement dans tous les cas, y compris lors des actions d'administration.",
          "Une alerte s'affiche quand vous perdez votre connexion à Internet ; elle disparaît automatiquement quand la connexion est rétablie.",
        ],
      },
      {
        categorie: "Connexion",
        items: [
          "En cas de trop nombreuses tentatives de connexion, un message explicite indique qu'il faut patienter avant de réessayer.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "Les liens d'invitation sont désormais valables 7 jours (au lieu de 24 heures).",
        ],
      },
    ],
  },
  {
    entete: "v 2.2.0 — 6 juin 2026",
    categories: [
      {
        categorie: "Navigation",
        items: [
          "L'application est désormais entièrement côté client (Vue 3) — la navigation entre pages est instantanée, sans rechargement.",
          "Les transitions entre pages sont animées : le contenu glisse ou fondu en douceur lors de chaque changement de page.",
          "L'icône « Mes spectacles » dans la barre de navigation est maintenant distincte du bouton « Acheter des billets ».",
        ],
      },
      {
        categorie: "Paramètres & Administration",
        items: [
          "Les onglets chargent leur contenu à la demande — le premier onglet s'affiche immédiatement, les autres au premier clic.",
          "L'état de chaque onglet est conservé quand on navigue vers une autre page puis qu'on revient.",
        ],
      },
      {
        categorie: "Interface",
        items: [
          "Les versions précédentes dans cette page s'ouvrent et se referment avec une animation progressive.",
        ],
      },
      {
        categorie: "Correctifs",
        items: [
          "La création de compte par invitation se fait en une seule étape — le lien d'invitation ouvre directement un formulaire pour choisir son identifiant et son mot de passe, sans connexion intermédiaire requise.",
        ],
      },
    ],
  },
  {
    entete: "v 2.1.3 — 3 juin 2026",
    categories: [
      {
        categorie: "Notifications",
        items: [
          "Les préférences email et push sont maintenant indépendantes — une grille permet de choisir séparément pour les artistes et les salles : recevoir l'alerte par email, par push, les deux, ou aucun.",
        ],
      },
      {
        categorie: "Correctifs",
        items: [
          "La session reste active après un changement de réseau (WiFi ↔ mobile) ou un passage en arrière-plan sur PWA — plus de déconnexions inopinées.",
          "Le clic sur une notification push fait défiler correctement jusqu'à la carte de l'artiste, même quand son nom contient des espaces.",
          "Les notifications push pour un concert de salle renvoient désormais vers la liste des spectacles plutôt qu'une ancre inexistante sur l'accueil.",
          "Le bouton \"Voir mes spectacles\" dans l'email digest renvoie vers l'accueil.",
        ],
      },
    ],
  },
  {
    entete: "v 2.1.2 — 2 juin 2026",
    categories: [
      {
        categorie: "Notifications push",
        items: [
          "Le clic sur une notification ouvre directement l'accueil et fait défiler jusqu'à la carte de l'artiste concerné — sans rechargement si l'application est déjà ouverte.",
          "Les notifications push sont désormais reçues sur iPhone et iPad (correction d'un problème de chiffrement des clés d'abonnement Apple).",
        ],
      },
      {
        categorie: "Correctifs",
        items: [
          "La déconnexion depuis le menu de navigation fonctionne correctement après une navigation rapide entre pages.",
          "Le menu tiroir se ferme automatiquement après un clic sur un lien interne.",
          "L'onglet actif se met à jour correctement dans la barre latérale et le menu tiroir après chaque changement de page.",
        ],
      },
    ],
  },
  {
    entete: "v 2.1.1 — 1er juin 2026",
    categories: [
      {
        categorie: "Performance",
        items: [
          "La navigation entre les pages est plus rapide : seule la zone de contenu est rechargée côté serveur, sans renvoyer la barre latérale et la navigation mobile à chaque clic.",
          "Les pages HTML, CSS et JSON sont désormais compressés (Gzip) avant envoi, réduisant la taille des réponses de 60 à 70 %.",
          "La police et HTMX sont mis en cache par le service worker dès la première visite, évitant de les recharger à chaque session sur mobile et en mode PWA.",
        ],
      },
      {
        categorie: "Interface",
        items: [
          "Les titres des sections dans les pages Administration et Paramètres flottent désormais sur la bordure supérieure de chaque bloc, à la manière d'une légende de formulaire.",
          "La boîte de confirmation avant la suppression d'un compte utilisateur ou la régénération des clés VAPID s'affiche correctement et attend votre confirmation avant d'agir.",
        ],
      },
    ],
  },
  {
    entete: "v 2.1.0 — 30 mai 2026",
    categories: [
      {
        categorie: "Interface",
        items: [
          "Les actions (ajout, suppression d'artiste ou de salle, sauvegarde des paramètres, activation des notifications push) affichent désormais un message de confirmation en bas de l'écran, qui disparaît automatiquement. Les erreurs restent visibles jusqu'à fermeture manuelle.",
          "Les administrateurs peuvent désormais publier un message système visible par tous les utilisateurs connectés. Le message apparaît en haut de chaque page et se ferme automatiquement (10 s pour les informations, 20 s pour les avertissements) ou manuellement pour les alertes.",
          "Le badge « Nouveau » sur l'icône Accueil apparaît dès qu'un concert non encore vu est disponible — sur la barre de navigation mobile et dans le menu latéral sur ordinateur. Il disparaît à la prochaine visite de la page d'accueil.",
        ],
      },
      {
        categorie: "Mes spectacles & Découverte",
        items: [
          "Refonte du tableau de concerts : nom de l'artiste en gras, date en couleur, lieu et ville fusionnés en une seule colonne.",
          "Les badges de statut (Annulé, Reporté, Complet, Prévente) s'affichent directement sous le nom de l'artiste.",
        ],
      },
      {
        categorie: "PWA",
        items: [
          "Rechargement automatique de l'application lors du retour de connexion (après une coupure réseau).",
          "La page hors ligne respecte désormais le thème sombre ou clair choisi par l'utilisateur.",
          "Le bouton « Réessayer » sur la page hors ligne redirige correctement vers l'accueil sans nécessiter de fermer puis rouvrir l'application.",
          "Le badge sur l'icône d'accueil (Android, iOS 18+) reflète le nombre de nouveaux concerts — il se met à jour à chaque ouverture et lors des notifications push.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "Les bounces email (adresses invalides ou inexistantes) sont désormais détectés via les webhooks Resend. L'administrateur reçoit une notification email avec l'adresse concernée. Nécessite la configuration de la clé <code>RESEND_WEBHOOK_SECRET</code> depuis l'administration.",
          "La page Configuration regroupe désormais les clés par catégorie : Ticketmaster, Email (Resend), Général et Notifications push (VAPID).",
        ],
      },
      {
        categorie: "Correctifs",
        items: [
          "Le bouton « Acheter des billets » était invisible en vue desktop — corrigé.",
          "La page Administration était inaccessible et renvoyait vers la connexion — corrigé.",
          "Les boutons « Copier » et « Fermer » de la modale d'invitation (création d'utilisateur) n'avaient aucun effet — corrigé.",
          "Après une action dans l'administration (suppression d'utilisateur, sauvegarde de configuration…), la page restait sur le premier onglet au lieu de revenir sur l'onglet d'origine — corrigé.",
        ],
      },
    ],
  },
  {
    entete: "v 2.0.5 — 29 mai 2026",
    categories: [
      {
        categorie: "Mes spectacles",
        items: [
          "Suppression du filtre budget — non fonctionnel, sans données de prix disponibles.",
          "L'état vide distingue maintenant « aucun artiste ni salle suivi » de « aucun concert avec ces filtres ».",
          "L'heure du concert s'affiche dans la colonne Date quand elle est disponible.",
        ],
      },
      {
        categorie: "Découverte",
        items: [
          "Suppression du filtre budget. Ajout des badges de statut (Annulé, Reporté, Complet). Bouton Actualiser intégré.",
        ],
      },
      {
        categorie: "Artistes & Salles",
        items: [
          "Confirmation requise avant la suppression d'un artiste ou d'une salle — plus de suppression accidentelle en un clic.",
          "La salle ajoutée déclenche maintenant un message de confirmation.",
        ],
      },
      {
        categorie: "Paramètres",
        items: [
          "Le toggle « Concerts disponibles uniquement » passe au vert quand il est activé.",
          "Les cases à cocher des notifications ont un style cohérent avec le reste de l'interface.",
        ],
      },
      {
        categorie: "Aide",
        items: ["Limite à 3 signalements par heure par adresse IP."],
      },
    ],
  },
  {
    entete: "v 2.0.4 — 28 mai 2026",
    categories: [
      {
        categorie: "Paramètres",
        items: [
          "Confirmation par email lors du changement d'adresse email — la nouvelle adresse est validée avant d'être appliquée.",
          "Messages de confirmation différenciés selon l'action (TOTP activé/désactivé, identifiant modifié, email confirmé).",
          "Compteur de longueur en temps réel sur le champ mot de passe.",
          'Notifications push : le bouton d\'activation est désactivé si la fréquence email est sur "Jamais".',
          "Import de préférences : résumé du nombre d'artistes et de salles importés, avec les éléments rejetés le cas échéant.",
          "Compteur de capacité restante (X/500 artistes — Y/200 salles) visible dans l'onglet Données.",
          'Suppression de compte : saisie obligatoire du mot "supprimer" pour confirmer l\'action.',
        ],
      },
      {
        categorie: "Correctifs",
        items: [
          "Notifications push : les notifications push PWA sont désormais réellement envoyées (un problème de configuration interne empêchait tout envoi depuis la mise en ligne).",
          "Badge \"Nouveau\" : le badge apparaît correctement sur l'accueil et dans Mes spectacles pendant 7 jours après la découverte d'un concert — il n'était pas affiché même pour les concerts récents.",
          'Navigation PWA : le menu "+" et la liste déroulante des onglets fonctionnent de nouveau après une navigation.',
          'Accueil : les concerts des salles suivies apparaissent désormais correctement (ils n\'étaient pas affichés malgré le badge "Nouveau").',
        ],
      },
    ],
  },
  {
    entete: "v 2.0.3 — 27 mai 2026",
    categories: [
      {
        categorie: "Correctif",
        items: [
          "Les notifications digest ne sont plus silencieusement bloquées depuis la v2.0.2.",
        ],
      },
      {
        categorie: "Administration — Tableau de bord",
        items: [
          "La prochaine date d'exécution planifiée est affichée pour chaque job.",
          "Le quota Ticketmaster affiche un avertissement visuel quand il descend sous 20 % (orange) ou 10 % (rouge).",
        ],
      },
      {
        categorie: "Administration — Logs",
        items: [
          "Les sources disponibles dans le filtre sont corrigées (web, push) et la limite est portée à 200 entrées.",
          "Boutons « Rafraîchir » et « Réinitialiser » ajoutés pour mettre à jour les logs sans recharger la page.",
        ],
      },
      {
        categorie: "Administration — Configuration",
        items: [
          "La régénération des clés VAPID affiche une confirmation avant d'invalider tous les abonnements push existants.",
          "Une aide contextuelle explique le rôle des clés VAPID et les conséquences d'une régénération.",
        ],
      },
    ],
  },
  {
    entete: "v 2.0.2 — 26 mai 2026",
    categories: [
      {
        categorie: "Accueil",
        items: [
          "Tous vos concerts à venir sont désormais affichés — la limite de 50 concerts sur la page d'accueil est supprimée.",
          "Les dates de concerts dont l'année est différente de l'année courante affichent maintenant l'année (ex. « jeu. 8 avr. 2027 »).",
        ],
      },
      {
        categorie: "Navigation",
        items: [
          "L'onglet actif de la barre de navigation mobile se met à jour correctement lors des changements de page.",
          "L'onglet Accueil ne reste plus actif en double lors de la navigation vers une autre page.",
          "La déconnexion depuis le menu latéral (desktop) redirige correctement vers la page de connexion.",
        ],
      },
      {
        categorie: "Notifications",
        items: [
          "Les préférences de notification sont désormais respectées : les concerts des salles suivies sont inclus, la désactivation par artiste est honorée, et les fréquences « temps réel » et « quotidien » fonctionnent indépendamment.",
        ],
      },
      {
        categorie: "Sources de concerts",
        items: [
          "Plus de concerts récupérés depuis Ticketmaster : les concerts publiés uniquement en anglais sont désormais inclus, et jusqu'à 200 résultats supplémentaires sont chargés à chaque cycle.",
          "Les concerts Ticketmaster sans date confirmée (« à annoncer ») ne consomment plus de quota inutilement.",
        ],
      },
    ],
  },
  {
    entete: "v 2.0.1 — 24 mai 2026",
    categories: [
      {
        categorie: "Spectacles",
        items: [
          "Les concerts Ticketmaster avec une prévente affichent désormais un badge « Prévente le JJ/MM » dans la liste de vos spectacles.",
          "Navigation instantanée entre les pages grâce à Turbo Drive : la barre de navigation reste fixe, sans rechargement complet.",
        ],
      },
      {
        categorie: "Compte",
        items: [
          "La session reste active pendant 30 jours d'inactivité — vous n'êtes plus déconnecté après 30 minutes.",
        ],
      },
    ],
  },
  {
    entete: "v 2.0.0 — 24 mai 2026",
    categories: [
      {
        categorie: "Infrastructure",
        items: [
          "Migration complète vers Java / Spring Boot — l'application ne dépend plus de Node.js.",
          "Spring Security remplace Better-Auth pour la gestion des sessions et de l'authentification.",
          "Interface serveur Thymeleaf (rendu côté serveur) en remplacement de SvelteKit.",
        ],
      },
      {
        categorie: "Administration",
        items: [
          "La section « Actualisations » affiche désormais un badge animé « En cours » pour chaque job en cours d'exécution, et se recharge automatiquement à la fin des fetches.",
          "Le bouton « Relancer les fetches » donne un retour visuel immédiat et recharge la page sans intervention manuelle.",
          "Les dates et heures s'affichent en français dans toutes les sections (jobs, logs, utilisateurs).",
        ],
      },
      {
        categorie: "Corrections",
        items: [
          "Accueil : les concerts d'artistes en résidence (plusieurs dates dans la même salle) s'affichent désormais correctement — toutes les dates sont conservées au lieu d'une seule.",
          "Accueil : certains concerts (artistes en tournée, concerts liés à une salle suivie) n'apparaissaient pas — corrigé.",
          "Import des préférences : les fichiers exportés depuis la v1 sont de nouveau acceptés.",
          "Salles : l'ajout de salles arbitraires (sans résultat de recherche) n'est plus possible, pour préserver la cohérence des données.",
          "Administration : le bouton « Copier » du lien d'invitation fonctionne désormais sur les accès en HTTP local.",
          "Administration : les liens d'invitation et de réinitialisation de mot de passe utilisent désormais l'adresse réelle du serveur.",
          "Aide : le formulaire de signalement envoie désormais les messages à la bonne adresse, même sans configuration explicite.",
          "Mes spectacles : le filtre « Mes salles » affiche désormais correctement les concerts des salles suivies.",
          "Notifications : les alertes push sont désormais envoyées immédiatement après chaque actualisation, et non plus uniquement à 8 h du matin.",
          "Découverte : déselectionner un département fonctionne de nouveau correctement.",
          "Découverte : la sélection de département est conservée lors de la navigation entre les pages de résultats.",
          "Affichage sur grands écrans (24\" et plus) : le contenu est désormais centré — la moitié droite de l'écran n'est plus laissée vide.",
        ],
      },
    ],
  },
  {
    entete: "v 1.4.0 — 23 mai 2026",
    categories: [
      {
        categorie: "Accueil",
        items: [
          "Nouveau bouton « Masquer » sur chaque groupe d'artiste pour masquer toute la tournée en un clic.",
          "Nouveau filtre « Masqués » pour retrouver et réafficher les concerts masqués.",
          "L'accueil affiche désormais un avertissement quand plus de 50 concerts sont disponibles, avec un lien vers Mes spectacles.",
        ],
      },
      {
        categorie: "Mes spectacles",
        items: [
          "Pagination par 100 concerts avec navigation entre les pages.",
          "Les mois disponibles dans le filtre sont calculés sur l'ensemble de vos concerts, pas uniquement la page en cours.",
        ],
      },
      {
        categorie: "Paramètres",
        items: [
          "La page est désormais organisée en quatre onglets : Préférences, Notifications, Compte, Données. L'onglet actif est mémorisé dans l'URL.",
        ],
      },
      {
        categorie: "Artistes",
        items: [
          "Un indicateur de chargement apparaît à côté de l'artiste après son ajout, pendant la recherche des concerts.",
        ],
      },
      {
        categorie: "Aide",
        items: [
          "La page d'aide est réorganisée avec des sections claires et des instructions pour installer l'application (iOS, Android, Desktop).",
        ],
      },
    ],
  },
  {
    entete: "v 1.3.1 — Mai 2026",
    categories: [
      {
        categorie: "Recherche d'artiste",
        items: [
          "La recherche d'artiste trouve désormais les résultats quelle que soit l'orthographe des accents (« Adèle » trouve « Adele », « Etienne Daho » trouve « Étienne Daho »).",
          "Les suggestions s'appuient maintenant sur Deezer, plus fiable depuis ce serveur.",
        ],
      },
    ],
  },
  {
    entete: "v 1.3.0 — Mai 2026",
    categories: [
      {
        categorie: "Découverte",
        items: [
          "Nouvelle page Découverte — explorez tous les concerts en base par département, sans vous limiter à vos artistes suivis.",
        ],
      },
      {
        categorie: "Notifications",
        items: [
          "Les concerts dont les billets ne sont plus disponibles n'apparaissent plus comme « Nouveaux » et ne déclenchent plus de notification.",
          "Le clic sur une notification ouvre directement les spectacles de l'artiste concerné.",
        ],
      },
      {
        categorie: "Double authentification",
        items: [
          "La double authentification TOTP est disponible dans Paramètres → Double authentification. Fonctionne avec Google Authenticator, Aegis, 1Password et toute application compatible TOTP. Elle est optionnelle pour tous les utilisateurs.",
        ],
      },
    ],
  },
  {
    entete: "v 1.2 — Avril 2026",
    categories: [
      {
        categorie: "Notifications push",
        texte:
          "Recevez des notifications directement sur votre appareil. Activez-les dans Paramètres → Notifications push.",
      },
      {
        categorie: "Application installable",
        texte:
          "Strapontin peut désormais être installé comme application sur votre appareil (Android, iOS, desktop) via le menu de votre navigateur.",
      },
      {
        categorie: "Masquer un concert",
        texte:
          "Un bouton « Masquer » est disponible sur chaque concert de l'Accueil. Le concert disparaît de la page d'accueil mais reste visible dans Mes spectacles. Pour le réafficher, utilisez le chip « Masqués ».",
      },
      {
        categorie: "Mot de passe oublié",
        texte:
          "Réinitialisez votre mot de passe depuis la page de connexion sans passer par l'admin.",
      },
    ],
  },
  {
    entete: "v 1.0 — Avril 2026",
    categories: [
      {
        categorie: null,
        texte: "Version initiale.",
      },
    ],
  },
];

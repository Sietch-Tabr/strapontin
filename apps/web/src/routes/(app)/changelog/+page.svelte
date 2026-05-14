<script lang="ts">
	import { History } from '@lucide/svelte';
</script>

<svelte:head>
	<title>Nouveautés — Strapontin</title>
</svelte:head>

<div class="p-6 lg:p-8">
	<div class="mb-8">
		<h1 class="text-2xl font-semibold text-foreground">Nouveautés</h1>
	</div>

	<div class="max-w-xl space-y-10">

		<section>
			<div class="mb-4 flex items-center gap-2 text-foreground">
				<History size={20} aria-hidden="true" />
				<h2 class="text-base font-medium">v 1.4.0 — Mai 2026</h2>
			</div>
			<div class="space-y-4 text-sm text-muted-foreground">
				<div>
					<p class="mb-1 font-medium text-foreground">Mes spectacles</p>
					<ul class="list-inside list-disc space-y-1">
						<li>La liste des spectacles est maintenant paginée (100 par page) — plus de long défilement lorsque le nombre de concerts dépasse la centaine.</li>
						<li>Les mois disponibles dans le filtre sont calculés côté serveur : ils reflètent l'ensemble de vos concerts, quelle que soit la page affichée.</li>
					</ul>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Accueil</p>
					<ul class="list-inside list-disc space-y-1">
						<li>L'accueil affiche désormais vos 50 prochains concerts. Si vous en avez davantage, un lien vous renvoie vers Mes spectacles.</li>
					</ul>
				</div>
			</div>
		</section>

		<section>
			<div class="mb-4 flex items-center gap-2 text-foreground">
				<History size={20} aria-hidden="true" />
				<h2 class="text-base font-medium">v 1.3.1 — Mai 2026</h2>
			</div>
			<div class="space-y-4 text-sm text-muted-foreground">
				<div>
					<p class="mb-1 font-medium text-foreground">Recherche d'artiste</p>
					<ul class="list-inside list-disc space-y-1">
						<li>La recherche d'artiste trouve désormais les résultats quelle que soit l'orthographe des accents (« Adèle » trouve « Adele », « Etienne Daho » trouve « Étienne Daho »).</li>
						<li>Les suggestions s'appuient maintenant sur Deezer, plus fiable depuis ce serveur.</li>
					</ul>
				</div>
			</div>
		</section>

		<section>
			<div class="mb-4 flex items-center gap-2 text-foreground">
				<History size={20} aria-hidden="true" />
				<h2 class="text-base font-medium">v 1.3.0 — Mai 2026</h2>
			</div>
			<div class="space-y-4 text-sm text-muted-foreground">
				<div>
					<p class="mb-1 font-medium text-foreground">Notifications</p>
					<ul class="list-inside list-disc space-y-1">
						<li>Les concerts dont les billets ne sont plus disponibles n'apparaissent plus comme « Nouveaux » et ne déclenchent plus de notification.</li>
						<li>Le clic sur une notification ouvre directement les spectacles de l'artiste concerné.</li>
					</ul>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Aide</p>
					<ul class="list-inside list-disc space-y-1">
						<li>La page d'aide a été réorganisée et mise à jour.</li>
					</ul>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Administration</p>
					<ul class="list-inside list-disc space-y-1">
						<li>Les logs système sont maintenant visibles dans la page d'administration : connexions, modifications de configuration et état des jobs.</li>
						<li>La page d'administration a été réorganisée en onglets : Tableau de bord, Logs, Configuration, Utilisateurs. La colonne « Dernière activité » indique la date de connexion de chaque utilisateur.</li>
					</ul>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Double authentification</p>
					<ul class="list-inside list-disc space-y-1">
						<li>La double authentification TOTP est disponible dans Paramètres → Double authentification. Fonctionne avec Google Authenticator, Authy, 1Password et toute application compatible TOTP. Elle est obligatoire pour les administrateurs.</li>
					</ul>
				</div>
			</div>
		</section>

		<section>
			<div class="mb-4 flex items-center gap-2 text-foreground">
				<History size={20} aria-hidden="true" />
				<h2 class="text-base font-medium">v 1.2.3 — Mai 2026</h2>
			</div>
			<div class="space-y-4 text-sm text-muted-foreground">
				<div>
					<p class="mb-1 font-medium text-foreground">Concerts</p>
					<ul class="list-inside list-disc space-y-1">
						<li>Correction d'un problème qui pouvait afficher le même concert en doublon lors du premier ajout d'un artiste (si Ticketmaster renvoyait deux noms de salle légèrement différents pour le même événement).</li>
					</ul>
				</div>
			</div>
		</section>

		<section>
			<div class="mb-4 flex items-center gap-2 text-foreground">
				<History size={20} aria-hidden="true" />
				<h2 class="text-base font-medium">v 1.2.2 — Mai 2026</h2>
			</div>
			<div class="space-y-4 text-sm text-muted-foreground">
				<div>
					<p class="mb-1 font-medium text-foreground">Filtres</p>
					<ul class="list-inside list-disc space-y-1">
						<li>Les deux chips « Complet » et « Annulé/Reporté » sont remplacés par un seul chip <strong class="text-foreground">« Indisponibles »</strong> sur l'accueil et Mes spectacles. Il masque les concerts complets, annulés et reportés sans date — les concerts reportés avec une nouvelle date restent toujours visibles.</li>
						<li>Dans Découverte et Mes spectacles, changer un filtre (mois, prix, catégorie, genre) applique le résultat immédiatement, sans avoir à cliquer sur un bouton.</li>
						<li>Le bouton « Filtrer » est renommé « Rechercher » et se trouve maintenant directement à côté du champ de recherche.</li>
					</ul>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Paramètres</p>
					<ul class="list-inside list-disc space-y-1">
						<li>Le curseur « rayon de recherche » a été supprimé. Le rayon est maintenant calculé automatiquement selon la taille géographique de chaque département — les grandes régions comme la Gironde ou la Corse obtiennent un rayon adapté.</li>
					</ul>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Concerts</p>
					<ul class="list-inside list-disc space-y-1">
						<li>Les recherches par département et par salle se limitent désormais aux 6 prochains mois, ce qui réduit le nombre de résultats inutiles et améliore les performances.</li>
						<li>Les artistes et salles suivis récupèrent désormais jusqu'à 200 concerts par page au lieu de 20.</li>
					</ul>
				</div>
			</div>
		</section>

		<section>
			<div class="mb-4 flex items-center gap-2 text-foreground">
				<History size={20} aria-hidden="true" />
				<h2 class="text-base font-medium">v 1.2.1 — Mai 2026</h2>
			</div>
			<div class="space-y-4 text-sm text-muted-foreground">
				<div>
					<p class="mb-1 font-medium text-foreground">Notifications email et push</p>
					<p>Les notifications email et push fonctionnent désormais correctement. La clé API Resend est lue depuis la configuration de l'application (comme les autres clés). Le marquage « déjà notifié » n'est effectué que si l'envoi a réussi.</p>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Accueil</p>
					<ul class="list-inside list-disc space-y-1">
						<li>Tous vos concerts s'affichent (la limite de 50 est supprimée).</li>
						<li>Les chips « Complet » et « Annulé/Reporté » s'affichent dès que vous suivez des artistes, même sans concert en cours.</li>
						<li>Quand les filtres actifs expliquent l'absence de résultats, un message dédié vous invite à les ajuster.</li>
					</ul>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Artistes et salles</p>
					<ul class="list-inside list-disc space-y-1">
						<li>Si vous recherchez un artiste ou une salle déjà suivie, l'application l'indique clairement au lieu d'afficher « non trouvé ».</li>
						<li>Après avoir supprimé un artiste ou une salle, un message vous rappelle que les concerts associés disparaîtront lors de la prochaine actualisation (dans moins de 2h).</li>
					</ul>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Mes spectacles</p>
					<ul class="list-inside list-disc space-y-1">
						<li>Le filtre par catégorie et par genre affiche désormais le bon message d'état vide quand aucun résultat ne correspond.</li>
						<li>Le filtre par budget fonctionne correctement pour tous les montants (la comparaison était lexicographique, ex. 9 €  était considéré supérieur à 30 €).</li>
						<li>La chip « Masqués » conserve désormais tous les autres filtres actifs au lieu de les effacer.</li>
					</ul>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Découverte</p>
					<ul class="list-inside list-disc space-y-1">
						<li>Le filtre par budget déclenche désormais le message « Aucun résultat pour ces filtres. » quand aucun concert ne correspond.</li>
						<li>La chip « Mes depts » s'affiche correctement même quand le nombre de départements sélectionnés correspond mais pas leur contenu.</li>
						<li>Le lien « Réinitialiser » apparaît maintenant aussi quand seul le tri a été modifié.</li>
					</ul>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Paramètres</p>
					<ul class="list-inside list-disc space-y-1">
						<li>L'import en mode « Fusionner » vérifie désormais que le total (existants + nouveaux) ne dépasse pas les limites (500 artistes, 200 salles) et affiche un message explicite si c'est le cas.</li>
					</ul>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Administration</p>
					<ul class="list-inside list-disc space-y-1">
						<li>Un bouton « Relancer tous les fetches » permet de déclencher manuellement l'actualisation des concerts (artistes suivis + région) sans attendre le cron 2h — utile après une remise à zéro ou un correctif.</li>
					</ul>
				</div>
			</div>
		</section>

		<section>
			<div class="mb-4 flex items-center gap-2 text-foreground">
				<History size={20} aria-hidden="true" />
				<h2 class="text-base font-medium">v 1.2 — Avril 2026</h2>
			</div>
			<div class="space-y-4 text-sm text-muted-foreground">
				<div>
					<p class="mb-1 font-medium text-foreground">Notifications push</p>
					<p>Recevez des notifications directement sur votre appareil, en même temps que vos emails de digest. Activez-les dans Paramètres → Notifications push. Fonctionne sur tous les appareils et navigateurs compatibles (Chrome, Firefox, Edge, Safari 17+).</p>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Application installable</p>
					<p>Strapontin peut désormais être installé comme application sur votre appareil (Android, iOS, desktop) via le menu de votre navigateur. L'application s'ouvre alors sans barre d'adresse, comme une application native.</p>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Masquer un concert</p>
					<p>Un bouton "Masquer" est disponible sur chaque concert de l'Accueil. Le concert disparaît de la page d'accueil mais reste visible dans Mes spectacles. Pour le réafficher, utilisez le chip "Masqués" dans Mes spectacles puis cliquez sur "Réafficher".</p>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Logo et favicon</p>
					<p>L'application dispose désormais de son logo et de sa favicône, visibles dans l'onglet du navigateur et les pages de connexion.</p>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Gestion du compte</p>
					<ul class="list-inside list-disc space-y-1">
						<li>Modification de l'adresse email depuis Paramètres → Mon compte.</li>
						<li>Suppression du compte depuis Paramètres → Zone de danger (RGPD art. 17).</li>
					</ul>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Administration</p>
					<ul class="list-inside list-disc space-y-1">
						<li>Modification du nom et de l'email d'un utilisateur depuis la page Admin.</li>
						<li>Réinitialisation du mot de passe par l'admin : envoi d'un lien de reset à l'utilisateur.</li>
						<li>Configuration des clés API depuis l'interface Admin (Ticketmaster, Resend, email de contact, URL de base) — sans accès au serveur.</li>
					</ul>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Signalement et suggestions</p>
					<p>Un formulaire de contact est disponible dans Aide pour signaler un bug, proposer une amélioration ou poser une question directement depuis l'application.</p>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Notifications email</p>
					<ul class="list-inside list-disc space-y-1">
						<li>Notifications concerts : recevez un email lorsque de nouveaux concerts correspondent à vos préférences. Trois fréquences au choix dans Paramètres : désactivées, après chaque mise à jour (toutes les 2h), ou récapitulatif quotidien à 8h.</li>
						<li>Choix du contenu des notifications : concerts de vos artistes suivis, de vos salles suivies, ou les deux (Paramètres → Notifications).</li>
						<li>Email d'invitation automatique lors de la création de compte par l'admin.</li>
					</ul>
				</div>
				<div>
					<p class="mb-1 font-medium text-foreground">Mot de passe oublié</p>
					<p>Réinitialisez votre mot de passe depuis la page de connexion sans passer par l'admin.</p>
				</div>
			</div>
		</section>

		<section>
			<div class="mb-4 flex items-center gap-2 text-foreground">
				<History size={20} aria-hidden="true" />
				<h2 class="text-base font-medium">v 1.0 — Avril 2026</h2>
			</div>
			<div class="space-y-6 text-sm text-muted-foreground">
				<p>Version initiale.</p>
			</div>
		</section>

	</div>
</div>

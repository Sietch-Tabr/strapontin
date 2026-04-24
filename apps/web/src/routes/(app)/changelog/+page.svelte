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
				<h2 class="text-base font-medium">v 1.2 — Avril 2026</h2>
			</div>
			<div class="space-y-4 text-sm text-muted-foreground">
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

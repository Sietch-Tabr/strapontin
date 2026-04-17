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
				<h2 class="text-base font-medium">v 0.90 — Avril 2026</h2>
			</div>
			<div class="space-y-6 text-sm text-muted-foreground">

				<div>
					<p class="mb-1 font-medium text-foreground">Thème sombre et mode automatique</p>
					<p>
						L'application s'adapte désormais à votre préférence système (clair, sombre, ou
						automatique). Le thème est mémorisé entre appareils.
					</p>
				</div>

				<div>
					<p class="mb-1 font-medium text-foreground">Export et import de préférences</p>
					<p>
						Exportez vos artistes, salles et configuration en JSON depuis les paramètres.
						Réimportez-les en mode fusionner ou écraser, avec possibilité d'inclure votre
						configuration.
					</p>
				</div>

				<div>
					<p class="mb-1 font-medium text-foreground">Noms de salles corrigés</p>
					<p>
						Les noms de salles retournés en tout-majuscule par Ticketmaster sont désormais
						convertis en casse lisible (ex. « Zénith de Paris » au lieu de « ZENITH DE PARIS »).
					</p>
				</div>

				<div>
					<p class="mb-1 font-medium text-foreground">Découverte par département</p>
					<p>
						La page Découverte affiche une bannière lorsque vous consultez un département hors de vos
						préférences, avec l'heure de la dernière actualisation et un bouton pour lancer un fetch
						immédiat.
					</p>
				</div>

				<div>
					<p class="mb-1 font-medium text-foreground">Connexion par identifiant</p>
					<p>
						Le login se fait maintenant par identifiant (insensible à la casse) plutôt que par
						email. L'email reste utilisé pour la récupération de compte.
					</p>
				</div>

				<div>
					<p class="mb-1 font-medium text-foreground">Accessibilité et navigation clavier</p>
					<p>
						Navigation entièrement accessible au clavier : lien d'évitement, focus piégé dans le
						drawer mobile, labels ARIA sur tous les éléments interactifs.
					</p>
				</div>

				<div>
					<p class="mb-1 font-medium text-foreground">Accueil — tournées</p>
					<p>
						Si un artiste a plusieurs concerts à venir, un badge « Tournée · N dates » s'affiche
						sur la page d'accueil. Chaque date conserve son propre lien billetterie.
					</p>
				</div>

				<div>
					<p class="mb-1 font-medium text-foreground">Sécurité</p>
					<p>
						Limitation à 500 artistes et 200 salles par compte. Bornage des entrées texte.
						Rate limiting sur la page de connexion (5 tentatives / 15 min).
					</p>
				</div>

			</div>
		</section>

	</div>
</div>

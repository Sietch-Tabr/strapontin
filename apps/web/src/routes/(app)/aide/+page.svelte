<script lang="ts">
	import { BookOpen, Shield, Scale } from '@lucide/svelte';
</script>

<svelte:head>
	<title>Aide — Strapontin</title>
</svelte:head>

<div class="p-6 lg:p-8">
	<div class="mb-8">
		<h1 class="text-2xl font-semibold text-foreground">Aide et informations</h1>
	</div>

	<div class="max-w-xl space-y-10">

		<!-- Guide d'utilisation -->
		<section>
			<div class="mb-4 flex items-center gap-2 text-foreground">
				<BookOpen size={20} aria-hidden="true" />
				<h2 class="text-base font-medium">Guide d'utilisation</h2>
			</div>
			<div class="space-y-4 text-sm text-muted-foreground">
				<p>
					Strapontin vous permet de suivre les concerts de vos artistes favoris et des salles de
					spectacle de votre choix, en France entière.
				</p>

				<div>
					<p class="mb-1 font-medium text-foreground">Mes artistes et Mes salles</p>
					<p>
						Recherchez et ajoutez les artistes et salles que vous souhaitez suivre. Les concerts
						correspondants sont récupérés automatiquement et mis à jour toutes les 2 heures.
					</p>
				</div>

				<div>
					<p class="mb-1 font-medium text-foreground">Mes spectacles</p>
					<p>
						Liste des prochains concerts correspondant à vos artistes et salles suivis. Filtrez par
						classification, genre, mois ou budget, et triez par date ou nouveauté.
					</p>
				</div>

				<div>
					<p class="mb-1 font-medium text-foreground">Découverte</p>
					<p>
						Tous les concerts référencés en base, pour explorer au-delà de vos préférences.
						Filtrez par département et catégorie.
					</p>
				</div>

				<div>
					<p class="mb-1 font-medium text-foreground">Actualisation</p>
					<p>
						Les données sont mises à jour automatiquement depuis Ticketmaster et Eventim.
						L'ajout d'un nouvel artiste ou d'une salle déclenche une actualisation immédiate.
					</p>
				</div>

				<div>
					<p class="mb-1 font-medium text-foreground">Paramètres</p>
					<p>
						Configurez vos départements de recherche, votre rayon et activez le filtre des billets
						indisponibles. Choisissez le thème d'affichage (Automatique suit les préférences de votre
						système, Clair ou Sombre sont fixes) — ce choix se synchronise sur tous vos appareils.
						Exportez et importez vos préférences au format JSON.
					</p>
				</div>
			</div>
		</section>

		<!-- RGPD -->
		<section>
			<div class="mb-4 flex items-center gap-2 text-foreground">
				<Shield size={20} aria-hidden="true" />
				<h2 class="text-base font-medium">RGPD — Données personnelles</h2>
			</div>
			<p class="mb-4 text-xs text-muted-foreground">
				Règlement Général sur la Protection des Données
			</p>
			<div class="space-y-4 text-sm text-muted-foreground">
				<div>
					<p class="mb-1 font-medium text-foreground">Données collectées</p>
					<ul class="list-inside list-disc space-y-1">
						<li>Informations de compte : nom d'affichage, identifiant, adresse email</li>
						<li>Préférences : artistes et salles suivis, départements, rayon de recherche</li>
						<li>Sessions de connexion</li>
					</ul>
				</div>

				<div>
					<p class="mb-1 font-medium text-foreground">Finalité</p>
					<p>
						Ces données sont utilisées exclusivement pour le fonctionnement de l'application :
						authentification, personnalisation des résultats et envoi d'alertes email si activées.
						Elles ne sont partagées avec aucun tiers.
					</p>
				</div>

				<div>
					<p class="mb-1 font-medium text-foreground">Hébergement et conservation</p>
					<p>
						Les données sont stockées sur un serveur privé auto-hébergé en France et conservées
						tant que votre compte est actif.
					</p>
				</div>

				<div>
					<p class="mb-1 font-medium text-foreground">Vos droits</p>
					<p>
						Vous pouvez exporter l'intégralité de vos données depuis
						<a
							href="/parametres"
							class="underline underline-offset-4 hover:text-foreground"
						>Paramètres → Export</a>.
						Pour demander la modification ou la suppression de votre compte, contactez
						l'administrateur de l'instance.
					</p>
				</div>
			</div>
		</section>

		<!-- Licence -->
		<section>
			<div class="mb-4 flex items-center gap-2 text-foreground">
				<Scale size={20} aria-hidden="true" />
				<h2 class="text-base font-medium">Licence</h2>
			</div>
			<div class="space-y-3 text-sm text-muted-foreground">
				<p>
					Strapontin est un logiciel libre distribué sous licence
					<span class="font-medium text-foreground">MIT</span>.
				</p>
				<pre class="rounded-md bg-muted px-4 py-3 text-xs leading-relaxed whitespace-pre-wrap">MIT License

Copyright (c) 2026 Kwaite

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.</pre>
			</div>
		</section>

	</div>
</div>

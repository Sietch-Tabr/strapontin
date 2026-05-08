<script lang="ts">
	import { tick } from 'svelte';
	import { BookOpen, Bell, Settings, Compass, Shield, Scale, MessageSquare, Code } from '@lucide/svelte';
	import { enhance } from '$app/forms';
	import { toast } from 'svelte-sonner';
	import type { PageData, ActionData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();

	let dialogOuvert = $state(false);
	let boutonOuverture = $state<HTMLButtonElement | undefined>(undefined);
	let typeSignalement = $state('bug');
	let envoi = $state(false);

	const CANEVAS: Record<string, string> = {
		bug: 'Décrivez le problème\n\nÉtapes pour reproduire :\n1. \n2. \n\nComportement attendu :\n\nComportement observé :',
		suggestion: "Décrivez votre suggestion\n\nContexte (pourquoi c'est utile) :\n\nComportement souhaité :",
		question: 'Votre question',
	};

	let messageSignalement = $state(CANEVAS['bug']!);

	function ouvrirDialog() {
		dialogOuvert = true;
		tick().then(() => {
			document.getElementById('type-signalement')?.focus();
		});
	}

	function fermerDialog() {
		dialogOuvert = false;
		tick().then(() => boutonOuverture?.focus());
	}

	function changerType(type: string) {
		typeSignalement = type;
		messageSignalement = CANEVAS[type] ?? '';
	}

	function piegerFocus(e: KeyboardEvent) {
		if (e.key === 'Escape') { fermerDialog(); return; }
		if (e.key !== 'Tab') return;
		const dialog = document.getElementById('dialog-signalement');
		if (!dialog) return;
		const focusables = Array.from(
			dialog.querySelectorAll<HTMLElement>('button,input,select,textarea,[tabindex]')
		).filter((el) => !el.hasAttribute('disabled') && el.tabIndex >= 0);
		if (focusables.length === 0) return;
		const premier = focusables[0]!;
		const dernier = focusables[focusables.length - 1]!;
		if (e.shiftKey && document.activeElement === premier) {
			e.preventDefault(); dernier.focus();
		} else if (!e.shiftKey && document.activeElement === dernier) {
			e.preventDefault(); premier.focus();
		}
	}

	$effect(() => {
		if (form?.succes) {
			messageSignalement = CANEVAS['bug']!;
			typeSignalement = 'bug';
			fermerDialog();
		}
	});
</script>

<svelte:head>
	<title>Aide — Strapontin</title>
</svelte:head>

<div class="p-6 lg:p-8">
	<div class="mb-8">
		<h1 class="text-2xl font-semibold text-foreground">Aide</h1>
	</div>

	<div class="max-w-2xl space-y-10">

		<!-- Groupe : Usage courant -->
		<section aria-labelledby="titre-usage">
			<p id="titre-usage" class="mb-4 text-xs font-medium uppercase tracking-widest text-muted-foreground">
				Usage courant
			</p>
			<div class="grid gap-3 sm:grid-cols-2">

				<div class="rounded-lg border border-border bg-card p-4">
					<div class="mb-2 flex items-center gap-2">
						<BookOpen size={16} class="text-muted-foreground" aria-hidden="true" />
						<h2 class="text-sm font-medium text-foreground">Artistes &amp; salles</h2>
					</div>
					<p class="text-sm text-muted-foreground">
						Recherchez et ajoutez les artistes et salles à suivre. Les concerts sont récupérés automatiquement et mis à jour toutes les 2 heures depuis Ticketmaster et Eventim.
					</p>
				</div>

				<div class="rounded-lg border border-border bg-card p-4">
					<div class="mb-2 flex items-center gap-2">
						<Compass size={16} class="text-muted-foreground" aria-hidden="true" />
						<h2 class="text-sm font-medium text-foreground">Mes spectacles &amp; Découverte</h2>
					</div>
					<p class="text-sm text-muted-foreground">
						<strong class="font-medium text-foreground">Mes spectacles</strong> : vos prochains concerts, filtrables par catégorie, mois ou budget.
						<strong class="font-medium text-foreground">Découverte</strong> : tous les concerts en base, filtrables par département.
					</p>
				</div>

				<div class="rounded-lg border border-border bg-card p-4">
					<div class="mb-2 flex items-center gap-2">
						<Bell size={16} class="text-muted-foreground" aria-hidden="true" />
						<h2 class="text-sm font-medium text-foreground">Notifications</h2>
					</div>
					<p class="text-sm text-muted-foreground">
						Recevez un email ou une notification push lorsque de nouveaux concerts correspondent à vos préférences.
						Trois fréquences disponibles : désactivées, à chaque mise à jour (toutes les 2h) ou en récapitulatif quotidien à 8h.
						Désactivées par défaut — à activer dans Paramètres.
					</p>
				</div>

				<div class="rounded-lg border border-border bg-card p-4">
					<div class="mb-2 flex items-center gap-2">
						<Settings size={16} class="text-muted-foreground" aria-hidden="true" />
						<h2 class="text-sm font-medium text-foreground">Paramètres</h2>
					</div>
					<p class="text-sm text-muted-foreground">
						Configurez vos départements de recherche, le filtre des billets indisponibles et votre thème (Automatique, Clair ou Sombre — synchronisé sur tous vos appareils).
						Exportez et importez vos préférences au format JSON.
					</p>
				</div>

			</div>
		</section>

		<!-- Groupe : Informations -->
		<section aria-labelledby="titre-infos">
			<p id="titre-infos" class="mb-4 text-xs font-medium uppercase tracking-widest text-muted-foreground">
				Informations
			</p>
			<div class="grid gap-3 sm:grid-cols-2">

				<div class="rounded-lg border border-border bg-card p-4">
					<div class="mb-2 flex items-center gap-2">
						<Shield size={16} class="text-muted-foreground" aria-hidden="true" />
						<h2 class="text-sm font-medium text-foreground">Données personnelles</h2>
					</div>
					<div class="space-y-2 text-sm text-muted-foreground">
						<p>Données collectées : nom, identifiant, email, préférences et sessions. Utilisées exclusivement pour le fonctionnement de l'application, jamais partagées.</p>
						<p>Hébergement sur serveur privé auto-hébergé en France. Données conservées tant que le compte est actif.</p>
						<p>
							Export intégral depuis
							<a href="/parametres" class="underline underline-offset-4 hover:text-foreground">Paramètres → Export</a>.
							Suppression de compte sur demande à l'administrateur.
						</p>
					</div>
				</div>

				<div class="rounded-lg border border-border bg-card p-4">
					<div class="mb-2 flex items-center gap-2">
						<Scale size={16} class="text-muted-foreground" aria-hidden="true" />
						<h2 class="text-sm font-medium text-foreground">Licence</h2>
					</div>
					<p class="text-sm text-muted-foreground">
						Strapontin est distribué sous licence <span class="font-medium text-foreground">MIT</span> — logiciel libre, utilisation, modification et redistribution autorisées sous réserve de conserver la mention de copyright.
					</p>
				</div>

				<div class="rounded-lg border border-border bg-card p-4">
					<div class="mb-2 flex items-center gap-2">
						<Code size={16} class="text-muted-foreground" aria-hidden="true" />
						<h2 class="text-sm font-medium text-foreground">Code source</h2>
					</div>
					<p class="text-sm text-muted-foreground">
						Le code source est disponible sur
						<a
							href="https://codeberg.org/Kwaite/strapontin"
							target="_blank"
							rel="noopener noreferrer"
							class="underline underline-offset-4 hover:text-foreground"
						>Codeberg</a>.
					</p>
				</div>

				<div class="rounded-lg border border-border bg-card p-4">
					<div class="mb-2 flex items-center gap-2">
						<MessageSquare size={16} class="text-muted-foreground" aria-hidden="true" />
						<h2 class="text-sm font-medium text-foreground">Signaler un problème</h2>
					</div>
					{#if data.resendDisponible}
						<p class="mb-3 text-sm text-muted-foreground">
							Un bug, une suggestion, une question ?
						</p>
						<button
							bind:this={boutonOuverture}
							type="button"
							onclick={ouvrirDialog}
							class="rounded-md border border-border px-3 py-1.5 text-sm font-medium text-foreground transition-colors hover:bg-accent"
						>
							Ouvrir le formulaire
						</button>
					{:else}
						<p class="text-sm text-muted-foreground">
							Écrivez à
							<a href="mailto:admin@strapontin.kwaite.fr" class="underline underline-offset-4 hover:text-foreground">
								admin@strapontin.kwaite.fr
							</a>.
						</p>
					{/if}
				</div>

			</div>
		</section>

		<p class="text-sm text-muted-foreground">
			<a href="/changelog" class="underline underline-offset-4 hover:text-foreground">
				Voir les nouveautés →
			</a>
		</p>

	</div>
</div>

{#if dialogOuvert}
	<div
		class="fixed inset-0 z-50 flex items-center justify-center p-4"
		role="dialog"
		aria-modal="true"
		aria-labelledby="titre-signalement"
		tabindex="-1"
		onkeydown={piegerFocus}
	>
		<button
			type="button"
			class="absolute inset-0 bg-black/50"
			onclick={fermerDialog}
			aria-label="Fermer"
			tabindex="-1"
		></button>

		<div
			id="dialog-signalement"
			class="relative w-full max-w-lg rounded-xl bg-background p-6 shadow-xl"
		>
			<div class="mb-5 flex items-center justify-between">
				<h2 id="titre-signalement" class="text-base font-semibold text-foreground">
					Signaler un problème ou une suggestion
				</h2>
				<button
					type="button"
					onclick={fermerDialog}
					aria-label="Fermer"
					class="rounded-md p-1 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
				>
					✕
				</button>
			</div>

			<form
				method="POST"
				action="?/signalerBug"
				class="space-y-4"
				use:enhance={() => {
					envoi = true;
					return async ({ result, update }) => {
						if (result.type === 'success') toast.success('Message envoyé');
						else if (result.type === 'failure') toast.error((result.data?.erreur as string | undefined) ?? 'Une erreur est survenue');
						await update();
						envoi = false;
					};
				}}
			>
				<div>
					<label for="type-signalement" class="mb-1.5 block text-sm font-medium text-foreground">
						Type
					</label>
					<select
						id="type-signalement"
						name="type"
						value={typeSignalement}
						onchange={(e) => changerType((e.currentTarget as HTMLSelectElement).value)}
						class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-foreground/20"
					>
						<option value="bug">Bug</option>
						<option value="suggestion">Amélioration</option>
						<option value="question">Question</option>
					</select>
				</div>

				<div>
					<label for="message-signalement" class="mb-1.5 block text-sm font-medium text-foreground">
						Message <span class="text-muted-foreground">(2000 caractères max)</span>
					</label>
					<textarea
						id="message-signalement"
						name="message"
						bind:value={messageSignalement}
						rows={8}
						maxlength={2000}
						class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-foreground/20"
					></textarea>
				</div>

				<div class="flex gap-3">
					<button
						type="submit"
						disabled={envoi || !messageSignalement.trim()}
						class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
					>
						{envoi ? 'Envoi…' : 'Envoyer'}
					</button>
					<button
						type="button"
						onclick={fermerDialog}
						class="rounded-md px-4 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-accent"
					>
						Annuler
					</button>
				</div>
			</form>
		</div>
	</div>
{/if}

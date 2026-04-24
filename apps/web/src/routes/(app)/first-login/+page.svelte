<script lang="ts">
	import { enhance } from '$app/forms';
	import type { ActionData } from './$types';

	let { form }: { form: ActionData } = $props();

	let nom = $state('');
	let identifiant = $state('');
	let motDePasseActuel = $state('');
	let motDePasseNouveau = $state('');
	let motDePasseConfirmation = $state('');
	let enCours = $state(false);
</script>

<div class="flex min-h-screen items-center justify-center bg-background">
	<div class="w-full max-w-sm space-y-6 p-8">
		<div class="space-y-3 text-center">
			<img src="/logo.png" alt="Strapontin" class="mx-auto h-24 w-24 rounded-2xl object-cover" />
			<div class="space-y-1">
				<h1 class="text-2xl font-semibold tracking-tight text-foreground">Bienvenue sur Strapontin</h1>
				<p class="text-sm text-muted-foreground">
					Choisissez votre identifiant et un nouveau mot de passe avant de continuer.
				</p>
			</div>
		</div>

		{#if form?.erreur}
			<p class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
				{form.erreur}
			</p>
		{/if}

		<form
			method="POST"
			class="space-y-4"
			use:enhance={() => {
				enCours = true;
				return async ({ update }) => {
					await update();
					enCours = false;
				};
			}}
		>
			<div class="space-y-2">
				<label for="nom" class="text-sm font-medium text-foreground">Nom d'affichage</label>
				<input
					id="nom"
					type="text"
					name="nom"
					bind:value={nom}
					required
					maxlength="100"
					autocomplete="name"
					class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
				/>
			</div>

			<div class="space-y-2">
				<label for="identifiant" class="text-sm font-medium text-foreground">Identifiant</label>
				<input
					id="identifiant"
					type="text"
					name="identifiant"
					bind:value={identifiant}
					required
					minlength="3"
					maxlength="30"
					pattern="[a-z0-9_]+"
					autocomplete="username"
					placeholder="lettres, chiffres et _ uniquement"
					class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
				/>
			</div>

			<div class="space-y-2">
				<label for="mot-de-passe-actuel" class="text-sm font-medium text-foreground">
					Mot de passe temporaire
				</label>
				<input
					id="mot-de-passe-actuel"
					type="password"
					name="motDePasseActuel"
					bind:value={motDePasseActuel}
					required
					autocomplete="current-password"
					class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
				/>
			</div>

			<div class="space-y-2">
				<label for="mot-de-passe-nouveau" class="text-sm font-medium text-foreground">
					Nouveau mot de passe
				</label>
				<input
					id="mot-de-passe-nouveau"
					type="password"
					name="motDePasseNouveau"
					bind:value={motDePasseNouveau}
					required
					minlength="12"
					autocomplete="new-password"
					placeholder="12 caractères minimum"
					class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
				/>
			</div>

			<div class="space-y-2">
				<label for="mot-de-passe-confirmation" class="text-sm font-medium text-foreground">
					Confirmer le mot de passe
				</label>
				<input
					id="mot-de-passe-confirmation"
					type="password"
					name="motDePasseConfirmation"
					bind:value={motDePasseConfirmation}
					required
					minlength="12"
					autocomplete="new-password"
					class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
				/>
			</div>

			<button
				type="submit"
				disabled={enCours}
				class="w-full rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
			>
				{enCours ? 'Enregistrement…' : 'Valider et accéder à l\'application'}
			</button>
		</form>
	</div>
</div>

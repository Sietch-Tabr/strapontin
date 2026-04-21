<script lang="ts">
	import { enhance } from '$app/forms';
	import type { PageData, ActionData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();

	let chargement = $state(false);
</script>

<svelte:head>
	<title>Nouveau mot de passe — Strapontin</title>
</svelte:head>

<div class="flex min-h-screen items-center justify-center bg-background">
	<div class="w-full max-w-sm space-y-6 p-8">
		<div class="space-y-1 text-center">
			<h1 class="text-2xl font-semibold tracking-tight">Nouveau mot de passe</h1>
			<p class="text-sm text-muted-foreground">Choisissez un mot de passe d'au moins 12 caractères.</p>
		</div>

		<form
			method="POST"
			class="space-y-4"
			use:enhance={() => {
				chargement = true;
				return async ({ update }) => {
					await update();
					chargement = false;
				};
			}}
		>
			<input type="hidden" name="token" value={data.token} />

			{#if form?.erreur}
				<p role="alert" class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
					{form.erreur}
				</p>
			{/if}

			<div class="space-y-2">
				<label for="motDePasse" class="text-sm font-medium">Nouveau mot de passe</label>
				<input
					id="motDePasse"
					type="password"
					name="motDePasse"
					required
					minlength="12"
					autocomplete="new-password"
					class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
				/>
			</div>

			<div class="space-y-2">
				<label for="confirmation" class="text-sm font-medium">Confirmation</label>
				<input
					id="confirmation"
					type="password"
					name="confirmation"
					required
					minlength="12"
					autocomplete="new-password"
					class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
				/>
			</div>

			<button
				type="submit"
				disabled={chargement}
				class="w-full rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
			>
				{chargement ? 'Enregistrement…' : 'Enregistrer le mot de passe'}
			</button>
		</form>
	</div>
</div>

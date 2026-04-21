<script lang="ts">
	import { enhance } from '$app/forms';
	import type { ActionData } from './$types';

	let { form }: { form: ActionData } = $props();

	let chargement = $state(false);
</script>

<svelte:head>
	<title>Mot de passe oublié — Strapontin</title>
</svelte:head>

<div class="flex min-h-screen items-center justify-center bg-background">
	<div class="w-full max-w-sm space-y-6 p-8">
		<div class="space-y-1 text-center">
			<h1 class="text-2xl font-semibold tracking-tight">Mot de passe oublié</h1>
			<p class="text-sm text-muted-foreground">
				Saisissez votre adresse email pour recevoir un lien de réinitialisation.
			</p>
		</div>

		{#if form?.succes}
			<p class="rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
				Si un compte existe pour cette adresse, un email a été envoyé.
			</p>
		{:else}
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
				{#if form?.erreur}
					<p role="alert" class="text-sm text-destructive">{form.erreur}</p>
				{/if}

				<div class="space-y-2">
					<label for="email" class="text-sm font-medium">Adresse email</label>
					<input
						id="email"
						type="email"
						name="email"
						required
						autocomplete="email"
						class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
					/>
				</div>

				<button
					type="submit"
					disabled={chargement}
					class="w-full rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
				>
					{chargement ? 'Envoi…' : 'Envoyer le lien'}
				</button>
			</form>
		{/if}

		<p class="text-center text-sm text-muted-foreground">
			<a href="/login" class="underline underline-offset-4 hover:text-foreground">
				Retour à la connexion
			</a>
		</p>
	</div>
</div>

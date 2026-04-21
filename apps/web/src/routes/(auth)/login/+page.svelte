<script lang="ts">
	import { signIn } from '$lib/auth-client';
	import { goto } from '$app/navigation';
	import { page } from '$app/stores';

	let identifiant = $state('');
	let motDePasse = $state('');
	let erreur = $state('');
	let chargement = $state(false);

	const resetOk = $derived($page.url.searchParams.get('reset') === 'ok');

	async function connexion(e: SubmitEvent) {
		e.preventDefault();
		chargement = true;
		erreur = '';

		const resultat = await signIn.username({ username: identifiant, password: motDePasse });

		if (resultat.error) {
			erreur = 'Identifiant ou mot de passe incorrect.';
			chargement = false;
		} else {
			goto('/');
		}
	}
</script>

<div class="flex min-h-screen items-center justify-center bg-background">
	<div class="w-full max-w-sm space-y-6 p-8">
		<div class="space-y-1 text-center">
			<h1 class="text-2xl font-semibold tracking-tight">Strapontin</h1>
			<p class="text-sm text-muted-foreground">Connectez-vous pour accéder à vos concerts</p>
		</div>

		{#if resetOk}
			<p class="rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
				Mot de passe mis à jour. Vous pouvez vous connecter.
			</p>
		{/if}

		<form onsubmit={connexion} class="space-y-4">
			<div class="space-y-2">
				<label for="identifiant" class="text-sm font-medium">Identifiant</label>
				<input
					id="identifiant"
					type="text"
					bind:value={identifiant}
					required
					autocomplete="username"
					class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
				/>
			</div>

			<div class="space-y-2">
				<div class="flex items-center justify-between">
					<label for="mot-de-passe" class="text-sm font-medium">Mot de passe</label>
					<a href="/forgot-password" class="text-xs text-muted-foreground underline underline-offset-4 hover:text-foreground">
						Mot de passe oublié ?
					</a>
				</div>
				<input
					id="mot-de-passe"
					type="password"
					bind:value={motDePasse}
					required
					autocomplete="current-password"
					class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
				/>
			</div>

			{#if erreur}
				<p role="alert" class="text-sm text-destructive">{erreur}</p>
			{/if}

			<button
				type="submit"
				disabled={chargement}
				class="w-full rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
			>
				{chargement ? 'Connexion…' : 'Se connecter'}
			</button>
		</form>
	</div>
</div>

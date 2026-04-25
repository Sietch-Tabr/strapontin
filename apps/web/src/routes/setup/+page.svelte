<script lang="ts">
	import { enhance } from '$app/forms';
	import type { ActionData, PageData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();

	let chargement = $state(false);
	let formValide = $state(false);

	function verifierValidite(e: Event) {
		formValide = (e.currentTarget as HTMLFormElement).checkValidity();
	}
</script>

<svelte:head>
	<title>Initialisation — Strapontin</title>
</svelte:head>

<div class="flex min-h-screen items-center justify-center bg-background">
	<div class="w-full max-w-sm space-y-6 p-8">
		<div class="space-y-3 text-center">
			<img src="/logo.png" alt="Strapontin" class="mx-auto h-24 w-24 rounded-2xl object-cover" />
			<div class="space-y-1">
				<h1 class="text-2xl font-semibold tracking-tight">Bienvenue sur Strapontin</h1>
				<p class="text-sm text-muted-foreground">Créez le premier compte administrateur</p>
			</div>
		</div>

		<form
			method="POST"
			use:enhance={() => {
				chargement = true;
				return async ({ update }) => {
					chargement = false;
					await update();
				};
			}}
			oninput={verifierValidite}
			class="space-y-4"
		>
			<div class="space-y-2">
				<label for="nom" class="text-sm font-medium">Nom d'affichage</label>
				<input
					id="nom"
					name="nom"
					type="text"
					required
					autocomplete="name"
					class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
				/>
			</div>

			<div class="space-y-2">
				<label for="identifiant" class="text-sm font-medium">Identifiant de connexion</label>
				<input
					id="identifiant"
					name="identifiant"
					type="text"
					required
					minlength="3"
					maxlength="30"
					pattern="[a-zA-Z0-9_]+"
					autocomplete="username"
					class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
				/>
				<p class="text-xs text-muted-foreground">3 à 30 caractères, lettres, chiffres et _ uniquement. Insensible à la casse.</p>
			</div>

			<div class="space-y-2">
				<label for="email" class="text-sm font-medium">Email (pour la récupération de compte)</label>
				<input
					id="email"
					name="email"
					type="email"
					required
					autocomplete="email"
					class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
				/>
			</div>

			<div class="space-y-2">
				<label for="baseUrl" class="text-sm font-medium">URL publique de l'application</label>
				<input
					id="baseUrl"
					name="baseUrl"
					type="url"
					required
					value={data.urlBase}
					autocomplete="url"
					class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
				/>
				<p class="text-xs text-muted-foreground">Utilisée pour les liens dans les emails.</p>
			</div>

			<div class="space-y-2">
				<label for="motDePasse" class="text-sm font-medium">Mot de passe</label>
				<input
					id="motDePasse"
					name="motDePasse"
					type="password"
					required
					minlength="12"
					autocomplete="new-password"
					placeholder="12 caractères minimum"
					class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
				/>
			</div>

			{#if form?.erreur}
				<p class="text-sm text-destructive">{form.erreur}</p>
			{/if}

			<button
				type="submit"
				disabled={chargement || !formValide}
				class="w-full rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
			>
				{chargement ? 'Création…' : 'Créer le compte admin'}
			</button>
		</form>
	</div>
</div>

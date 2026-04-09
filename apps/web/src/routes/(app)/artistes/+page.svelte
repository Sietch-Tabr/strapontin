<script lang="ts">
	import { enhance } from '$app/forms';
	import { Music, X, Plus } from '@lucide/svelte';
	import type { PageData, ActionData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();

	let nomArtiste = $state('');
	let ajoutEnCours = $state(false);
</script>

<div class="p-6 lg:p-8">
	<div class="mb-8">
		<h1 class="text-2xl font-semibold text-foreground">Mes artistes</h1>
		<p class="mt-1 text-sm text-muted-foreground">
			{data.artistes.length} artiste{data.artistes.length !== 1 ? 's' : ''} suivi{data.artistes.length !== 1 ? 's' : ''}
		</p>
	</div>

	<!-- Formulaire d'ajout -->
	<form
		method="POST"
		action="?/ajouter"
		class="mb-8 flex gap-2"
		use:enhance={() => {
			ajoutEnCours = true;
			return async ({ update }) => {
				await update();
				ajoutEnCours = false;
				nomArtiste = '';
			};
		}}
	>
		<input
			type="text"
			name="nom"
			bind:value={nomArtiste}
			placeholder="Nom de l'artiste…"
			maxlength="200"
			autocomplete="off"
			class="flex-1 rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
		/>
		<button
			type="submit"
			disabled={ajoutEnCours || !nomArtiste.trim()}
			class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
		>
			<Plus size={16} />
			Ajouter
		</button>
	</form>

	{#if form?.erreur}
		<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
			{form.erreur}
		</p>
	{/if}

	{#if data.artistes.length === 0}
		<div class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-20 text-center">
			<Music size={40} class="mb-4 text-muted-foreground/50" />
			<p class="mb-1 text-base font-medium text-foreground">Aucun artiste suivi.</p>
			<p class="text-sm text-muted-foreground">
				Ajoutez un artiste pour recevoir des alertes concerts.
			</p>
		</div>
	{:else}
		<ul class="space-y-2">
			{#each data.artistes as artiste (artiste.artisteNormalise)}
				<li class="flex items-center justify-between rounded-xl border border-border bg-card px-4 py-3">
					<span class="font-medium text-card-foreground">{artiste.artisteAffiche}</span>
					<form method="POST" action="?/supprimer" use:enhance>
						<input type="hidden" name="artisteNormalise" value={artiste.artisteNormalise} />
						<button
							type="submit"
							class="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive"
							aria-label="Supprimer {artiste.artisteAffiche}"
						>
							<X size={16} />
						</button>
					</form>
				</li>
			{/each}
		</ul>
	{/if}
</div>

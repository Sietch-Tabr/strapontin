<script lang="ts">
	import { enhance } from '$app/forms';
	import { Music, X, Plus, Search } from '@lucide/svelte';
	import type { PageData, ActionData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();

	let termeRecherche = $state<string>(data.recherche);
</script>

<div class="p-6 lg:p-8">
	<div class="mb-8">
		<h1 class="text-2xl font-semibold text-foreground">Mes artistes</h1>
		<p class="mt-1 text-sm text-muted-foreground">
			{data.artistes.length} artiste{data.artistes.length !== 1 ? 's' : ''} suivi{data.artistes.length !== 1 ? 's' : ''}
		</p>
	</div>

	<!-- Recherche d'artiste -->
	<form method="GET" class="mb-3 flex gap-2">
		<input
			type="text"
			name="q"
			bind:value={termeRecherche}
			placeholder="Rechercher un artiste…"
			minlength="2"
			autocomplete="off"
			class="flex-1 rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
		/>
		<button
			type="submit"
			disabled={!termeRecherche || termeRecherche.trim().length < 2}
			class="flex items-center gap-2 rounded-md border border-border px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent disabled:opacity-50"
		>
			<Search size={16} />
			Rechercher
		</button>
	</form>

	<p class="mb-6 text-xs text-muted-foreground">
		La recherche propose des artistes déjà en base puis des suggestions MusicBrainz pour s'assurer de la bonne orthographe.
	</p>

	{#if form?.erreur}
		<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
			{form.erreur}
		</p>
	{/if}

	<!-- Résultats de recherche -->
	{#if data.suggestions.length > 0}
		<section class="mb-8">
			<h2 class="mb-3 text-sm font-medium text-muted-foreground">
				Résultats pour « {data.recherche} »
			</h2>
			<ul class="space-y-2">
				{#each data.suggestions as suggestion (suggestion.nom)}
					<li class="flex items-center justify-between rounded-xl border border-border bg-card px-4 py-3">
						<div>
							<p class="font-medium text-card-foreground">{suggestion.nom}</p>
							{#if suggestion.pays}
								<p class="text-xs text-muted-foreground">{suggestion.pays}</p>
							{/if}
						</div>
						<form method="POST" action="?/ajouter" use:enhance>
							<input type="hidden" name="nom" value={suggestion.nom} />
							<button
								type="submit"
								class="flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
							>
								<Plus size={14} />
								Suivre
							</button>
						</form>
					</li>
				{/each}
			</ul>
		</section>
	{:else if data.recherche.length >= 2}
		<!-- Aucun résultat — proposer d'ajouter directement -->
		<div class="mb-8 rounded-xl border border-dashed border-border p-4">
			<p class="mb-3 text-sm text-muted-foreground">
				Aucun résultat pour « {data.recherche} ».
			</p>
			<form method="POST" action="?/ajouter" use:enhance>
				<input type="hidden" name="nom" value={data.recherche} />
				<button
					type="submit"
					class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90"
				>
					<Plus size={16} />
					Ajouter « {data.recherche} » quand même
				</button>
			</form>
		</div>
	{/if}

	<!-- Artistes suivis -->
	{#if data.artistes.length === 0}
		<div class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-20 text-center">
			<Music size={40} class="mb-4 text-muted-foreground/50" />
			<p class="mb-1 text-base font-medium text-foreground">Aucun artiste suivi.</p>
			<p class="text-sm text-muted-foreground">
				Recherchez un artiste ci-dessus pour commencer.
			</p>
		</div>
	{:else}
		<section>
			<h2 class="mb-3 text-sm font-medium text-muted-foreground">Artistes suivis</h2>
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
		</section>
	{/if}
</div>

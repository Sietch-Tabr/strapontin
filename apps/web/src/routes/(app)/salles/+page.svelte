<script lang="ts">
	import { enhance } from '$app/forms';
	import { Building2, X, Search, Plus } from '@lucide/svelte';
	import type { PageData, ActionData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();

	let termeRecherche = $state(data.recherche);
</script>

<div class="p-6 lg:p-8">
	<div class="mb-8">
		<h1 class="text-2xl font-semibold text-foreground">Mes salles</h1>
		<p class="mt-1 text-sm text-muted-foreground">
			{data.sallesSuivies.length} salle{data.sallesSuivies.length !== 1 ? 's' : ''} suivie{data.sallesSuivies.length !== 1 ? 's' : ''}
		</p>
	</div>

	<!-- Recherche de salle -->
	<form method="GET" class="mb-6 flex gap-2">
		<input
			type="text"
			name="q"
			bind:value={termeRecherche}
			placeholder="Rechercher une salle…"
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

	{#if form?.erreur}
		<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
			{form.erreur}
		</p>
	{/if}

	<!-- Résultats de recherche -->
	{#if data.resultatsRecherche.length > 0}
		<section class="mb-8">
			<h2 class="mb-3 text-sm font-medium text-muted-foreground">
				Résultats pour « {data.recherche} »
			</h2>
			<ul class="space-y-2">
				{#each data.resultatsRecherche as resultat (resultat.salle)}
					<li class="flex items-center justify-between rounded-xl border border-border bg-card px-4 py-3">
						<div>
							<p class="font-medium text-card-foreground">{resultat.salle}</p>
							{#if resultat.ville}
								<p class="text-xs text-muted-foreground">{resultat.ville}</p>
							{/if}
						</div>
						<form method="POST" action="?/ajouter" use:enhance>
							<input type="hidden" name="salleId" value={resultat.salle} />
							<button
								type="submit"
								class="flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
								aria-label="Suivre {resultat.salle}"
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
		<p class="mb-8 text-sm text-muted-foreground">
			Aucune salle trouvée pour « {data.recherche} ».
		</p>
	{/if}

	<!-- Salles suivies -->
	{#if data.sallesSuivies.length === 0}
		<div class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-20 text-center">
			<Building2 size={40} class="mb-4 text-muted-foreground/50" />
			<p class="mb-1 text-base font-medium text-foreground">Aucune salle suivie.</p>
			<p class="text-sm text-muted-foreground">
				Recherchez une salle ci-dessus pour commencer.
			</p>
		</div>
	{:else}
		<section>
			<h2 class="mb-3 text-sm font-medium text-muted-foreground">Salles suivies</h2>
			<ul class="space-y-2">
				{#each data.sallesSuivies as salle (salle.salleId)}
					<li class="flex items-center justify-between rounded-xl border border-border bg-card px-4 py-3">
						<span class="font-medium text-card-foreground">{salle.salleId}</span>
						<form method="POST" action="?/supprimer" use:enhance>
							<input type="hidden" name="salleId" value={salle.salleId} />
							<button
								type="submit"
								class="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive"
								aria-label="Ne plus suivre {salle.salleId}"
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

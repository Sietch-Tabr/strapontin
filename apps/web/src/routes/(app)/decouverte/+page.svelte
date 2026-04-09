<script lang="ts">
	import { untrack } from 'svelte';
	import { enhance } from '$app/forms';
	import { Compass, Search, X, Check } from '@lucide/svelte';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();

	let recherche = $state(untrack(() => data.recherche));
	let deptsCoches = $state(untrack(() => new Set(data.deptsActifs)));
	let mois = $state(untrack(() => data.mois));
	let genre = $state(untrack(() => data.genre));

	// Mois disponibles pour le filtre (basé sur les résultats actuels)
	const moisDisponibles = $derived(
		[...new Set(data.resultats.map((c) => c.date.slice(0, 7)))].sort()
	);

	function toutAfficher() {
		deptsCoches = new Set();
	}

	function resetMesDepts() {
		deptsCoches = new Set(data.userDepts);
	}

	function toggleDept(dept: string) {
		const nouveau = new Set(deptsCoches);
		if (nouveau.has(dept)) nouveau.delete(dept);
		else nouveau.add(dept);
		deptsCoches = nouveau;
	}

	function formaterDate(dateStr: string): string {
		const [annee, moisNum, jour] = dateStr.split('-').map(Number);
		return new Date(annee, moisNum - 1, jour).toLocaleDateString('fr-FR', {
			weekday: 'short',
			day: 'numeric',
			month: 'short',
			year: 'numeric'
		});
	}

	function labelMois(m: string): string {
		const [annee, moisNum] = m.split('-').map(Number);
		return new Date(annee, moisNum - 1).toLocaleDateString('fr-FR', {
			month: 'long',
			year: 'numeric'
		});
	}

	// Artistes ajoutés localement (optimistic UI)
	let artistesAjoutes = $state(new Set<string>());

	function marquerAjoute(artisteNorm: string) {
		artistesAjoutes = new Set([...artistesAjoutes, artisteNorm]);
	}

	function estSuivi(artisteNorm: string): boolean {
		return (
			(data.resultats.find((c) => c.artisteNorm === artisteNorm)?.dejaSuivi ?? false) ||
			artistesAjoutes.has(artisteNorm)
		);
	}
</script>

<div class="p-6 lg:p-8">
	<div class="mb-8">
		<h1 class="text-2xl font-semibold text-foreground">Découverte</h1>
		<p class="mt-1 text-sm text-muted-foreground">
			Parcourez tous les concerts disponibles et suivez de nouveaux artistes.
		</p>
	</div>

	<!-- Filtres -->
	<form method="GET" class="mb-6 space-y-3">
		<div class="flex flex-wrap gap-2">
			<input
				type="text"
				name="q"
				bind:value={recherche}
				placeholder="Artiste, salle…"
				autocomplete="off"
				class="flex-1 min-w-40 rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
			/>
			<select
				name="mois"
				bind:value={mois}
				class="rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
			>
				<option value="">Tous les mois</option>
				{#each moisDisponibles as m (m)}
					<option value={m}>{labelMois(m)}</option>
				{/each}
			</select>
			{#if data.genresDisponibles.length > 0}
				<select
					name="genre"
					bind:value={genre}
					class="rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
				>
					<option value="">Tous les genres</option>
					{#each data.genresDisponibles as g (g)}
						<option value={g}>{g}</option>
					{/each}
				</select>
			{/if}
			<button
				type="submit"
				class="flex items-center gap-2 rounded-md border border-border px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent"
			>
				<Search size={16} />
				Filtrer
			</button>
			{#if recherche || mois || genre || deptsCoches.size > 0}
				<a
					href="/decouverte"
					class="flex items-center gap-2 rounded-md px-3 py-2 text-sm text-muted-foreground transition-colors hover:text-foreground"
				>
					<X size={16} />
					Réinitialiser
				</a>
			{/if}
		</div>

		<!-- Filtre départements : chips cliquables -->
		{#if data.deptsDisponibles.length > 0}
			<div class="flex flex-wrap items-center gap-1.5">
				{#each data.deptsDisponibles as d (d)}
					<!-- input caché pour la soumission du formulaire -->
					{#if deptsCoches.has(d)}
						<input type="hidden" name="dept" value={d} />
					{/if}
					<button
						type="button"
						onclick={() => toggleDept(d)}
						class="rounded-full border px-2.5 py-1 text-xs font-medium transition-colors
							{deptsCoches.has(d)
								? 'border-foreground bg-foreground text-background'
								: data.userDepts.includes(d)
									? 'border-foreground/40 bg-transparent text-foreground hover:border-foreground'
									: 'border-border bg-transparent text-muted-foreground hover:border-foreground/40 hover:text-foreground'}"
					>
						{d}
					</button>
				{/each}
				{#if data.userDepts.length > 0 && deptsCoches.size !== data.userDepts.length}
					<button
						type="button"
						onclick={resetMesDepts}
						class="rounded-full border border-dashed border-border px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
					>
						Mes depts
					</button>
				{/if}
				{#if deptsCoches.size > 0}
					<button
						type="button"
						onclick={toutAfficher}
						class="rounded-full border border-dashed border-border px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
					>
						Tout
					</button>
				{/if}
			</div>
		{/if}
	</form>

	{#if data.resultats.length === 0}
		<div class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-20 text-center">
			<Compass size={40} class="mb-4 text-muted-foreground/50" />
			{#if data.recherche || data.deptsActifs.length > 0 || data.mois || data.genre}
				<p class="mb-1 text-base font-medium text-foreground">Aucun résultat pour ces filtres.</p>
				<a href="/decouverte" class="mt-4 text-sm text-muted-foreground underline underline-offset-2">
					Voir tous les concerts
				</a>
			{:else}
				<p class="mb-1 text-base font-medium text-foreground">Aucun concert en base pour l'instant.</p>
				<p class="text-sm text-muted-foreground">
					Les concerts apparaîtront ici une fois que le worker aura effectué ses premiers fetches.
				</p>
			{/if}
		</div>
	{:else}
		<!-- Vue tableau desktop -->
		<div class="hidden overflow-hidden rounded-xl border border-border lg:block">
			<table class="w-full text-sm">
				<thead class="border-b border-border bg-muted/40">
					<tr>
						<th class="px-4 py-3 text-left font-medium text-muted-foreground">Artiste</th>
						<th class="px-4 py-3 text-left font-medium text-muted-foreground">Date</th>
						<th class="px-4 py-3 text-left font-medium text-muted-foreground">Salle</th>
						<th class="px-4 py-3 text-left font-medium text-muted-foreground">Ville</th>
						<th class="px-4 py-3 text-left font-medium text-muted-foreground">Dép.</th>
						<th class="px-4 py-3"></th>
					</tr>
				</thead>
				<tbody class="divide-y divide-border">
					{#each data.resultats as concert (concert.id)}
						<tr class="transition-colors hover:bg-muted/30">
							<td class="px-4 py-3 font-medium text-foreground">{concert.artiste}</td>
							<td class="px-4 py-3 text-muted-foreground">{formaterDate(concert.date)}</td>
							<td class="px-4 py-3 text-muted-foreground">{concert.salle}</td>
							<td class="px-4 py-3 text-muted-foreground">{concert.ville ?? '—'}</td>
							<td class="px-4 py-3 text-muted-foreground">{concert.dept ?? '—'}</td>
							<td class="px-4 py-3">
								<div class="flex items-center justify-end gap-2">
									{#if concert.url}
										<a
											href={concert.url}
											target="_blank"
											rel="noopener noreferrer"
											class="rounded-md border border-border px-3 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent"
										>
											Billets
										</a>
									{/if}
									{#if estSuivi(concert.artisteNorm)}
										<span class="flex items-center gap-1 rounded-md px-3 py-1 text-xs font-medium text-muted-foreground">
											<Check size={12} />
											Suivi
										</span>
									{:else}
										<form
											method="POST"
											action="/artistes?/ajouter"
											use:enhance={() => {
												return async ({ result, update }) => {
													if (result.type === 'success') {
														marquerAjoute(concert.artisteNorm);
													}
													await update({ reset: false });
												};
											}}
										>
											<input type="hidden" name="nom" value={concert.artiste} />
											<button
												type="submit"
												class="rounded-md border border-border px-3 py-1 text-xs font-medium text-foreground transition-colors hover:bg-accent"
											>
												+ Suivre
											</button>
										</form>
									{/if}
								</div>
							</td>
						</tr>
					{/each}
				</tbody>
			</table>
		</div>

		<!-- Vue cartes mobile -->
		<div class="space-y-2 lg:hidden">
			{#each data.resultats as concert (concert.id)}
				<div class="rounded-xl border border-border bg-card px-4 py-3">
					<div class="flex items-start justify-between gap-2">
						<div class="min-w-0">
							<p class="truncate font-medium text-card-foreground">{concert.artiste}</p>
							<p class="mt-0.5 text-sm text-muted-foreground">{formaterDate(concert.date)}</p>
							<p class="mt-0.5 text-sm text-muted-foreground">
								{concert.salle}{concert.ville ? ` · ${concert.ville}` : ''}
							</p>
						</div>
						<div class="flex shrink-0 flex-col items-end gap-1">
							{#if concert.url}
								<a
									href={concert.url}
									target="_blank"
									rel="noopener noreferrer"
									class="rounded-md border border-border px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent"
								>
									Billets
								</a>
							{/if}
							{#if estSuivi(concert.artisteNorm)}
								<span class="flex items-center gap-1 px-3 py-1 text-xs text-muted-foreground">
									<Check size={12} />
									Suivi
								</span>
							{:else}
								<form
									method="POST"
									action="/artistes?/ajouter"
									use:enhance={() => {
										return async ({ result, update }) => {
											if (result.type === 'success') {
												marquerAjoute(concert.artisteNorm);
											}
											await update({ reset: false });
										};
									}}
								>
									<input type="hidden" name="nom" value={concert.artiste} />
									<button
										type="submit"
										class="rounded-md border border-border px-3 py-1.5 text-xs font-medium text-foreground transition-colors hover:bg-accent"
									>
										+ Suivre
									</button>
								</form>
							{/if}
						</div>
					</div>
				</div>
			{/each}
		</div>
	{/if}
</div>

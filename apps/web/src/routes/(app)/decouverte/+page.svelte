<script lang="ts">
	import { untrack } from 'svelte';
	import { enhance } from '$app/forms';
	import { Compass, Search, X, Check, Info, RefreshCw, Plus } from '@lucide/svelte';
	import type { PageData, ActionData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();

	// Depts pour lesquels un fetch a déjà été lancé — bannière masquée côté client
	let deptsActualises = $state(new Set<string>());
	const deptsASignaler = $derived(
		data.deptsHorsListe.filter((d) => !deptsActualises.has(d))
	);

	// État local uniquement pour les champs texte/select du formulaire
	let recherche = $state(untrack(() => data.recherche));
	let mois = $state(untrack(() => data.mois));
	let budget = $state(untrack(() => data.budget));

	const moisDisponibles = $derived(
		[...new Set(data.resultats.map((c) => c.date.slice(0, 7)))].sort()
	);

	const LABELS_CLASSIFICATION: Record<string, string> = {
		music: 'Musique',
		theatre: 'Théâtre',
		opera: 'Opéra',
		musical: 'Musical'
	};

	// Construit une URL /decouverte avec tous les filtres actifs.
	// Les chips utilisent des <a href> — navigation SvelteKit garantie.
	function construireUrl(options: {
		depts?: string[];
		deptsFiltre?: boolean;
		classifications?: string[];
		genres?: string[];
	} = {}): string {
		const params = new URLSearchParams();
		if (recherche) params.set('q', recherche);
		if (mois) params.set('mois', mois);
		if (budget) params.set('budget', budget);

		const newDeptsFiltre = options.deptsFiltre ?? data.deptFiltre;
		if (newDeptsFiltre) params.set('dept_filtre', '1');
		for (const d of (options.depts ?? data.deptsActifs)) params.append('dept', d);
		for (const c of (options.classifications ?? data.classifications)) params.append('classification', c);
		for (const g of (options.genres ?? data.genres)) params.append('genre', g);

		return `/decouverte?${params}`;
	}

	function urlToggleDept(dept: string): string {
		const actif = data.deptsActifs.includes(dept);
		const depts = actif
			? data.deptsActifs.filter((d) => d !== dept)
			: [...data.deptsActifs, dept];
		return construireUrl({ depts, deptsFiltre: true });
	}

	function urlToggleClassification(classif: string): string {
		const actif = data.classifications.includes(classif);
		const classifications = actif
			? data.classifications.filter((c) => c !== classif)
			: [...data.classifications, classif];
		return construireUrl({ classifications });
	}

	function urlToggleGenre(genre: string): string {
		const actif = data.genres.includes(genre);
		const genres = actif
			? data.genres.filter((g) => g !== genre)
			: [...data.genres, genre];
		return construireUrl({ genres });
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

	<!-- Filtres texte/mois via formulaire GET classique -->
	<form method="GET" class="mb-4 space-y-3">
		<!-- Inputs cachés pour préserver l'état des chips lors du submit -->
		{#if data.deptFiltre}
			<input type="hidden" name="dept_filtre" value="1" />
		{/if}
		{#each data.deptsActifs as d}
			<input type="hidden" name="dept" value={d} />
		{/each}
		{#each data.classifications as c}
			<input type="hidden" name="classification" value={c} />
		{/each}
		{#each data.genres as g}
			<input type="hidden" name="genre" value={g} />
		{/each}

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
			<select
				name="budget"
				bind:value={budget}
				class="rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
			>
				<option value="">Tous les prix</option>
				<option value="30">≤ 30 €</option>
				<option value="50">≤ 50 €</option>
				<option value="80">≤ 80 €</option>
				<option value="100">≤ 100 €</option>
			</select>
			<button
				type="submit"
				class="flex items-center gap-2 rounded-md border border-border px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent"
			>
				<Search size={16} />
				Filtrer
			</button>
			{#if recherche || mois || budget || data.deptFiltre || data.classifications.length > 0 || data.genres.length > 0}
				<a
					href="/decouverte"
					class="flex items-center gap-2 rounded-md px-3 py-2 text-sm text-muted-foreground transition-colors hover:text-foreground"
				>
					<X size={16} />
					Réinitialiser
				</a>
			{/if}
		</div>
	</form>

	<!-- Chips : départements -->
	{#if data.deptsDisponibles.length > 0}
		<div class="mb-3 border-t border-border pt-3">
			<p class="mb-1.5 text-xs font-medium text-muted-foreground">Départements</p>
			<div class="flex flex-wrap items-center gap-1.5">
				{#each data.deptsDisponibles as d (d)}
					<a
						href={urlToggleDept(d)}
						data-sveltekit-replacestate
						class="rounded-full border px-2.5 py-1 text-xs font-medium transition-colors
							{data.deptsActifs.includes(d)
								? 'border-foreground bg-foreground text-background'
								: data.userDepts.includes(d)
									? 'border-foreground/40 bg-transparent text-foreground hover:border-foreground'
									: 'border-border bg-transparent text-muted-foreground hover:border-foreground/40 hover:text-foreground'}"
					>
						{d}
					</a>
				{/each}
				{#if data.userDepts.length > 0 && data.deptsActifs.length !== data.userDepts.length}
					<a
						href={construireUrl({ depts: data.userDepts, deptsFiltre: true })}
						data-sveltekit-replacestate
						class="rounded-full border border-dashed border-border px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
					>
						Mes depts
					</a>
				{/if}
				{#if data.deptFiltre && data.deptsActifs.length > 0}
					<a
						href={construireUrl({ depts: [], deptsFiltre: true })}
						data-sveltekit-replacestate
						class="rounded-full border border-dashed border-border px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
					>
						Tout
					</a>
				{/if}
			</div>
		</div>
	{/if}

	<!-- Chips : catégories -->
	{#if data.classificationsDisponibles.length > 0}
		<div class="mb-3 border-t border-border pt-3">
			<p class="mb-1.5 text-xs font-medium text-muted-foreground">Catégories</p>
			<div class="flex flex-wrap items-center gap-1.5">
				{#each data.classificationsDisponibles as c (c)}
					<a
						href={urlToggleClassification(c)}
						data-sveltekit-replacestate
						class="rounded-full border px-2.5 py-1 text-xs font-medium transition-colors
							{data.classifications.includes(c)
								? 'border-foreground bg-foreground text-background'
								: 'border-border bg-transparent text-muted-foreground hover:border-foreground/40 hover:text-foreground'}"
					>
						{LABELS_CLASSIFICATION[c] ?? c}
					</a>
				{/each}
			</div>
		</div>
	{/if}

	<!-- Chips : genres -->
	{#if data.genresDisponibles.length > 0}
		<div class="mb-6 border-t border-border pt-3">
			<p class="mb-1.5 text-xs font-medium text-muted-foreground">Genres</p>
			<div class="flex flex-wrap items-center gap-1.5">
				{#each data.genresDisponibles as g (g)}
					<a
						href={urlToggleGenre(g)}
						data-sveltekit-replacestate
						class="rounded-full border px-2.5 py-1 text-xs font-medium transition-colors
							{data.genres.includes(g)
								? 'border-foreground bg-foreground text-background'
								: 'border-border bg-transparent text-muted-foreground hover:border-foreground/40 hover:text-foreground'}"
					>
						{g}
					</a>
				{/each}
			</div>
		</div>
	{/if}

	<!-- Bannière : depts actifs hors préférences → données potentiellement incomplètes -->
	{#if deptsASignaler.length > 0}
		<div class="mb-4 rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 dark:border-blue-800 dark:bg-blue-950/40">
			<div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
				<div class="flex items-start gap-2.5">
					<Info size={16} class="mt-0.5 shrink-0 text-blue-600 dark:text-blue-400" />
					<div>
						<p class="text-sm font-medium text-blue-900 dark:text-blue-200">
							Données peut-être incomplètes pour {deptsASignaler.join(', ')}
						</p>
						<p class="mt-0.5 text-xs text-blue-700 dark:text-blue-400">
							Ces départements ne font pas partie de vos préférences — les concerts n'y sont pas pré-chargés.
						</p>
					</div>
				</div>
				<div class="flex shrink-0 flex-wrap gap-2">
					<form
						method="POST"
						action="?/fetcherRegion"
						use:enhance={() => async ({ update }) => {
							deptsActualises = new Set([...deptsActualises, ...deptsASignaler]);
							await update({ reset: false });
						}}
					>
						{#each deptsASignaler as d}
							<input type="hidden" name="dept" value={d} />
						{/each}
						<button
							type="submit"
							class="flex items-center gap-1.5 rounded-md border border-blue-300 bg-white px-3 py-1.5 text-xs font-medium text-blue-700 transition-colors hover:bg-blue-50 dark:border-blue-700 dark:bg-transparent dark:text-blue-300 dark:hover:bg-blue-900/40"
						>
							<RefreshCw size={12} />
							Actualiser maintenant
						</button>
					</form>
					<form method="POST" action="?/ajouterDepts" use:enhance>
						{#each deptsASignaler as d}
							<input type="hidden" name="dept" value={d} />
						{/each}
						<button
							type="submit"
							class="flex items-center gap-1.5 rounded-md border border-blue-300 bg-white px-3 py-1.5 text-xs font-medium text-blue-700 transition-colors hover:bg-blue-50 dark:border-blue-700 dark:bg-transparent dark:text-blue-300 dark:hover:bg-blue-900/40"
						>
							<Plus size={12} />
							Ajouter à mes préférences
						</button>
					</form>
				</div>
			</div>
		</div>
	{/if}

	{#if data.resultats.length === 0}
		<div class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-20 text-center">
			<Compass size={40} class="mb-4 text-muted-foreground/50" />
			{#if data.recherche || data.deptsActifs.length > 0 || data.mois || data.genres.length > 0 || data.classifications.length > 0}
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

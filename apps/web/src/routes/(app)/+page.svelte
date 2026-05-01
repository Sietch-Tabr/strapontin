<script lang="ts">
	import type { PageData } from './$types';
	import { Music } from '@lucide/svelte';
	import { enhance } from '$app/forms';

	let { data }: { data: PageData } = $props();

	function formaterDate(dateStr: string): string {
		const [annee, mois, jour] = dateStr.split('-').map(Number);
		return new Date(annee, mois - 1, jour).toLocaleDateString('fr-FR', {
			weekday: 'long',
			day: 'numeric',
			month: 'long',
			year: 'numeric'
		});
	}

	function formaterDateCourte(dateStr: string): string {
		const [annee, mois, jour] = dateStr.split('-').map(Number);
		return new Date(annee, mois - 1, jour).toLocaleDateString('fr-FR', {
			weekday: 'short',
			day: 'numeric',
			month: 'short',
			year: 'numeric'
		});
	}

	const totalConcerts = $derived(
		data.artistesGroupes.reduce((acc, g) => acc + g.concerts.length, 0)
	);

	function badgeStatut(statut: string | null): { label: string; classes: string } | null {
		if (statut === 'offsale') return { label: 'Complet', classes: 'bg-orange-500/15 text-orange-600 dark:text-orange-400' };
		if (statut === 'cancelled') return { label: 'Annulé', classes: 'bg-red-500/15 text-red-600 dark:text-red-400' };
		if (statut === 'postponed' || statut === 'rescheduled') return { label: 'Reporté', classes: 'bg-yellow-500/15 text-yellow-600 dark:text-yellow-400' };
		return null;
	}
</script>

<svelte:head>
	<title>Accueil — Strapontin</title>
</svelte:head>

<div class="p-6 lg:p-8">
	<div class="mb-8">
		<p class="text-sm text-muted-foreground">Bonjour, {data.utilisateur?.name}</p>
		<h1 class="text-2xl font-semibold text-foreground">Mes prochains concerts</h1>
	</div>

	<!-- Chips de filtre statut -->
	{#if totalConcerts > 0 || data.inclureComplets || data.inclureAnnules || data.nbArtistes > 0}
		<div class="mb-6 flex flex-wrap gap-1.5">
			<a
				href="?complets={data.inclureComplets ? '0' : '1'}&annules={data.inclureAnnules ? '1' : '0'}"
				data-sveltekit-replacestate
				aria-current={data.inclureComplets ? 'true' : undefined}
				class="rounded-full border px-2.5 py-1 text-xs font-medium transition-colors
					{data.inclureComplets
						? 'border-orange-400 bg-orange-500/15 text-orange-600 dark:text-orange-400'
						: 'border-border bg-transparent text-muted-foreground hover:border-foreground/40 hover:text-foreground'}"
			>
				Complet
			</a>
			<a
				href="?complets={data.inclureComplets ? '1' : '0'}&annules={data.inclureAnnules ? '0' : '1'}"
				data-sveltekit-replacestate
				aria-current={data.inclureAnnules ? 'true' : undefined}
				class="rounded-full border px-2.5 py-1 text-xs font-medium transition-colors
					{data.inclureAnnules
						? 'border-red-400 bg-red-500/15 text-red-600 dark:text-red-400'
						: 'border-border bg-transparent text-muted-foreground hover:border-foreground/40 hover:text-foreground'}"
			>
				Annulé/Reporté
			</a>
		</div>
	{/if}

	{#if totalConcerts === 0}
		<div class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-20 text-center">
			<Music size={40} class="mb-4 text-muted-foreground/50" />
			{#if data.nbArtistes === 0 && data.nbSalles === 0}
				<p class="mb-1 text-base font-medium text-foreground">Bienvenue sur Strapontin !</p>
				<p class="mb-6 text-sm text-muted-foreground">
					Ajoutez des artistes ou des salles pour découvrir vos prochains concerts.
				</p>
				<div class="flex gap-3">
					<a
						href="/artistes"
						class="rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90"
					>
						Ajouter des artistes
					</a>
					<a
						href="/salles"
						class="rounded-md border border-border px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent"
					>
						Ajouter des salles
					</a>
				</div>
			{:else if !data.inclureComplets || !data.inclureAnnules}
				<p class="mb-1 text-base font-medium text-foreground">Aucun concert avec ces filtres.</p>
				<p class="text-sm text-muted-foreground">
					Essayez d'inclure les concerts complets ou annulés ci-dessus.
				</p>
			{:else}
				<p class="mb-1 text-base font-medium text-foreground">Aucun concert pour l'instant.</p>
				<p class="text-sm text-muted-foreground">
					Vos artistes et salles sont suivis — les concerts apparaîtront au prochain rafraîchissement.
				</p>
			{/if}
		</div>
	{:else}
		<div class="space-y-4 max-w-2xl">
			{#each data.artistesGroupes as groupe (groupe.artiste)}
				<div class="rounded-xl border border-border bg-card overflow-hidden">
					<!-- En-tête artiste -->
					<div class="flex items-center gap-2 px-4 py-3 border-b border-border bg-muted/20">
						<p class="font-semibold text-card-foreground">{groupe.artiste}</p>
						{#if groupe.estTournee}
							<span class="rounded-full bg-foreground/10 px-2 py-0.5 text-xs font-medium text-foreground">
								Tournée · {groupe.concerts.length} dates
							</span>
						{/if}
						{#if groupe.estNew}
							<span class="rounded-full bg-blue-500/15 px-1.5 py-0.5 text-[10px] font-semibold text-blue-600 dark:text-blue-400">Nouveau</span>
						{/if}
					</div>

					<!-- Liste des dates — lien billetterie sur chaque ligne -->
					<div class="divide-y divide-border/50">
						{#each groupe.concerts as concert (concert.id)}
						{@const badge = badgeStatut(concert.statut)}
						<div class="flex items-center justify-between px-4 py-3">
							<div class="min-w-0">
								{#if groupe.estTournee}
									<p class="text-sm font-medium text-foreground">
										{formaterDateCourte(concert.date)}
										{#if concert.estNew}
											<span class="ml-1 rounded-full bg-blue-500/15 px-1.5 py-0.5 text-[10px] font-semibold text-blue-600 dark:text-blue-400">Nouveau</span>
										{/if}
										{#if badge}
											<span class="ml-1 rounded-full px-1.5 py-0.5 text-[10px] font-semibold {badge.classes}">{badge.label}</span>
										{/if}
									</p>
								{:else}
									<p class="text-sm text-muted-foreground">
										{formaterDate(concert.date)}
										{#if concert.estNew}
											<span class="ml-1 rounded-full bg-blue-500/15 px-1.5 py-0.5 text-[10px] font-semibold text-blue-600 dark:text-blue-400">Nouveau</span>
										{/if}
										{#if badge}
											<span class="ml-1 rounded-full px-1.5 py-0.5 text-[10px] font-semibold {badge.classes}">{badge.label}</span>
										{/if}
									</p>
								{/if}
									<p class="mt-0.5 text-xs text-muted-foreground truncate">
										{concert.salle}{concert.ville ? ` · ${concert.ville}` : ''}
									</p>
								</div>
								<div class="ml-3 flex shrink-0 flex-col items-end gap-1">
									{#if concert.url}
										<a
											href={concert.url}
											target="_blank"
											rel="noopener noreferrer"
											class="rounded-md border border-border px-3 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
										>
											Billets
										</a>
									{/if}
									<form method="POST" action="?/masquer" use:enhance>
										<input type="hidden" name="concertId" value={concert.id} />
										<button
											type="submit"
											class="rounded-md px-2 py-0.5 text-xs text-muted-foreground/40 transition-colors hover:bg-accent hover:text-muted-foreground"
											aria-label="Masquer ce concert"
										>
											Masquer
										</button>
									</form>
								</div>
							</div>
						{/each}
					</div>
				</div>
			{/each}
		</div>
	{/if}
</div>

<script lang="ts">
	import { Check } from '@lucide/svelte';

	let {
		depts,
		actifs,
		onChange
	}: {
		depts: string[];
		actifs: string[];
		onChange: (actifs: string[]) => void;
	} = $props();

	let ouvert = $state(false);
	let filtre = $state('');
	let conteneur: HTMLElement = $state()!;
	let champRecherche: HTMLInputElement | undefined = $state(undefined);

	const deptsAffiches = $derived(
		filtre.trim() ? depts.filter((d) => d.includes(filtre.trim())) : depts
	);

	function basculer(dept: string) {
		const nouvelActifs = actifs.includes(dept)
			? actifs.filter((d) => d !== dept)
			: [...actifs, dept];
		onChange(nouvelActifs);
	}

	function ouvrir() {
		ouvert = true;
		filtre = '';
		setTimeout(() => champRecherche?.focus(), 0);
	}

	function fermer() {
		ouvert = false;
		filtre = '';
	}

	$effect(() => {
		if (!ouvert) return;

		function gererClicExterieur(evt: PointerEvent) {
			if (!conteneur.contains(evt.target as Node)) fermer();
		}
		function gererTouche(evt: KeyboardEvent) {
			if (evt.key === 'Escape') fermer();
		}

		document.addEventListener('pointerdown', gererClicExterieur);
		document.addEventListener('keydown', gererTouche);
		return () => {
			document.removeEventListener('pointerdown', gererClicExterieur);
			document.removeEventListener('keydown', gererTouche);
		};
	});
</script>

<div bind:this={conteneur} class="relative">
	<button
		type="button"
		onclick={() => (ouvert ? fermer() : ouvrir())}
		aria-expanded={ouvert}
		aria-haspopup="listbox"
		class="rounded-full border px-2.5 py-1 text-xs font-medium transition-colors
			{actifs.length > 0
				? 'border-foreground bg-foreground text-background'
				: 'border-border bg-transparent text-muted-foreground hover:border-foreground/40 hover:text-foreground'}"
	>
		Dép.{actifs.length > 0 ? ` · ${actifs.length}` : ''}
	</button>

	{#if ouvert}
		<div
			role="dialog"
			aria-label="Sélection des départements"
			class="absolute left-0 top-full z-50 mt-1 w-44 rounded-md border border-border bg-popover shadow-md"
		>
			<div class="p-2">
				<input
					bind:this={champRecherche}
					type="text"
					bind:value={filtre}
					placeholder="Rechercher…"
					aria-label="Filtrer les départements"
					class="w-full rounded border border-border bg-background px-2 py-1 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
				/>
			</div>
			<ul role="listbox" aria-multiselectable="true" aria-label="Départements" class="max-h-48 overflow-y-auto p-1">
				{#each deptsAffiches as dept (dept)}
					<li role="option" aria-selected={actifs.includes(dept)}>
						<button
							type="button"
							onclick={() => basculer(dept)}
							class="flex w-full items-center gap-2 rounded px-2 py-1 text-xs text-foreground hover:bg-accent"
						>
							<span
								class="flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded-sm border
									{actifs.includes(dept)
										? 'border-foreground bg-foreground text-background'
										: 'border-border'}"
							>
								{#if actifs.includes(dept)}
									<Check size={10} />
								{/if}
							</span>
							{dept}
						</button>
					</li>
				{/each}
				{#if deptsAffiches.length === 0}
					<li class="px-2 py-2 text-center text-xs text-muted-foreground">Aucun résultat</li>
				{/if}
			</ul>
		</div>
	{/if}
</div>

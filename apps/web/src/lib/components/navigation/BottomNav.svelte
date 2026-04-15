<script lang="ts">
	import { page } from '$app/state';
	import { signOut } from '$lib/auth-client';
	import { goto } from '$app/navigation';
	import {
		Home,
		Music,
		Building2,
		Compass,
		Ticket,
		Settings,
		LogOut,
		MoreHorizontal,
		ShieldCheck
	} from '@lucide/svelte';

	let { estAdmin = false }: { estAdmin?: boolean } = $props();

	let drawerOuvert = $state(false);

	const elementsNav = [
		{ label: 'Accueil', icone: Home, route: '/' },
		{ label: 'Spectacles', icone: Ticket, route: '/spectacles' },
		{ label: 'Découvrir', icone: Compass, route: '/decouverte' }
	];

	const elementsDrawer = [
		{ label: 'Mes artistes', icone: Music, route: '/artistes' },
		{ label: 'Mes salles', icone: Building2, route: '/salles' },
		{ label: 'Paramètres', icone: Settings, route: '/parametres' }
	];

	function estActif(route: string): boolean {
		if (route === '/') return page.url.pathname === '/';
		return page.url.pathname.startsWith(route);
	}

	function plusEstActif(): boolean {
		return elementsDrawer.some(({ route }) => page.url.pathname.startsWith(route));
	}

	function naviguer(route: string) {
		drawerOuvert = false;
		goto(route);
	}

	async function seDeconnecter() {
		drawerOuvert = false;
		await signOut();
		goto('/login');
	}
</script>

<nav class="fixed bottom-0 left-0 right-0 z-40 border-t border-border bg-background lg:hidden">
	<div class="flex h-16 items-center justify-around px-2">
		{#each elementsNav as { label, icone: Icone, route }}
			<a
				href={route}
				class="flex flex-1 flex-col items-center gap-1 py-2 text-xs font-medium transition-colors
					{estActif(route) ? 'text-foreground' : 'text-muted-foreground'}"
			>
				<Icone size={22} />
				{label}
			</a>
		{/each}

		<button
			type="button"
			onclick={() => (drawerOuvert = true)}
			class="flex flex-1 flex-col items-center gap-1 py-2 text-xs font-medium cursor-pointer transition-colors
				{plusEstActif() ? 'text-foreground' : 'text-muted-foreground hover:text-foreground'}"
		>
			<MoreHorizontal size={22} />
			Plus
		</button>
	</div>
</nav>

{#if drawerOuvert}
	<div
		class="fixed inset-0 z-50 lg:hidden"
		role="dialog"
		aria-modal="true"
		aria-label="Menu supplémentaire"
	>
		<button
			type="button"
			class="absolute inset-0 bg-black/50"
			onclick={() => (drawerOuvert = false)}
			aria-label="Fermer le menu"
		></button>

		<div class="absolute bottom-0 left-0 right-0 rounded-t-xl bg-background p-4 pb-8 shadow-xl">
			<div class="mx-auto mb-4 h-1 w-10 rounded-full bg-muted"></div>

			<div class="space-y-1">
				{#each elementsDrawer as { label, icone: Icone, route }}
					<button
						type="button"
						onclick={() => naviguer(route)}
						class="flex w-full items-center gap-3 rounded-md px-3 py-3 text-sm font-medium transition-colors hover:bg-accent hover:text-accent-foreground
							{estActif(route) ? 'bg-accent text-accent-foreground' : 'text-foreground'}"
					>
						<Icone size={20} />
						{label}
					</button>
				{/each}

				{#if estAdmin}
					<button
						type="button"
						onclick={() => naviguer('/admin')}
						class="flex w-full items-center gap-3 rounded-md px-3 py-3 text-sm font-medium transition-colors hover:bg-accent hover:text-accent-foreground
							{estActif('/admin') ? 'bg-accent text-accent-foreground' : 'text-foreground'}"
					>
						<ShieldCheck size={20} />
						Admin
					</button>
				{/if}

				<div class="my-2 border-t border-border"></div>

				<button
					type="button"
					onclick={seDeconnecter}
					class="flex w-full items-center gap-3 rounded-md px-3 py-3 text-sm font-medium text-destructive transition-colors hover:bg-destructive/10"
				>
					<LogOut size={20} />
					Se déconnecter
				</button>
			</div>
		</div>
	</div>
{/if}

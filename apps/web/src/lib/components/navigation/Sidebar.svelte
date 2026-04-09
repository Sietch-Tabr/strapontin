<script lang="ts">
	import { page } from '$app/state';
	import { signOut } from '$lib/auth-client';
	import { goto } from '$app/navigation';
	import {
		Home,
		Music,
		Building2,
		Compass,
		CalendarDays,
		Ticket,
		Settings,
		LogOut
	} from '@lucide/svelte';

	let { utilisateur }: { utilisateur: { name: string } } = $props();

	const elementsNav = [
		{ label: 'Accueil', icone: Home, route: '/' },
		{ label: 'Mes artistes', icone: Music, route: '/artistes' },
		{ label: 'Mes salles', icone: Building2, route: '/salles' },
		{ label: 'Découverte', icone: Compass, route: '/decouverte' },
		{ label: 'Quinzaine', icone: CalendarDays, route: '/quinzaine' },
		{ label: 'Spectacles', icone: Ticket, route: '/spectacles' }
	];

	function estActif(route: string): boolean {
		if (route === '/') return page.url.pathname === '/';
		return page.url.pathname.startsWith(route);
	}

	async function seDeconnecter() {
		await signOut();
		goto('/login');
	}
</script>

<aside class="hidden w-60 shrink-0 flex-col border-r border-sidebar-border bg-sidebar lg:flex">
	<div class="flex h-14 items-center border-b border-sidebar-border px-4">
		<a href="/" class="text-lg font-semibold text-sidebar-foreground">Strapontin</a>
	</div>

	<nav class="flex-1 space-y-0.5 p-2 pt-3">
		{#each elementsNav as { label, icone: Icone, route }}
			<a
				href={route}
				class="flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors
					{estActif(route)
					? 'bg-sidebar-accent text-sidebar-accent-foreground'
					: 'text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'}"
			>
				<Icone size={18} />
				{label}
			</a>
		{/each}
	</nav>

	<div class="border-t border-sidebar-border p-2">
		<a
			href="/parametres"
			class="flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors
				{estActif('/parametres')
				? 'bg-sidebar-accent text-sidebar-accent-foreground'
				: 'text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'}"
		>
			<Settings size={18} />
			Paramètres
		</a>

		<div class="mt-1 flex items-center justify-between rounded-md px-3 py-2">
			<span class="truncate text-sm text-sidebar-foreground">{utilisateur.name}</span>
			<button
				onclick={seDeconnecter}
				class="text-sidebar-foreground/60 transition-colors hover:text-sidebar-foreground"
				aria-label="Se déconnecter"
			>
				<LogOut size={16} />
			</button>
		</div>
	</div>
</aside>

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
		ShieldCheck,
		History,
		HelpCircle
	} from '@lucide/svelte';

	let { utilisateur }: { utilisateur: { nom: string; role?: string } } = $props();

	const elementsNav = [
		{ label: 'Accueil', icone: Home, route: '/' },
		{ label: 'Mes spectacles', icone: Ticket, route: '/spectacles' },
		{ label: 'Découverte', icone: Compass, route: '/decouverte' }
	];

	const elementsConf = [
		{ label: 'Mes artistes', icone: Music, route: '/artistes' },
		{ label: 'Mes salles', icone: Building2, route: '/salles' }
	];

	function estActif(route: string): boolean {
		if (route === '/') return page.url.pathname === '/';
		return page.url.pathname.startsWith(route);
	}

	async function seDeconnecter() {
		try { await signOut(); } finally { goto('/login'); }
	}
</script>

<aside class="hidden w-60 shrink-0 flex-col border-r border-sidebar-border bg-sidebar lg:flex">
	<div class="flex h-14 items-center border-b border-sidebar-border px-4">
		<a href="/" class="text-lg font-semibold text-sidebar-foreground">
			Strapontin
		</a>
	</div>

	<nav class="flex-1 p-2 pt-3" aria-label="Navigation principale">
		<div class="space-y-0.5">
			{#each elementsNav as { label, icone: Icone, route }}
				<a
					href={route}
					class="flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors
						{estActif(route)
						? 'bg-sidebar-accent text-sidebar-accent-foreground'
						: 'text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'}"
				>
					<Icone size={18} aria-hidden="true" />
					{label}
				</a>
			{/each}
		</div>

		<div class="my-3 border-t border-sidebar-border"></div>

		<div class="space-y-0.5">
			{#each elementsConf as { label, icone: Icone, route }}
				<a
					href={route}
					class="flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors
						{estActif(route)
						? 'bg-sidebar-accent text-sidebar-accent-foreground'
						: 'text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'}"
				>
					<Icone size={18} aria-hidden="true" />
					{label}
				</a>
			{/each}
		</div>
	</nav>

	<div class="border-t border-sidebar-border p-2">
		{#if utilisateur.role === 'admin'}
			<a
				href="/admin"
				class="flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors
					{estActif('/admin')
					? 'bg-sidebar-accent text-sidebar-accent-foreground'
					: 'text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'}"
			>
				<ShieldCheck size={18} aria-hidden="true" />
				Admin
			</a>
		{/if}
		<a
			href="/parametres"
			class="flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors
				{estActif('/parametres')
				? 'bg-sidebar-accent text-sidebar-accent-foreground'
				: 'text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'}"
		>
			<Settings size={18} aria-hidden="true" />
			Paramètres
		</a>
		<div class="my-1 border-t border-sidebar-border"></div>
		<a
			href="/aide"
			class="flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors
				{estActif('/aide')
				? 'bg-sidebar-accent text-sidebar-accent-foreground'
				: 'text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'}"
		>
			<HelpCircle size={18} aria-hidden="true" />
			Aide
		</a>
		<a
			href="/changelog"
			class="flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors
				{estActif('/changelog')
				? 'bg-sidebar-accent text-sidebar-accent-foreground'
				: 'text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'}"
		>
			<History size={18} aria-hidden="true" />
			Nouveautés
		</a>

		<div class="mt-1 flex items-center justify-between rounded-md px-3 py-2">
			<span class="truncate text-sm text-sidebar-foreground">{utilisateur.nom}</span>
			<button
				type="button"
				onclick={seDeconnecter}
				class="text-sidebar-foreground/60 transition-colors hover:text-sidebar-foreground"
				aria-label="Se déconnecter"
			>
				<LogOut size={16} aria-hidden="true" />
			</button>
		</div>
	</div>
</aside>

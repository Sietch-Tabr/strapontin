<script lang="ts">
	import { afterNavigate } from '$app/navigation';
	import Sidebar from '$lib/components/navigation/Sidebar.svelte';
	import BottomNav from '$lib/components/navigation/BottomNav.svelte';
	import { Toaster } from '$lib/components/ui/sonner';
	import type { LayoutData } from './$types';

	let { data, children }: { data: LayoutData; children: import('svelte').Snippet } = $props();

	let conteneurPrincipal: HTMLElement;

	// SvelteKit réinitialise window.scrollY mais pas les conteneurs overflow-auto internes
	afterNavigate(() => {
		conteneurPrincipal?.scrollTo({ top: 0 });
	});
</script>

<div class="flex h-screen bg-background">
	<Sidebar utilisateur={data.utilisateur} />

	<main bind:this={conteneurPrincipal} class="flex-1 overflow-auto pb-16 lg:pb-0">
		{@render children()}
	</main>

	<BottomNav estAdmin={data.utilisateur?.role === 'admin'} />
	<Toaster richColors closeButton />
</div>

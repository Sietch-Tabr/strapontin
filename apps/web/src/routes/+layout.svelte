<script lang="ts">
	import '../app.css';
	import favicon from '$lib/assets/favicon.svg';

	let { children } = $props();
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
	<!-- Applique le thème système quand la préférence est 'auto', avant le premier rendu -->
	<!-- svelte-ignore non_reactive_update -->
	{@html `<script>(function(){var m=document.cookie.match(/(?:^|; )theme=([^;]*)/);var t=m?decodeURIComponent(m[1]):'auto';if(t==='auto'&&window.matchMedia('(prefers-color-scheme: dark)').matches){document.documentElement.classList.add('dark');}}());</script>`}
</svelte:head>

{@render children()}

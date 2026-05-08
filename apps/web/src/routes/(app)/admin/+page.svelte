<script lang="ts">
	import { enhance } from '$app/forms';
	import { page } from '$app/stores';
	import { UserPlus, Trash2, ShieldCheck, Zap, RefreshCw, Loader2, Pencil, KeyRound, Settings, ScrollText, LayoutDashboard, Users } from '@lucide/svelte';
	import type { PageData, ActionData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();

	let emailNouvelUser = $state('');
	let creationEnCours = $state(false);
	let confirmationSupprimer = $state<string | null>(null);
	let confirmationReset = $state<string | null>(null);
	let modificationId = $state<string | null>(null);
	let nomModification = $state('');
	let emailModification = $state('');

	let relancerEnCours = $state(false);
	let configEditionCle = $state<string | null>(null);
	let configValeur = $state('');

	const ONGLETS = ['tableau-de-bord', 'logs', 'configuration', 'utilisateurs'] as const;
	type Onglet = (typeof ONGLETS)[number];

	const ongletActif = $derived<Onglet>(
		(($page.url.searchParams.get('tab') ?? 'tableau-de-bord') as Onglet)
	);

	const SEUIL_INACTIF_MS = 90 * 24 * 60 * 60 * 1000;

	const donneesBrutes = $derived(form as Record<string, unknown> | null);
	const erreurConfigCle = $derived(
		donneesBrutes?.['action'] === 'sauvegarderConfig' && typeof donneesBrutes?.['erreur'] === 'string' && typeof donneesBrutes?.['cle'] === 'string'
			? donneesBrutes['cle'] as string
			: null,
	);
	const erreurConfigMessage = $derived(
		donneesBrutes?.['action'] === 'sauvegarderConfig' && typeof donneesBrutes?.['erreur'] === 'string'
			? donneesBrutes['erreur'] as string
			: null,
	);
	const erreurGlobale = $derived(
		donneesBrutes?.['action'] !== 'sauvegarderConfig' && typeof donneesBrutes?.['erreur'] === 'string'
			? donneesBrutes['erreur'] as string
			: null,
	);

	function formaterReset(timestampMs: number): string {
		if (!timestampMs) return '—';
		return new Date(timestampMs).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
	}

	function formaterMisAJour(iso: string): string {
		return new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
	}

	function formaterDateRelative(iso: string): string {
		const date = new Date(iso);
		const maintenant = new Date();
		const diffMs = maintenant.getTime() - date.getTime();
		const diffMin = Math.floor(diffMs / 60_000);

		if (diffMin < 1) return "à l'instant";
		if (diffMin < 60) return `il y a ${diffMin} min`;

		const diffH = Math.floor(diffMin / 60);
		const heure = date.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });

		if (diffH < 24) return `aujourd'hui à ${heure}`;

		const diffJ = Math.floor(diffH / 24);
		if (diffJ === 1) return `hier à ${heure}`;
		return `il y a ${diffJ} jours`;
	}

	function formaterDuree(ms: number): string {
		if (ms < 1000) return `${ms} ms`;
		const s = Math.floor(ms / 1000);
		if (s < 60) return `${s} s`;
		const min = Math.floor(s / 60);
		const reste = s % 60;
		return reste > 0 ? `${min} min ${reste} s` : `${min} min`;
	}

	function formaterProchaineRun(finRunIso: string): string {
		const prochaine = new Date(new Date(finRunIso).getTime() + 2 * 60 * 60 * 1000);
		const diffMs = prochaine.getTime() - Date.now();
		if (diffMs <= 0) return 'imminent';
		const diffMin = Math.floor(diffMs / 60_000);
		if (diffMin < 60) return `dans ${diffMin} min`;
		const diffH = Math.floor(diffMin / 60);
		const resteMin = diffMin % 60;
		return resteMin > 0 ? `dans ${diffH} h ${resteMin} min` : `dans ${diffH} h`;
	}

	function estInactif(derniereActivite: Date | string | null): boolean {
		if (!derniereActivite) return true;
		return Date.now() - new Date(derniereActivite).getTime() > SEUIL_INACTIF_MS;
	}
</script>

<svelte:head>
	<title>Administration — Strapontin</title>
</svelte:head>

<div class="p-6 lg:p-8">
	<div class="mb-6">
		<h1 class="text-2xl font-semibold text-foreground">Administration</h1>
	</div>

	<!-- Barre d'onglets -->
	<nav
		class="sticky top-0 z-10 -mx-6 mb-8 overflow-x-auto border-b border-border bg-background px-6 lg:-mx-8 lg:px-8"
		aria-label="Onglets d'administration"
	>
		<div class="flex min-w-max gap-0">
			<a
				href="?tab=tableau-de-bord"
				data-sveltekit-replacestate
				class="flex items-center gap-2 border-b-2 px-4 py-3 text-sm font-medium transition-colors whitespace-nowrap
					{ongletActif === 'tableau-de-bord'
						? 'border-foreground text-foreground'
						: 'border-transparent text-muted-foreground hover:text-foreground'}"
				aria-current={ongletActif === 'tableau-de-bord' ? 'page' : undefined}
			>
				<LayoutDashboard size={15} aria-hidden="true" />
				Tableau de bord
			</a>
			<a
				href="?tab=logs"
				data-sveltekit-replacestate
				class="flex items-center gap-2 border-b-2 px-4 py-3 text-sm font-medium transition-colors whitespace-nowrap
					{ongletActif === 'logs'
						? 'border-foreground text-foreground'
						: 'border-transparent text-muted-foreground hover:text-foreground'}"
				aria-current={ongletActif === 'logs' ? 'page' : undefined}
			>
				<ScrollText size={15} aria-hidden="true" />
				Logs
				{#if data.logsAdmin.some(e => e.niveau === 'error')}
					<span class="h-2 w-2 rounded-full bg-red-500" aria-label="erreurs présentes"></span>
				{/if}
			</a>
			<a
				href="?tab=configuration"
				data-sveltekit-replacestate
				class="flex items-center gap-2 border-b-2 px-4 py-3 text-sm font-medium transition-colors whitespace-nowrap
					{ongletActif === 'configuration'
						? 'border-foreground text-foreground'
						: 'border-transparent text-muted-foreground hover:text-foreground'}"
				aria-current={ongletActif === 'configuration' ? 'page' : undefined}
			>
				<Settings size={15} aria-hidden="true" />
				Configuration
			</a>
			<a
				href="?tab=utilisateurs"
				data-sveltekit-replacestate
				class="flex items-center gap-2 border-b-2 px-4 py-3 text-sm font-medium transition-colors whitespace-nowrap
					{ongletActif === 'utilisateurs'
						? 'border-foreground text-foreground'
						: 'border-transparent text-muted-foreground hover:text-foreground'}"
				aria-current={ongletActif === 'utilisateurs' ? 'page' : undefined}
			>
				<Users size={15} aria-hidden="true" />
				Utilisateurs ({data.utilisateurs.length})
			</a>
		</div>
	</nav>

	<div class="max-w-2xl space-y-10">

		{#if ongletActif === 'tableau-de-bord'}

			<!-- Quota Ticketmaster -->
			<section>
				<h2 class="mb-4 text-base font-medium text-foreground flex items-center gap-2">
					<Zap size={16} class="text-muted-foreground" aria-hidden="true" />
					Quota Ticketmaster API
				</h2>

				{#if data.quotaTM}
					{@const pct = data.quotaTM.total > 0 ? Math.round((data.quotaTM.dispo / data.quotaTM.total) * 100) : 0}
					{@const couleur = pct > 30 ? 'bg-green-500' : pct > 10 ? 'bg-yellow-500' : 'bg-red-500'}
					<div class="rounded-xl border border-border bg-card p-4">
						<div class="mb-3 flex items-end justify-between">
							<div>
								<p class="text-2xl font-semibold tabular-nums text-foreground">
									{data.quotaTM.dispo.toLocaleString('fr-FR')}
									<span class="text-base font-normal text-muted-foreground">
										/ {data.quotaTM.total.toLocaleString('fr-FR')}
									</span>
								</p>
								<p class="text-xs text-muted-foreground">appels restants</p>
							</div>
							<p class="text-sm font-medium text-foreground">{pct} %</p>
						</div>
						<div class="h-2 w-full overflow-hidden rounded-full bg-muted">
							<div class="h-2 rounded-full transition-all {couleur}" style="width: {pct}%"></div>
						</div>
						<div class="mt-3 flex justify-between text-xs text-muted-foreground">
							<span>Remise à zéro vers {formaterReset(data.quotaTM.reset)}</span>
							<span>Mis à jour {formaterMisAJour(data.quotaTM.misAJour)}</span>
						</div>
						{#if data.quotaTM.dispo < 10}
							<p class="mt-3 text-xs font-medium text-red-600 dark:text-red-400">
								Quota épuisé — les fetches artistes et région sont suspendus jusqu'à la remise à zéro.
							</p>
						{:else if data.quotaTM.dispo < 200}
							<p class="mt-3 text-xs text-yellow-600 dark:text-yellow-400">
								Quota faible — certains fetches peuvent être interrompus.
							</p>
						{/if}
					</div>
				{:else}
					<div class="rounded-xl border border-dashed border-border px-4 py-6 text-center text-sm text-muted-foreground">
						Aucune donnée — quota disponible après le prochain fetch Ticketmaster.
					</div>
				{/if}
			</section>

			<!-- Actualisations -->
			<section>
				<h2 class="mb-4 text-base font-medium text-foreground flex items-center gap-2">
					<RefreshCw size={16} class="text-muted-foreground" aria-hidden="true" />
					Actualisations
				</h2>

				{#if form?.action === 'relancerFetches' && form?.succes}
					<p class="mb-3 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
						Fetches relancés — ils démarreront dans quelques secondes.
					</p>
				{/if}

				<div class="rounded-xl border border-border bg-card divide-y divide-border">
					{#each [
						{ label: 'Artistes suivis', stats: data.statsJobs.artistesBatch },
						{ label: 'Concerts par région', stats: data.statsJobs.region },
					] as { label, stats }}
						<div class="px-4 py-3 flex items-start justify-between gap-4">
							<div class="min-w-0">
								<p class="text-sm font-medium text-foreground">{label}</p>
								{#if stats}
									<p class="text-xs text-muted-foreground mt-0.5">
										{#if stats.statut === 'running'}
											Démarré {formaterDateRelative(stats.debutRun)}
										{:else if stats.finRun}
											Dernière exécution : {formaterDateRelative(stats.finRun)}{stats.durationMs ? ` · ${formaterDuree(stats.durationMs)}` : ''}
										{/if}
										{#if stats.statut === 'error' && stats.erreur}
											<span class="block text-destructive">{stats.erreur}</span>
										{/if}
									</p>
								{:else}
									<p class="text-xs text-muted-foreground mt-0.5">Pas encore exécuté</p>
								{/if}
							</div>
							<div class="shrink-0 flex items-center gap-2 mt-0.5">
								{#if stats?.statut === 'running'}
									<span class="flex items-center gap-1 text-xs text-blue-600 dark:text-blue-400">
										<Loader2 size={12} class="animate-spin" />
										En cours
									</span>
								{:else if stats?.statut === 'error'}
									<span class="text-xs font-medium text-destructive">Erreur</span>
								{:else if stats?.finRun}
									<span class="text-xs text-muted-foreground">{formaterProchaineRun(stats.finRun)}</span>
								{/if}
							</div>
						</div>
					{/each}
				</div>

				<form
					method="POST"
					action="?/relancerFetches"
					class="mt-3"
					use:enhance={() => {
						relancerEnCours = true;
						return async ({ update }) => {
							relancerEnCours = false;
							await update();
						};
					}}
				>
					<button
						type="submit"
						disabled={relancerEnCours}
						class="flex items-center gap-2 rounded-md border border-border bg-card px-3 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent disabled:opacity-50"
					>
						{#if relancerEnCours}
							<Loader2 size={15} class="animate-spin" />
						{:else}
							<RefreshCw size={15} />
						{/if}
						Relancer tous les fetches
					</button>
				</form>
			</section>

		{:else if ongletActif === 'logs'}

			<!-- Logs système -->
			<section>
				<h2 class="mb-4 text-base font-medium text-foreground flex items-center gap-2">
					<ScrollText size={16} class="text-muted-foreground" aria-hidden="true" />
					Logs système
				</h2>

				{#if data.logsAdmin.length === 0}
					<div class="rounded-xl border border-dashed border-border px-4 py-6 text-center text-sm text-muted-foreground">
						Aucun événement enregistré.
					</div>
				{:else}
					<div class="rounded-xl border border-border bg-card divide-y divide-border font-mono text-xs">
						{#each data.logsAdmin as entree}
							{@const couleurNiveau =
								entree.niveau === 'error' ? 'text-red-600 dark:text-red-400' :
								entree.niveau === 'warn'  ? 'text-yellow-600 dark:text-yellow-400' :
								entree.niveau === 'debug' ? 'text-muted-foreground' :
								'text-foreground'}
							<div class="flex items-baseline gap-3 px-4 py-2">
								<span class="shrink-0 text-muted-foreground tabular-nums">
									{new Date(entree.ts).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
								</span>
								<span class="shrink-0 w-12 {couleurNiveau} uppercase font-medium">{entree.niveau}</span>
								<span class="shrink-0 w-14 text-muted-foreground">{entree.source}</span>
								<span class="text-foreground">{entree.message}{entree.meta ? ' — ' + JSON.stringify(entree.meta) : ''}</span>
							</div>
						{/each}
					</div>
				{/if}
			</section>

		{:else if ongletActif === 'configuration'}

			<!-- Configuration -->
			<section>
				<h2 class="mb-4 text-base font-medium text-foreground flex items-center gap-2">
					<Settings size={16} class="text-muted-foreground" aria-hidden="true" />
					Configuration
				</h2>

				{#if form?.action === 'sauvegarderConfig' && form?.succes}
					<p class="mb-3 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
						Clé <strong>{form.cle}</strong> mise à jour.
					</p>
				{/if}
				{#if form?.action === 'genererVapid' && form?.succes}
					<p class="mb-3 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
						Clés VAPID générées et enregistrées.
					</p>
				{/if}

				<div class="rounded-xl border border-border bg-card divide-y divide-border">
					{#each data.configStatuts as config (config.cle)}
						<div>
							<div class="flex items-center justify-between gap-4 px-4 py-3">
								<div class="min-w-0">
									<p class="text-sm font-mono font-medium text-foreground">{config.cle}</p>
									<p class="text-xs text-muted-foreground mt-0.5">
										{#if config.valeur}
											<span class="font-mono">{config.valeur}</span>
										{:else if config.presente}
											<span class="text-green-600 dark:text-green-400">Configurée en base</span>
										{:else}
											<span>Non configurée</span>
										{/if}
									</p>
								</div>
								<div class="flex shrink-0 items-center gap-1">
									{#if config.cle === 'VAPID_PUBLIC_KEY'}
										<form method="POST" action="?/genererVapid" use:enhance>
											<button
												type="submit"
												class="rounded-md px-2.5 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent"
											>
												Générer
											</button>
										</form>
									{/if}
									<button
										type="button"
										onclick={() => {
											configEditionCle = configEditionCle === config.cle ? null : config.cle;
											configValeur = !config.secret && config.valeur ? config.valeur : '';
										}}
										class="rounded-md px-2.5 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent"
									>
										{configEditionCle === config.cle ? 'Annuler' : 'Modifier'}
									</button>
								</div>
							</div>

							{#if configEditionCle === config.cle}
								<form
									method="POST"
									action="?/sauvegarderConfig"
									class="border-t border-border px-4 py-3 space-y-3"
									use:enhance={() => async ({ result, update }) => {
										if (result.type !== 'failure') {
											configEditionCle = null;
											configValeur = '';
										}
										await update();
									}}
								>
									<input type="hidden" name="cle" value={config.cle} />
									{#if erreurConfigCle === config.cle && erreurConfigMessage}
										<p class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive">
											{erreurConfigMessage}
										</p>
									{/if}
									<div>
										<label class="mb-1 block text-xs text-muted-foreground" for="val-{config.cle}">
											Nouvelle valeur (laisser vide pour supprimer)
										</label>
										<input
											id="val-{config.cle}"
											type={config.secret ? 'password' : 'text'}
											name="valeur"
											bind:value={configValeur}
											autocomplete="off"
											class="w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-foreground/20"
										/>
										{#if config.cle === 'PUBLIC_BASE_URL'}
											<p class="mt-1.5 text-xs text-amber-600 dark:text-amber-400">
												Modifier cette URL invalide les liens de réinitialisation de mot de passe en cours.
											</p>
										{/if}
									</div>
									<div class="flex gap-2">
										<button
											type="submit"
											class="rounded-md bg-foreground px-3 py-1.5 text-xs font-medium text-background transition-colors hover:bg-foreground/90"
										>
											Enregistrer
										</button>
										<button
											type="button"
											onclick={() => { configEditionCle = null; configValeur = ''; }}
											class="rounded-md px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent"
										>
											Annuler
										</button>
									</div>
								</form>
							{/if}
						</div>
					{/each}
				</div>
				<p class="mt-2 text-xs text-muted-foreground">
					Les valeurs sont chiffrées en base. Elles prennent le dessus sur les variables d'environnement.
				</p>
			</section>

		{:else if ongletActif === 'utilisateurs'}

			<!-- Créer un compte -->
			<section>
				<h2 class="mb-4 text-base font-medium text-foreground">Créer un compte</h2>

				{#if form?.succes && form?.motDePasseTemp}
					<div class="mb-4 rounded-xl border border-green-500/30 bg-green-500/10 p-4">
						<p class="mb-2 text-sm font-medium text-green-700 dark:text-green-400">
							Compte créé pour <strong>{form.email}</strong>
						</p>
						<p class="text-sm text-green-700 dark:text-green-400">
							Identifiant temporaire :
							<code class="rounded bg-green-500/20 px-2 py-0.5 font-mono font-semibold">
								{form.identifiant}
							</code>
						</p>
						<p class="mt-1 text-sm text-green-700 dark:text-green-400">
							Mot de passe temporaire :
							<code class="rounded bg-green-500/20 px-2 py-0.5 font-mono font-semibold">
								{form.motDePasseTemp}
							</code>
						</p>
						<p class="mt-2 text-xs text-muted-foreground">
							Communiquez ces identifiants à l'utilisateur. À la première connexion, il choisira son propre identifiant et mot de passe.
						</p>
					</div>
				{/if}

				{#if erreurGlobale}
					<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
						{erreurGlobale}
					</p>
				{/if}

				<form
					method="POST"
					action="?/creerCompte"
					class="flex flex-wrap gap-2"
					use:enhance={() => {
						creationEnCours = true;
						return async ({ update }) => {
							await update();
							creationEnCours = false;
							emailNouvelUser = '';
						};
					}}
				>
					<input
						type="email"
						name="email"
						bind:value={emailNouvelUser}
						placeholder="Email de l'utilisateur"
						required
						class="flex-1 min-w-64 rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
					/>
					<button
						type="submit"
						disabled={creationEnCours || !emailNouvelUser.trim()}
						class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
					>
						<UserPlus size={16} />
						Inviter
					</button>
				</form>
			</section>

			<!-- Liste des utilisateurs -->
			<section>
				<h2 class="mb-4 text-base font-medium text-foreground">
					Utilisateurs ({data.utilisateurs.length})
				</h2>

				{#if form?.action === 'reinitialiserMdp' && form?.succes}
					<p class="mb-3 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
						Email de réinitialisation envoyé à <strong>{form.email}</strong>.
					</p>
				{/if}
				{#if form?.action === 'modifierCompte' && form?.succes}
					<p class="mb-3 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
						Compte mis à jour.
					</p>
				{/if}

				<ul class="space-y-2">
					{#each data.utilisateurs as utilisateur (utilisateur.id)}
						<li class="rounded-xl border border-border bg-card">
							<div class="flex items-center justify-between px-4 py-3">
								<div class="min-w-0">
									<div class="flex items-center gap-2 flex-wrap">
										<span class="font-medium text-card-foreground">{utilisateur.name}</span>
										{#if utilisateur.username}
											<span class="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground font-mono">@{utilisateur.username}</span>
										{/if}
										{#if utilisateur.role === 'admin'}
											<span class="flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
												<ShieldCheck size={11} />
												admin
											</span>
										{/if}
										{#if utilisateur.mustChangeCredentials}
											<span class="rounded-full bg-yellow-500/15 px-2 py-0.5 text-xs text-yellow-700 dark:text-yellow-400">
												premier login en attente
											</span>
										{/if}
										{#if estInactif(utilisateur.derniereActivite)}
											<span class="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
												Inactif
											</span>
										{/if}
									</div>
									<p class="text-sm text-muted-foreground">{utilisateur.email}</p>
									<p class="mt-0.5 text-xs text-muted-foreground">
										{#if utilisateur.derniereActivite}
											Dernière activité : {formaterDateRelative(new Date(utilisateur.derniereActivite).toISOString())}
										{:else}
											Jamais connecté
										{/if}
									</p>
								</div>
								{#if utilisateur.id !== data.utilisateur?.id}
									<div class="flex items-center gap-1 shrink-0">
										{#if confirmationSupprimer === utilisateur.id}
											<span class="text-xs text-destructive mr-1">Supprimer ?</span>
											<form method="POST" action="?/supprimerCompte" use:enhance={() => async ({ update }) => {
												confirmationSupprimer = null;
												await update();
											}}>
												<input type="hidden" name="userId" value={utilisateur.id} />
												<button type="submit" class="rounded-md px-2 py-1 text-xs font-medium text-destructive transition-colors hover:bg-destructive/10">
													Confirmer
												</button>
											</form>
											<button type="button" onclick={() => (confirmationSupprimer = null)}
												class="rounded-md px-2 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent">
												Annuler
											</button>
										{:else if confirmationReset === utilisateur.id}
											<span class="text-xs text-muted-foreground mr-1">Envoyer lien reset ?</span>
											<form method="POST" action="?/reinitialiserMdp" use:enhance={() => async ({ update }) => {
												confirmationReset = null;
												await update();
											}}>
												<input type="hidden" name="userId" value={utilisateur.id} />
												<button type="submit" class="rounded-md px-2 py-1 text-xs font-medium text-foreground transition-colors hover:bg-accent">
													Confirmer
												</button>
											</form>
											<button type="button" onclick={() => (confirmationReset = null)}
												class="rounded-md px-2 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent">
												Annuler
											</button>
										{:else}
											<button type="button"
												onclick={() => { modificationId = utilisateur.id; nomModification = utilisateur.name; emailModification = utilisateur.email; }}
												class="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-accent"
												aria-label="Modifier {utilisateur.name}">
												<Pencil size={15} />
											</button>
											<button type="button"
												onclick={() => (confirmationReset = utilisateur.id)}
												class="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-accent"
												aria-label="Réinitialiser le mot de passe de {utilisateur.name}">
												<KeyRound size={15} />
											</button>
											<button type="button"
												onclick={() => (confirmationSupprimer = utilisateur.id)}
												class="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive"
												aria-label="Supprimer {utilisateur.name}">
												<Trash2 size={15} />
											</button>
										{/if}
									</div>
								{/if}
							</div>

							{#if modificationId === utilisateur.id}
								<form method="POST" action="?/modifierCompte"
									class="border-t border-border px-4 py-3 space-y-3"
									use:enhance={() => async ({ update }) => {
										modificationId = null;
										await update();
									}}
								>
									<input type="hidden" name="userId" value={utilisateur.id} />
									<div class="flex gap-2 flex-wrap">
										<div class="flex-1 min-w-40">
											<label class="mb-1 block text-xs text-muted-foreground" for="nom-{utilisateur.id}">Nom</label>
											<input
												id="nom-{utilisateur.id}"
												type="text"
												name="nom"
												bind:value={nomModification}
												maxlength={100}
												class="w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-foreground/20"
											/>
										</div>
										<div class="flex-1 min-w-40">
											<label class="mb-1 block text-xs text-muted-foreground" for="email-{utilisateur.id}">Email</label>
											<input
												id="email-{utilisateur.id}"
												type="email"
												name="email"
												bind:value={emailModification}
												class="w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-foreground/20"
											/>
										</div>
									</div>
									<div class="flex gap-2">
										<button type="submit"
											class="rounded-md bg-foreground px-3 py-1.5 text-xs font-medium text-background transition-colors hover:bg-foreground/90">
											Enregistrer
										</button>
										<button type="button" onclick={() => (modificationId = null)}
											class="rounded-md px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent">
											Annuler
										</button>
									</div>
								</form>
							{/if}
						</li>
					{/each}
				</ul>
			</section>

		{/if}

	</div>
</div>

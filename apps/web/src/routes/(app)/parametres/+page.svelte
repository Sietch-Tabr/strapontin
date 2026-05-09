<script lang="ts">
	import { untrack } from 'svelte';
	import { enhance } from '$app/forms';
	import { toast } from 'svelte-sonner';
	import { Sun, Moon, Monitor, Save, KeyRound, User, Download, Upload, Bell, Shield } from '@lucide/svelte';
	import type { PageData, ActionData } from './$types';
	import ComboboxDepts from '$lib/components/ui/combobox-depts.svelte';
	import { TOUS_LES_DEPTS } from '$lib/constantes/depts';

	let { data, form }: { data: PageData; form: ActionData } = $props();
	let resendDisponible = $derived(data.resendDisponible);

	let deptsSelectionnes = $state<string[]>(untrack(() => data.config?.departements ?? []));
	let filtreDisponibles = $state<boolean>(untrack(() => data.config?.filtreDisponibles ?? true));
	let frequenceNotifications = $state<string>(untrack(() => data.config?.frequenceNotifications ?? 'jamais'));
	let notifArtistes = $state<boolean>(untrack(() => data.config?.notifArtistes ?? true));
	let notifSalles = $state<boolean>(untrack(() => data.config?.notifSalles ?? false));
	let nom = $state<string>(untrack(() => data.utilisateur?.name ?? ''));
	let email = $state<string>(untrack(() => data.utilisateur?.email ?? ''));
	let identifiant = $state<string>(untrack(() => (data.utilisateur as { username?: string | null } & typeof data.utilisateur)?.username ?? ''));
	let theme = $state<string>(untrack(() => data.theme));

	// Resynchronisation après une action serveur (import, enregistrement)
	$effect(() => {
		deptsSelectionnes = data.config?.departements ?? [];
		filtreDisponibles = data.config?.filtreDisponibles ?? true;
		frequenceNotifications = data.config?.frequenceNotifications ?? 'jamais';
		notifArtistes = data.config?.notifArtistes ?? true;
		notifSalles = data.config?.notifSalles ?? false;
		nom = data.utilisateur?.name ?? '';
		email = data.utilisateur?.email ?? '';
		identifiant = (data.utilisateur as { username?: string | null } & typeof data.utilisateur)?.username ?? '';
	});

	let enregistrementConfig = $state(false);
	let formulaireRecherche = $state<HTMLFormElement | undefined>(undefined);
	let formulaireNotifications = $state<HTMLFormElement | undefined>(undefined);

	function autoSauvegarderRecherche() {
		formulaireRecherche?.requestSubmit();
	}

	function autoSauvegarderNotifications() {
		formulaireNotifications?.requestSubmit();
	}
	let enregistrementCompte = $state(false);
	let enregistrementEmail = $state(false);
	let enregistrementIdentifiant = $state(false);
	let enregistrementMdp = $state(false);
	let activation2FaEnCours = $state(false);
	let desactivation2FaEnCours = $state(false);
	let codeConfirmation2Fa = $state('');
	let erreurConfirmation2Fa = $state('');
	let confirmationEnCours = $state(false);

	async function confirmerActivation2Fa(e: SubmitEvent) {
		e.preventDefault();
		confirmationEnCours = true;
		erreurConfirmation2Fa = '';
		const { authClient: client } = await import('$lib/auth-client');
		const resultat = await client.twoFactor.verifyTotp({ code: codeConfirmation2Fa });
		if (resultat.error) {
			erreurConfirmation2Fa = 'Code incorrect. Vérifiez votre application d\'authentification.';
			confirmationEnCours = false;
		} else {
			window.location.href = '/parametres#deux-facteurs';
		}
	}
	let importEnCours = $state(false);
	let modeImport = $state<'merger' | 'ecraser'>('merger');
	let confirmationSuppression = $state(false);

	// Push
	let pushSupporte = $state(false);
	let pushAbonne = $state<PushSubscription | null>(null);
	let pushClePublique = $state<string | null>(null);
	let pushPermissionRefusee = $state(false);
	let pushEnCours = $state(false);
	let pushErreur = $state<string | null>(null);

	$effect(() => {
		if (typeof window === 'undefined') return;
		if (!('serviceWorker' in navigator) || !('PushManager' in window)) return;
		pushSupporte = true;
		void chargerEtatPush();
	});

	async function chargerEtatPush() {
		try {
			const [reponseVapid, registration] = await Promise.all([
				fetch('/api/push').then((r) => r.json() as Promise<{ disponible: boolean; clePublique: string | null }>),
				navigator.serviceWorker.ready,
			]);
			if (!reponseVapid.disponible) { pushSupporte = false; return; }
			pushClePublique = reponseVapid.clePublique;
			pushAbonne = await registration.pushManager.getSubscription();
			pushPermissionRefusee = Notification.permission === 'denied';
		} catch {
			pushSupporte = false;
		}
	}

	async function activerPush() {
		if (!pushClePublique || pushEnCours) return;
		pushEnCours = true;
		pushErreur = null;
		try {
			const permission = await Notification.requestPermission();
			if (permission !== 'granted') {
				pushPermissionRefusee = permission === 'denied';
				return;
			}
			const registration = await navigator.serviceWorker.ready;
			const abonnement = await registration.pushManager.subscribe({
				userVisibleOnly: true,
				applicationServerKey: urlBase64VersUint8Array(pushClePublique),
			});
			const reponse = await fetch('/api/push', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify(abonnement.toJSON()),
			});
			if (!reponse.ok) throw new Error('Échec enregistrement');
			pushAbonne = abonnement;
		} catch (err) {
			pushErreur = err instanceof Error ? err.message : 'Une erreur est survenue';
		} finally {
			pushEnCours = false;
		}
	}

	async function desactiverPush() {
		if (!pushAbonne || pushEnCours) return;
		pushEnCours = true;
		pushErreur = null;
		try {
			const endpoint = pushAbonne.endpoint;
			await pushAbonne.unsubscribe();
			await fetch('/api/push', {
				method: 'DELETE',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ endpoint }),
			});
			pushAbonne = null;
		} catch (err) {
			pushErreur = err instanceof Error ? err.message : 'Une erreur est survenue';
		} finally {
			pushEnCours = false;
		}
	}

	function urlBase64VersUint8Array(base64: string): ArrayBuffer {
		const rembourrage = '='.repeat((4 - (base64.length % 4)) % 4);
		const b64 = (base64 + rembourrage).replace(/-/g, '+').replace(/_/g, '/');
		const brut = atob(b64);
		const octets = new Uint8Array(brut.length);
		for (let i = 0; i < brut.length; i++) octets[i] = brut.charCodeAt(i);
		return octets.buffer;
	}
</script>

<svelte:head>
	<title>Paramètres — Strapontin</title>
</svelte:head>

<div class="p-6 lg:p-8">
	<div class="mb-8">
		<h1 class="text-2xl font-semibold text-foreground">Paramètres</h1>
	</div>

	<div class="max-w-xl space-y-6">

		<!-- Recherche de concerts -->
		<section class="rounded-lg border border-border bg-card p-6">
			<h2 class="mb-6 text-base font-semibold text-foreground">Recherche de concerts</h2>

			<!-- Départements — bouton explicite car modification destructive possible -->
			<form
				method="POST"
				action="?/departements"
				class="space-y-4"
				use:enhance={() => {
					enregistrementConfig = true;
					return async ({ result, update }) => {
						if (result.type === 'success') toast.success('Départements enregistrés');
						else if (result.type === 'failure') toast.error((result.data?.erreur as string | undefined) ?? 'Une erreur est survenue');
						await update({ reset: false });
						enregistrementConfig = false;
					};
				}}
			>
				<div>
					<p class="mb-1.5 text-sm font-medium text-foreground">Départements</p>
					<input type="hidden" name="departements" value={deptsSelectionnes.join(', ')} />
					<ComboboxDepts
						depts={TOUS_LES_DEPTS}
						actifs={deptsSelectionnes}
						onChange={(actifs) => { deptsSelectionnes = actifs; }}
					/>
				</div>

				<button
					type="submit"
					disabled={enregistrementConfig}
					class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
				>
					<Save size={16} />
					{enregistrementConfig ? 'Enregistrement…' : 'Enregistrer'}
				</button>
			</form>

			<!-- Rayon et filtre — auto-save -->
			<form
				bind:this={formulaireRecherche}
				method="POST"
				action="?/rechercheRapide"
				class="space-y-4 border-t border-border pt-4"
				use:enhance={() => async ({ result, update }) => {
					if (result.type === 'failure') toast.error((result.data?.erreur as string | undefined) ?? 'Une erreur est survenue');
					await update({ reset: false });
				}}
			>
				<div class="flex items-center gap-3">
					<input
						id="filtreDisponibles"
						type="checkbox"
						name="filtreDisponibles"
						bind:checked={filtreDisponibles}
						onchange={autoSauvegarderRecherche}
						class="h-4 w-4 rounded border-border accent-foreground"
					/>
					<label for="filtreDisponibles" class="text-sm text-foreground">
						Masquer par défaut les concerts indisponibles
					</label>
				</div>
			</form>
		</section>

		<!-- Notifications -->
		<section class="rounded-lg border border-border bg-card p-6">
			<div class="mb-6 flex items-center gap-2">
				<Bell size={18} class="text-foreground" aria-hidden="true" />
				<h2 class="text-base font-semibold text-foreground">Notifications</h2>
			</div>

			{#if form?.succes === 'notifications'}
				<p class="mb-4 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
					Préférences de notification enregistrées.
				</p>
			{/if}
			{#if form?.erreur && form?.section === 'notifications'}
				<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
					{form.erreur}
				</p>
			{/if}

			<form
				bind:this={formulaireNotifications}
				method="POST"
				action="?/notifications"
				class="space-y-4"
				use:enhance={() => async ({ result, update }) => {
					if (result.type === 'failure') toast.error((result.data?.erreur as string | undefined) ?? 'Une erreur est survenue');
					await update({ reset: false });
				}}
			>
				{#if resendDisponible}
				<p class="text-sm text-muted-foreground">
					Recevez un email lorsque de nouveaux concerts correspondent à vos préférences.
				</p>

				<div class="space-y-2">
					{#each [
						{ valeur: 'jamais', label: 'Désactivées' },
						{ valeur: 'temps_reel', label: 'Après chaque mise à jour (toutes les 2h)' },
						{ valeur: 'quotidien', label: 'Un récapitulatif quotidien (8h)' },
					] as option (option.valeur)}
						<label class="flex cursor-pointer items-center gap-3 rounded-md border border-border p-3 transition-colors hover:bg-accent {frequenceNotifications === option.valeur ? 'border-foreground bg-accent' : ''}">
							<input
								type="radio"
								name="frequenceNotifications"
								value={option.valeur}
								bind:group={frequenceNotifications}
								onchange={autoSauvegarderNotifications}
								class="accent-foreground"
							/>
							<span class="text-sm text-foreground">{option.label}</span>
						</label>
					{/each}
				</div>

				{#if frequenceNotifications !== 'jamais'}
				<div class="space-y-1 rounded-md border border-border p-4">
					<p class="mb-3 text-sm font-medium text-foreground">Inclure dans les emails</p>
					<label class="flex cursor-pointer items-center gap-3">
						<input
							type="checkbox"
							name="notifArtistes"
							bind:checked={notifArtistes}
							onchange={autoSauvegarderNotifications}
							class="accent-foreground"
						/>
						<span class="text-sm text-foreground">Concerts de mes artistes suivis</span>
					</label>
					<label class="flex cursor-pointer items-center gap-3">
						<input
							type="checkbox"
							name="notifSalles"
							bind:checked={notifSalles}
							onchange={autoSauvegarderNotifications}
							class="accent-foreground"
						/>
						<span class="text-sm text-foreground">Concerts dans mes salles suivies</span>
					</label>
					{#if !notifArtistes && !notifSalles}
					<p class="mt-2 text-xs text-amber-600 dark:text-amber-400">
						Au moins une option doit être cochée pour recevoir des notifications.
					</p>
					{/if}
				</div>
				{/if}
			{:else}
				<p class="text-sm text-muted-foreground">
					Les notifications email ne sont pas disponibles sur cette instance (service email non configuré).
				</p>
			{/if}
			</form>
		</section>

		<!-- Notifications push -->
		{#if pushSupporte}
		<section class="rounded-lg border border-border bg-card p-6">
			<div class="mb-6 flex items-center gap-2">
				<Bell size={18} class="text-foreground" aria-hidden="true" />
				<h2 class="text-base font-semibold text-foreground">Notifications push</h2>
			</div>

			{#if pushErreur}
				<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
					{pushErreur}
				</p>
			{/if}

			{#if pushPermissionRefusee}
				<p class="text-sm text-muted-foreground">
					Les notifications sont bloquées dans votre navigateur. Pour les activer, modifiez les permissions du site dans les paramètres de votre navigateur.
				</p>
			{:else if pushAbonne}
				<p class="mb-4 text-sm text-muted-foreground">
					Les notifications push sont activées sur cet appareil. Vous recevrez une alerte lors de chaque digest.
				</p>
				<button
					type="button"
					onclick={desactiverPush}
					disabled={pushEnCours}
					class="flex items-center gap-2 rounded-md border border-border px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent disabled:opacity-50"
				>
					{pushEnCours ? 'Désactivation…' : 'Désactiver les notifications push'}
				</button>
			{:else}
				<p class="mb-4 text-sm text-muted-foreground">
					Recevez une notification sur cet appareil en même temps que vos emails de digest.
				</p>
				<button
					type="button"
					onclick={activerPush}
					disabled={pushEnCours}
					class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
				>
					<Bell size={16} />
					{pushEnCours ? 'Activation…' : 'Activer les notifications push'}
				</button>
			{/if}
		</section>
		{/if}

		<!-- Apparence -->
		<section class="rounded-lg border border-border bg-card p-6">
			<h2 class="mb-6 text-base font-semibold text-foreground">Apparence</h2>
			<div class="space-y-2">
				<form method="POST" action="?/theme" use:enhance={() => {
					return async ({ result }) => {
						if (result.type === 'success') {
							window.location.reload();
						}
					};
				}}>
					<input type="hidden" name="valeur" value="auto" />
					<button
						type="submit"
						class="flex w-full items-center gap-2 rounded-lg border px-4 py-2.5 text-sm font-medium transition-colors {theme === 'auto'
							? 'border-foreground bg-foreground text-background'
							: 'border-border bg-card text-card-foreground hover:bg-accent hover:text-accent-foreground'}"
					>
						<Monitor size={16} />
						Automatique
					</button>
				</form>
				<div class="flex gap-2">
					{#each [
						{ valeur: 'clair', Icone: Sun, label: 'Clair' },
						{ valeur: 'sombre', Icone: Moon, label: 'Sombre' },
					] as option (option.valeur)}
						<form class="flex-1" method="POST" action="?/theme" use:enhance={() => {
							return async ({ result }) => {
								if (result.type === 'success') {
									window.location.reload();
								}
							};
						}}>
							<input type="hidden" name="valeur" value={option.valeur} />
							<button
								type="submit"
								class="flex w-full items-center justify-center gap-2 rounded-lg border px-4 py-2.5 text-sm font-medium transition-colors {theme === option.valeur
									? 'border-foreground bg-foreground text-background'
									: 'border-border bg-card text-card-foreground hover:bg-accent hover:text-accent-foreground'}"
							>
								<option.Icone size={16} />
								{option.label}
							</button>
						</form>
					{/each}
				</div>
			</div>
		</section>

		<!-- Mon compte -->
		<section class="rounded-lg border border-border bg-card p-6">
			<h2 class="mb-6 text-base font-semibold text-foreground">Mon compte</h2>

			<!-- Nom d'affichage -->
			<div>
				<h3 id="titre-nom" class="mb-4 text-sm font-medium text-muted-foreground">Nom d'affichage</h3>

				{#if form?.succes === 'compte'}
					<p class="mb-4 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
						Nom mis à jour.
					</p>
				{/if}
				{#if form?.erreur && form?.section === 'compte'}
					<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
						{form.erreur}
					</p>
				{/if}

				<form
					method="POST"
					action="?/compte"
					class="space-y-4"
					use:enhance={() => {
						enregistrementCompte = true;
						return async ({ result, update }) => {
							if (result.type === 'success') toast.success('Nom mis à jour');
							else if (result.type === 'failure') toast.error((result.data?.erreur as string | undefined) ?? 'Une erreur est survenue');
							await update({ reset: false });
							enregistrementCompte = false;
						};
					}}
				>
					<input
						id="nom"
						type="text"
						name="nom"
						bind:value={nom}
						maxlength="100"
						aria-labelledby="titre-nom"
						class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
					/>
					<button
						type="submit"
						disabled={enregistrementCompte}
						class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
					>
						<User size={16} />
						{enregistrementCompte ? 'Mise à jour…' : 'Mettre à jour'}
					</button>
				</form>
			</div>

			<hr class="my-6 border-border" />

			<!-- Identifiant -->
			<div>
				<h3 id="titre-identifiant" class="mb-4 text-sm font-medium text-muted-foreground">Identifiant de connexion</h3>

				{#if form?.succes === 'identifiant'}
					<p class="mb-4 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
						Identifiant mis à jour.
					</p>
				{/if}
				{#if form?.erreur && form?.section === 'identifiant'}
					<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
						{form.erreur}
					</p>
				{/if}

				<form
					method="POST"
					action="?/identifiant"
					class="space-y-4"
					use:enhance={() => {
						enregistrementIdentifiant = true;
						return async ({ result, update }) => {
							if (result.type === 'success') toast.success('Identifiant mis à jour');
							else if (result.type === 'failure') toast.error((result.data?.erreur as string | undefined) ?? 'Une erreur est survenue');
							await update({ reset: false });
							enregistrementIdentifiant = false;
						};
					}}
				>
					<div>
						<input
							id="identifiant"
							type="text"
							name="identifiant"
							bind:value={identifiant}
							minlength="3"
							maxlength="30"
							pattern="[a-zA-Z0-9_]+"
							autocomplete="username"
							aria-labelledby="titre-identifiant"
							class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
						/>
						<p class="mt-1 text-xs text-muted-foreground">3 à 30 caractères, lettres, chiffres et _ uniquement. Insensible à la casse.</p>
					</div>
					<button
						type="submit"
						disabled={enregistrementIdentifiant}
						class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
					>
						<User size={16} />
						{enregistrementIdentifiant ? 'Mise à jour…' : 'Mettre à jour'}
					</button>
				</form>
			</div>

			<hr class="my-6 border-border" />

			<!-- Email -->
			<div>
				<h3 id="titre-email" class="mb-4 text-sm font-medium text-muted-foreground">Adresse email</h3>
				<p class="mb-3 text-xs text-muted-foreground">Utilisée pour les notifications et la réinitialisation du mot de passe.</p>

				{#if form?.succes === 'email'}
					<p class="mb-4 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
						Adresse email mise à jour.
					</p>
				{/if}
				{#if form?.erreur && form?.section === 'email'}
					<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
						{form.erreur}
					</p>
				{/if}

				<form
					method="POST"
					action="?/email"
					class="space-y-4"
					use:enhance={() => {
						enregistrementEmail = true;
						return async ({ result, update }) => {
							if (result.type === 'success') toast.success('Adresse email mise à jour');
							else if (result.type === 'failure') toast.error((result.data?.erreur as string | undefined) ?? 'Une erreur est survenue');
							await update({ reset: false });
							enregistrementEmail = false;
						};
					}}
				>
					<input
						id="email"
						type="email"
						name="email"
						bind:value={email}
						maxlength="254"
						autocomplete="email"
						aria-labelledby="titre-email"
						class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
					/>
					<button
						type="submit"
						disabled={enregistrementEmail}
						class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
					>
						<User size={16} />
						{enregistrementEmail ? 'Mise à jour…' : 'Mettre à jour'}
					</button>
				</form>
			</div>

			<hr class="my-6 border-border" />

			<!-- Mot de passe -->
			<div>
				<h3 class="mb-4 text-sm font-medium text-muted-foreground">Mot de passe</h3>

				{#if form?.succes === 'motDePasse'}
					<p class="mb-4 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
						Mot de passe modifié. Les autres sessions ont été révoquées.
					</p>
				{/if}
				{#if form?.erreur && form?.section === 'motDePasse'}
					<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
						{form.erreur}
					</p>
				{/if}

				<form method="POST" action="?/motDePasse" class="space-y-4" use:enhance={() => {
					enregistrementMdp = true;
					return async ({ result, update }) => {
						if (result.type === 'success') toast.success('Mot de passe modifié. Les autres sessions ont été révoquées.');
						else if (result.type === 'failure') toast.error((result.data?.erreur as string | undefined) ?? 'Une erreur est survenue');
						await update();
						enregistrementMdp = false;
					};
				}}>
					<div>
						<label for="motDePasseActuel" class="mb-1.5 block text-sm font-medium text-foreground">
							Mot de passe actuel
						</label>
						<input
							id="motDePasseActuel"
							type="password"
							name="motDePasseActuel"
							autocomplete="current-password"
							class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
						/>
					</div>

					<div>
						<label for="motDePasseNouveau" class="mb-1.5 block text-sm font-medium text-foreground">
							Nouveau mot de passe
						</label>
						<input
							id="motDePasseNouveau"
							type="password"
							name="motDePasseNouveau"
							minlength="12"
							placeholder="12 caractères minimum"
							autocomplete="new-password"
							class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
						/>
					</div>

					<div>
						<label for="motDePasseConfirmation" class="mb-1.5 block text-sm font-medium text-foreground">
							Confirmer le nouveau mot de passe
						</label>
						<input
							id="motDePasseConfirmation"
							type="password"
							name="motDePasseConfirmation"
							minlength="12"
							autocomplete="new-password"
							class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
						/>
					</div>

					<button
						type="submit"
						disabled={enregistrementMdp}
						class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
					>
						<KeyRound size={16} />
						{enregistrementMdp ? 'Modification…' : 'Modifier le mot de passe'}
					</button>
				</form>
			</div>
		</section>

		<!-- Double authentification -->
		<section id="deux-facteurs" class="rounded-lg border border-border bg-card p-6">
			<div class="mb-6 flex items-center gap-2">
				<Shield size={18} class="text-foreground" aria-hidden="true" />
				<h2 class="text-base font-semibold text-foreground">Double authentification (2FA)</h2>
			</div>

			{#if data.utilisateur?.twoFactorEnabled}
				<!-- 2FA activée -->
				<div class="mb-4 flex items-center gap-2">
					<span class="inline-block h-2 w-2 rounded-full bg-green-500"></span>
					<span class="text-sm text-green-700 dark:text-green-400">Double authentification activée</span>
				</div>
				<p class="mb-4 text-sm text-muted-foreground">
					Votre compte est protégé par une application d'authentification TOTP.
				</p>

				{#if form?.succes === 'desactiverDeuxFacteurs'}
					<p class="mb-4 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
						Double authentification désactivée.
					</p>
				{:else}
					{#if form?.erreur && form?.section === 'deuxFacteurs'}
						<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
							{form.erreur}
						</p>
					{/if}
					<form method="POST" action="?/desactiverDeuxFacteurs" class="space-y-3" use:enhance={() => {
						desactivation2FaEnCours = true;
						return async ({ result, update }) => {
							if (result.type === 'success') toast.success('Double authentification désactivée.');
							else if (result.type === 'failure') toast.error((result.data?.erreur as string | undefined) ?? 'Une erreur est survenue.');
							await update();
							desactivation2FaEnCours = false;
						};
					}}>
						<div>
							<label for="mdp-desactiver-2fa" class="mb-1.5 block text-sm font-medium text-foreground">
								Confirmer avec votre mot de passe
							</label>
							<input
								id="mdp-desactiver-2fa"
								type="password"
								name="motDePasse"
								required
								autocomplete="current-password"
								class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
							/>
						</div>
						<button
							type="submit"
							disabled={desactivation2FaEnCours}
							class="rounded-md border border-destructive/50 px-4 py-2 text-sm font-medium text-destructive transition-colors hover:bg-destructive/10 disabled:opacity-50"
						>
							{desactivation2FaEnCours ? 'Désactivation…' : 'Désactiver la double authentification'}
						</button>
					</form>
				{/if}
			{:else}
				<!-- 2FA non activée -->
				<p class="mb-4 text-sm text-muted-foreground">
					Protégez votre compte avec une application d'authentification (Google Authenticator, Authy, 1Password…).
					Un code temporaire vous sera demandé à chaque connexion.
				</p>

				{#if form?.succes === 'activerDeuxFacteurs'}
					<!-- Affichage du QR code après activation -->
					<div class="mb-4 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
						Double authentification activée. Scannez le QR code ci-dessous avec votre application.
					</div>
					<div class="space-y-6">
						<div>
							<p class="mb-3 text-sm font-medium text-foreground">Scannez ce QR code</p>
							<img src={form.qrCodeDataUrl} alt="QR code TOTP" class="rounded-md border border-border" width="200" height="200" />
						</div>
						<div>
							<p class="mb-2 text-sm font-medium text-foreground">Codes de secours</p>
							<p class="mb-3 text-sm text-muted-foreground">
								Conservez ces codes en lieu sûr. Chacun ne peut être utilisé qu'une seule fois en cas de perte d'accès à votre application.
							</p>
							<div class="grid grid-cols-2 gap-1.5 font-mono text-sm sm:grid-cols-3">
								{#each (form.backupCodes ?? []) as code (code)}
									<code class="rounded bg-muted px-2 py-1 text-muted-foreground">{code}</code>
								{/each}
							</div>
						</div>
						<div class="border-t border-border pt-4">
							<p class="mb-3 text-sm font-medium text-foreground">Confirmer l'activation</p>
							<p class="mb-3 text-sm text-muted-foreground">Saisissez le code affiché dans votre application pour finaliser l'activation.</p>
							<form onsubmit={confirmerActivation2Fa} class="space-y-3">
								<input
									type="text"
									inputmode="numeric"
									pattern="[0-9]{6}"
									maxlength="6"
									bind:value={codeConfirmation2Fa}
									required
									autocomplete="one-time-code"
									placeholder="000000"
									class="w-full rounded-md border border-input bg-background px-3 py-2 text-center text-lg tracking-widest ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
								/>
								{#if erreurConfirmation2Fa}
									<p class="text-sm text-destructive">{erreurConfirmation2Fa}</p>
								{/if}
								<button
									type="submit"
									disabled={confirmationEnCours}
									class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
								>
									<Shield size={16} />
									{confirmationEnCours ? 'Vérification…' : 'Confirmer l\'activation'}
								</button>
							</form>
						</div>
					</div>
				{:else}
					{#if form?.erreur && form?.section === 'deuxFacteurs'}
						<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
							{form.erreur}
						</p>
					{/if}
					<form method="POST" action="?/activerDeuxFacteurs" class="space-y-3" use:enhance={() => {
						activation2FaEnCours = true;
						return async ({ result, update }) => {
							if (result.type === 'failure') toast.error((result.data?.erreur as string | undefined) ?? 'Une erreur est survenue.');
							await update();
							activation2FaEnCours = false;
						};
					}}>
						<div>
							<label for="mdp-activer-2fa" class="mb-1.5 block text-sm font-medium text-foreground">
								Confirmer avec votre mot de passe
							</label>
							<input
								id="mdp-activer-2fa"
								type="password"
								name="motDePasse"
								required
								autocomplete="current-password"
								class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
							/>
						</div>
						<button
							type="submit"
							disabled={activation2FaEnCours}
							class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
						>
							<Shield size={16} />
							{activation2FaEnCours ? 'Activation…' : 'Activer la double authentification'}
						</button>
					</form>
				{/if}
			{/if}
		</section>

		<!-- Export / Import -->
		<section class="rounded-lg border border-border bg-card p-6">
			<h2 class="mb-2 text-base font-semibold text-foreground">Export / Import</h2>
			<p class="mb-6 text-sm text-muted-foreground">
				Exportez vos artistes et salles suivis (JSON), ou importez un fichier existant.
			</p>

			<!-- Export -->
			<div class="mb-6">
				<h3 class="mb-3 text-sm font-medium text-muted-foreground">Exporter mes préférences</h3>
				<a
					href="/api/preferences"
					download
					class="inline-flex items-center gap-2 rounded-md border border-border px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent"
				>
					<Download size={16} />
					Télécharger le fichier JSON
				</a>
			</div>

			<hr class="mb-6 border-border" />

			<!-- Import -->
			<div>
				<h3 class="mb-3 text-sm font-medium text-muted-foreground">Importer des préférences</h3>

				{#if form?.succes === 'import'}
					<p class="mb-4 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
						{form.message}
					</p>
				{/if}
				{#if form?.erreur && form?.section === 'import'}
					<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
						{form.erreur}
					</p>
				{/if}

				<form
					method="POST"
					action="?/importerPreferences"
					enctype="multipart/form-data"
					class="space-y-4"
					use:enhance={() => {
						importEnCours = true;
						return async ({ result, update }) => {
							if (result.type === 'success') toast.success((result.data?.message as string | undefined) ?? 'Import réussi');
							else if (result.type === 'failure') toast.error((result.data?.erreur as string | undefined) ?? 'Une erreur est survenue');
							await update({ reset: true });
							importEnCours = false;
						};
					}}
				>
					<div>
						<label for="fichier-import" class="mb-1.5 block text-sm font-medium text-foreground">
							Fichier JSON
						</label>
						<input
							id="fichier-import"
							type="file"
							name="fichier"
							accept=".json,application/json"
							required
							class="block w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground file:mr-3 file:rounded file:border-0 file:bg-muted file:px-2 file:py-1 file:text-xs file:font-medium file:text-foreground"
						/>
					</div>

					<div class="flex items-start gap-2.5">
						<input
							id="importer-config"
							type="checkbox"
							name="importerConfig"
							class="mt-0.5 h-4 w-4 rounded border-border accent-foreground"
						/>
						<label for="importer-config" class="text-sm text-foreground">
							<span class="font-medium">Importer également la configuration</span>
							<span class="text-muted-foreground"> — départements, rayon, filtres</span>
						</label>
					</div>

					<div class="space-y-2">
						<p class="text-sm font-medium text-foreground">Gestion des doublons</p>
						<label class="flex cursor-pointer items-start gap-2.5">
							<input
								type="radio"
								name="mode"
								value="merger"
								bind:group={modeImport}
								class="mt-0.5 accent-foreground"
							/>
							<span class="text-sm text-foreground">
								<span class="font-medium">Fusionner</span>
								<span class="text-muted-foreground"> — ajoute les artistes et salles absents, conserve l'existant</span>
							</span>
						</label>
						<label class="flex cursor-pointer items-start gap-2.5">
							<input
								type="radio"
								name="mode"
								value="ecraser"
								bind:group={modeImport}
								class="mt-0.5 accent-foreground"
							/>
							<span class="text-sm text-foreground">
								<span class="font-medium">Écraser</span>
								<span class="text-muted-foreground"> — remplace toutes vos préférences par celles du fichier</span>
							</span>
						</label>
						{#if modeImport === 'ecraser'}
							<p class="rounded-md border border-amber-300/50 bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:border-amber-700/50 dark:bg-amber-950/40 dark:text-amber-300">
								Attention : vos artistes et salles actuels seront supprimés avant l'import.
							</p>
						{/if}
					</div>

					<button
						type="submit"
						disabled={importEnCours}
						class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
					>
						<Upload size={16} />
						{importEnCours ? 'Import en cours…' : 'Importer'}
					</button>
				</form>
			</div>
		</section>

		<!-- Zone de danger -->
		<section class="rounded-lg border border-destructive/30 bg-card p-6">
			<h2 class="mb-2 text-base font-semibold text-destructive">Zone de danger</h2>
			<p class="mb-4 text-sm text-muted-foreground">
				La suppression de votre compte est définitive et irréversible. Toutes vos données (artistes, salles, préférences) seront effacées.
			</p>

			{#if !confirmationSuppression}
				<button
					type="button"
					onclick={() => (confirmationSuppression = true)}
					class="rounded-md border border-destructive/50 px-4 py-2 text-sm font-medium text-destructive transition-colors hover:bg-destructive/10"
				>
					Supprimer mon compte
				</button>
			{:else}
				<div class="space-y-3">
					<p class="text-sm font-medium text-destructive">
						Cette action est irréversible. Êtes-vous sûr ?
					</p>
					<form method="POST" action="?/supprimerCompte" class="flex gap-2">
						<button
							type="submit"
							class="rounded-md bg-destructive px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-destructive/90"
						>
							Oui, supprimer définitivement
						</button>
						<button
							type="button"
							onclick={() => (confirmationSuppression = false)}
							class="rounded-md border border-border px-4 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-accent"
						>
							Annuler
						</button>
					</form>
				</div>
			{/if}
		</section>

	</div>
</div>

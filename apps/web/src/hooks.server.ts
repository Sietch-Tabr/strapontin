import { auth } from '$lib/auth';
import { svelteKitHandler } from 'better-auth/svelte-kit';
import type { Handle } from '@sveltejs/kit';

export const handle: Handle = async ({ event, resolve }) => {
	// Lecture du cookie thème pour éviter le FOUC
	const theme = event.cookies.get('theme') ?? 'light';
	event.locals.theme = theme;

	return svelteKitHandler({ event, resolve, auth });
};

// Strapontin — service worker (push notifications)

self.addEventListener('push', event => {
  if (!event.data) return;
  let data;
  try {
    data = event.data.json();
  } catch (_) {
    return;
  }
  event.waitUntil(
    self.registration.showNotification(data.title || 'Strapontin', {
      body: data.body || '',
      icon: data.icon || '/images/icon-192.png',
      badge: '/images/icon-192.png',
      data: { url: data.url || '/' }
    })
  );
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const url = event.notification.data?.url || '/';
  event.waitUntil(
    clients
      .matchAll({ type: 'window', includeUncontrolled: true })
      .then(liste => {
        for (const client of liste) {
          if (client.url === url && 'focus' in client) return client.focus();
        }
        if (clients.openWindow) return clients.openWindow(url);
      })
  );
});

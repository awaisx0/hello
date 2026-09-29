const express = require('express');
const { randomUUID } = require('crypto');
const db = require('../db');
const { publicKey } = require('../push');

const router = express.Router();

router.get('/push/vapid-public-key', (req, res) => {
  res.json({ publicKey: publicKey || null });
});

router.post('/push/subscribe', (req, res) => {
  const sub = req.body;
  if (!sub || !sub.endpoint || !sub.keys || !sub.keys.p256dh || !sub.keys.auth) {
    return res.status(400).json({ error: 'Invalid subscription' });
  }
  db.prepare(`
    INSERT INTO push_subscriptions (id, endpoint, p256dh, auth, created_at)
    VALUES (@id, @endpoint, @p256dh, @auth, @created_at)
    ON CONFLICT(endpoint) DO UPDATE SET p256dh = excluded.p256dh, auth = excluded.auth
  `).run({
    id: randomUUID(),
    endpoint: sub.endpoint,
    p256dh: sub.keys.p256dh,
    auth: sub.keys.auth,
    created_at: new Date().toISOString(),
  });
  res.status(204).end();
});

router.post('/push/unsubscribe', (req, res) => {
  db.prepare('DELETE FROM push_subscriptions WHERE endpoint = ?').run((req.body && req.body.endpoint) || '');
  res.status(204).end();
});

module.exports = router;

const cron = require('node-cron');
const db = require('./db');
const { webpush, configured } = require('./push');

function sendToAll(payload) {
  if (!configured) return;
  const subs = db.prepare('SELECT * FROM push_subscriptions').all();
  const body = JSON.stringify(payload);
  subs.forEach((sub) => {
    webpush
      .sendNotification({ endpoint: sub.endpoint, keys: { p256dh: sub.p256dh, auth: sub.auth } }, body)
      .catch((err) => {
        if (err.statusCode === 404 || err.statusCode === 410) {
          db.prepare('DELETE FROM push_subscriptions WHERE id = ?').run(sub.id);
        } else {
          console.error('Push send failed:', err.message);
        }
      });
  });
}

// Two-stage reminder per todo: once right at the due moment, once 24h later
// if it's still not done. Flags on the row prevent re-notifying every minute.
function checkReminders() {
  const now = new Date();
  const todos = db.prepare(`
    SELECT * FROM todos
    WHERE deleted_at IS NULL AND completed = 0 AND due_date IS NOT NULL
      AND (notified_due = 0 OR notified_overdue = 0)
  `).all();

  todos.forEach((todo) => {
    const dueDateTime = new Date(`${todo.due_date}T${todo.due_time || '23:59'}:00`);
    if (Number.isNaN(dueDateTime.getTime())) return;

    if (!todo.notified_due && now >= dueDateTime) {
      sendToAll({ title: 'Todo due', body: todo.title, tag: `due-${todo.id}` });
      db.prepare('UPDATE todos SET notified_due = 1 WHERE id = ?').run(todo.id);
    }

    const overdueThreshold = new Date(dueDateTime.getTime() + 24 * 60 * 60 * 1000);
    if (!todo.notified_overdue && now >= overdueThreshold) {
      sendToAll({ title: 'Todo overdue', body: todo.title, tag: `overdue-${todo.id}` });
      db.prepare('UPDATE todos SET notified_overdue = 1 WHERE id = ?').run(todo.id);
    }
  });
}

function start() {
  cron.schedule('* * * * *', checkReminders);
  console.log('Reminder scheduler started' + (configured ? '' : ' (push not configured — set VAPID keys in .env to enable)'));
}

module.exports = { start, checkReminders };

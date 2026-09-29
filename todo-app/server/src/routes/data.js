const express = require('express');
const db = require('../db');

const router = express.Router();

router.get('/export', (req, res) => {
  const todos = db.prepare('SELECT * FROM todos').all();
  const subtasks = db.prepare('SELECT * FROM subtasks').all();
  res.setHeader('Content-Disposition', `attachment; filename="todo-backup-${new Date().toISOString().slice(0, 10)}.json"`);
  res.json({ exportedAt: new Date().toISOString(), version: 1, todos, subtasks });
});

// Full restore from a backup produced by /export. Replaces all current data —
// the client warns before calling this.
router.post('/import', (req, res) => {
  const { todos, subtasks } = req.body || {};
  if (!Array.isArray(todos) || !Array.isArray(subtasks)) {
    return res.status(400).json({ error: 'Invalid backup file: expected { todos: [], subtasks: [] }' });
  }

  const insertTodo = db.prepare(`
    INSERT INTO todos (id, title, notes, category, priority, due_date, due_time,
      completed, completed_at, created_at, updated_at, series_id, recur_interval, recur_unit,
      prev_instance_id, next_instance_id, notified_due, notified_overdue, deleted_at, position)
    VALUES (@id, @title, @notes, @category, @priority, @due_date, @due_time,
      @completed, @completed_at, @created_at, @updated_at, @series_id, @recur_interval, @recur_unit,
      @prev_instance_id, @next_instance_id, @notified_due, @notified_overdue, @deleted_at, @position)
  `);
  const insertSub = db.prepare(
    'INSERT INTO subtasks (id, todo_id, text, done, position) VALUES (@id, @todo_id, @text, @done, @position)'
  );

  const tx = db.transaction(() => {
    db.prepare('DELETE FROM subtasks').run();
    db.prepare('DELETE FROM todos').run();
    for (const t of todos) insertTodo.run(t);
    for (const s of subtasks) insertSub.run(s);
  });

  try {
    tx();
  } catch (err) {
    return res.status(400).json({ error: `Import failed: ${err.message}` });
  }

  res.status(204).end();
});

module.exports = router;

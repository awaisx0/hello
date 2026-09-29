const express = require('express');
const { randomUUID } = require('crypto');
const db = require('../db');
const { addInterval } = require('../recurrence');

const router = express.Router();
const nowIso = () => new Date().toISOString();
const today = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

function serializeTodo(row) {
  if (!row) return null;
  const subtasks = db.prepare('SELECT * FROM subtasks WHERE todo_id = ? ORDER BY position, rowid').all(row.id);
  return { ...row, completed: !!row.completed, subtasks: subtasks.map((s) => ({ ...s, done: !!s.done })) };
}

const getTodo = db.prepare('SELECT * FROM todos WHERE id = ?');

// ---- Listing ----

router.get('/todos', (req, res) => {
  const rows = db.prepare('SELECT * FROM todos WHERE deleted_at IS NULL ORDER BY created_at').all();
  res.json(rows.map(serializeTodo));
});

router.get('/trash', (req, res) => {
  const rows = db.prepare('SELECT * FROM todos WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC').all();
  res.json(rows.map(serializeTodo));
});

// ---- Create / Update ----

router.post('/todos', (req, res) => {
  const b = req.body || {};
  if (!b.title || !b.title.trim()) return res.status(400).json({ error: 'Title is required' });
  if (b.recurInterval && !['days', 'weeks', 'months'].includes(b.recurUnit)) {
    return res.status(400).json({ error: 'Invalid recurrence unit' });
  }

  const id = randomUUID();
  const ts = nowIso();
  db.prepare(`
    INSERT INTO todos (id, title, notes, category, priority, due_date, due_time,
      completed, created_at, updated_at, series_id, recur_interval, recur_unit)
    VALUES (@id, @title, @notes, @category, @priority, @due_date, @due_time,
      0, @created_at, @updated_at, @series_id, @recur_interval, @recur_unit)
  `).run({
    id,
    title: b.title.trim(),
    notes: b.notes || '',
    category: (b.category || '').trim(),
    priority: ['low', 'medium', 'high'].includes(b.priority) ? b.priority : 'medium',
    due_date: b.dueDate || null,
    due_time: b.dueDate ? (b.dueTime || null) : null,
    created_at: ts,
    updated_at: ts,
    series_id: b.recurInterval ? id : null,
    recur_interval: b.recurInterval || null,
    recur_unit: b.recurInterval ? b.recurUnit : null,
  });

  if (Array.isArray(b.subtasks)) {
    const insertSub = db.prepare('INSERT INTO subtasks (id, todo_id, text, done, position) VALUES (?, ?, ?, 0, ?)');
    b.subtasks.forEach((t, i) => {
      const text = (t || '').trim();
      if (text) insertSub.run(randomUUID(), id, text, i);
    });
  }

  res.status(201).json(serializeTodo(getTodo.get(id)));
});

router.put('/todos/:id', (req, res) => {
  const existing = getTodo.get(req.params.id);
  if (!existing || existing.deleted_at) return res.status(404).json({ error: 'Not found' });
  const b = req.body || {};
  if (b.recurInterval && b.recurUnit && !['days', 'weeks', 'months'].includes(b.recurUnit)) {
    return res.status(400).json({ error: 'Invalid recurrence unit' });
  }

  const nextDueDate = b.dueDate === undefined ? existing.due_date : (b.dueDate || null);
  const nextDueTime = b.dueTime === undefined ? existing.due_time : (b.dueTime || null);
  const dueChanged = nextDueDate !== existing.due_date || nextDueTime !== existing.due_time;
  const recurInterval = b.recurInterval === undefined ? existing.recur_interval : (b.recurInterval || null);
  const recurUnit = recurInterval ? (b.recurUnit || existing.recur_unit) : null;

  const tx = db.transaction(() => {
    db.prepare(`
      UPDATE todos SET
        title = @title, notes = @notes, category = @category, priority = @priority,
        due_date = @due_date, due_time = @due_time,
        recur_interval = @recur_interval, recur_unit = @recur_unit,
        series_id = @series_id, updated_at = @updated_at,
        notified_due = CASE WHEN @dueChanged THEN 0 ELSE notified_due END,
        notified_overdue = CASE WHEN @dueChanged THEN 0 ELSE notified_overdue END
      WHERE id = @id
    `).run({
      id: existing.id,
      title: (b.title !== undefined ? b.title : existing.title).trim(),
      notes: b.notes !== undefined ? b.notes : existing.notes,
      category: (b.category !== undefined ? b.category : existing.category || '').trim(),
      priority: ['low', 'medium', 'high'].includes(b.priority) ? b.priority : existing.priority,
      due_date: nextDueDate,
      due_time: nextDueDate ? nextDueTime : null,
      recur_interval: recurInterval,
      recur_unit: recurUnit,
      series_id: existing.series_id || (recurInterval ? existing.id : null),
      updated_at: nowIso(),
      dueChanged: dueChanged ? 1 : 0,
    });

    // Full replace-on-save: the edit modal sends the complete subtask list
    // it ended up with, so the simplest correct move is to swap them in.
    if (Array.isArray(b.subtasks)) {
      db.prepare('DELETE FROM subtasks WHERE todo_id = ?').run(existing.id);
      const insertSub = db.prepare('INSERT INTO subtasks (id, todo_id, text, done, position) VALUES (?, ?, ?, ?, ?)');
      b.subtasks.forEach((s, i) => {
        const text = (s.text || '').trim();
        if (text) insertSub.run(s.id || randomUUID(), existing.id, text, s.done ? 1 : 0, i);
      });
    }
  });
  tx();

  res.json(serializeTodo(getTodo.get(existing.id)));
});

// ---- Completion / recurrence ----

router.post('/todos/:id/complete', (req, res) => {
  const todo = getTodo.get(req.params.id);
  if (!todo || todo.deleted_at) return res.status(404).json({ error: 'Not found' });
  if (todo.completed) return res.status(400).json({ error: 'Already completed' });

  const completedAt = (req.body && req.body.completedAt) || today();
  const ts = nowIso();
  let spawnedId = null;

  const tx = db.transaction(() => {
    db.prepare('UPDATE todos SET completed = 1, completed_at = ?, updated_at = ? WHERE id = ?')
      .run(completedAt, ts, todo.id);

    if (todo.recur_interval && todo.recur_unit) {
      const nextDue = addInterval(completedAt, todo.recur_interval, todo.recur_unit);
      spawnedId = randomUUID();
      db.prepare(`
        INSERT INTO todos (id, title, notes, category, priority, due_date, due_time,
          completed, created_at, updated_at, series_id, recur_interval, recur_unit, prev_instance_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
      `).run(
        spawnedId, todo.title, todo.notes, todo.category, todo.priority, nextDue, todo.due_time,
        ts, ts, todo.series_id || todo.id, todo.recur_interval, todo.recur_unit, todo.id
      );

      const subtasks = db.prepare('SELECT * FROM subtasks WHERE todo_id = ? ORDER BY position, rowid').all(todo.id);
      const insertSub = db.prepare('INSERT INTO subtasks (id, todo_id, text, done, position) VALUES (?, ?, ?, 0, ?)');
      subtasks.forEach((s) => insertSub.run(randomUUID(), spawnedId, s.text, s.position));

      db.prepare('UPDATE todos SET next_instance_id = ? WHERE id = ?').run(spawnedId, todo.id);
    }
  });
  tx();

  res.json({
    completed: serializeTodo(getTodo.get(todo.id)),
    next: spawnedId ? serializeTodo(getTodo.get(spawnedId)) : null,
  });
});

router.post('/todos/:id/reopen', (req, res) => {
  const todo = getTodo.get(req.params.id);
  if (!todo || !todo.completed) return res.status(404).json({ error: 'Not found or not completed' });

  const tx = db.transaction(() => {
    if (todo.next_instance_id) {
      const next = getTodo.get(todo.next_instance_id);
      // Only remove the spawned next occurrence if it's still untouched
      // (nobody has completed or deleted it yet) — otherwise leave it be.
      if (next && !next.completed && !next.deleted_at) {
        db.prepare('DELETE FROM todos WHERE id = ?').run(next.id);
      }
    }
    db.prepare('UPDATE todos SET completed = 0, completed_at = NULL, next_instance_id = NULL, updated_at = ? WHERE id = ?')
      .run(nowIso(), todo.id);
  });
  tx();

  res.json(serializeTodo(getTodo.get(todo.id)));
});

// Backdate/forward-date when a todo was actually completed. If the next
// recurrence has already been auto-spawned and is still untouched, its due
// date is recomputed from this new completion date.
router.patch('/todos/:id/completed-at', (req, res) => {
  const todo = getTodo.get(req.params.id);
  if (!todo || !todo.completed) return res.status(404).json({ error: 'Not found or not completed' });
  const completedAt = req.body && req.body.completedAt;
  if (!completedAt) return res.status(400).json({ error: 'completedAt is required' });

  const tx = db.transaction(() => {
    db.prepare('UPDATE todos SET completed_at = ?, updated_at = ? WHERE id = ?').run(completedAt, nowIso(), todo.id);
    if (todo.next_instance_id && todo.recur_interval && todo.recur_unit) {
      const next = getTodo.get(todo.next_instance_id);
      if (next && !next.completed && !next.deleted_at) {
        const nextDue = addInterval(completedAt, todo.recur_interval, todo.recur_unit);
        db.prepare('UPDATE todos SET due_date = ?, updated_at = ? WHERE id = ?').run(nextDue, nowIso(), next.id);
      }
    }
  });
  tx();

  res.json(serializeTodo(getTodo.get(todo.id)));
});

// ---- Trash ----

router.delete('/todos/:id', (req, res) => {
  const result = db.prepare('UPDATE todos SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL')
    .run(nowIso(), req.params.id);
  if (result.changes === 0) return res.status(404).json({ error: 'Not found' });
  res.status(204).end();
});

router.post('/todos/:id/restore', (req, res) => {
  const result = db.prepare('UPDATE todos SET deleted_at = NULL WHERE id = ?').run(req.params.id);
  if (result.changes === 0) return res.status(404).json({ error: 'Not found' });
  res.json(serializeTodo(getTodo.get(req.params.id)));
});

router.delete('/todos/:id/permanent', (req, res) => {
  const result = db.prepare('DELETE FROM todos WHERE id = ?').run(req.params.id);
  if (result.changes === 0) return res.status(404).json({ error: 'Not found' });
  res.status(204).end();
});

router.post('/trash/empty', (req, res) => {
  db.prepare('DELETE FROM todos WHERE deleted_at IS NOT NULL').run();
  res.status(204).end();
});

// ---- Subtasks ----

router.post('/todos/:id/subtasks', (req, res) => {
  const todo = db.prepare('SELECT id FROM todos WHERE id = ? AND deleted_at IS NULL').get(req.params.id);
  if (!todo) return res.status(404).json({ error: 'Not found' });
  const text = ((req.body && req.body.text) || '').trim();
  if (!text) return res.status(400).json({ error: 'Text is required' });
  const maxPos = db.prepare('SELECT COALESCE(MAX(position), -1) AS m FROM subtasks WHERE todo_id = ?').get(todo.id).m;
  const id = randomUUID();
  db.prepare('INSERT INTO subtasks (id, todo_id, text, done, position) VALUES (?, ?, ?, 0, ?)')
    .run(id, todo.id, text, maxPos + 1);
  const sub = db.prepare('SELECT * FROM subtasks WHERE id = ?').get(id);
  res.status(201).json({ ...sub, done: !!sub.done });
});

router.patch('/subtasks/:id', (req, res) => {
  const sub = db.prepare('SELECT * FROM subtasks WHERE id = ?').get(req.params.id);
  if (!sub) return res.status(404).json({ error: 'Not found' });
  const text = req.body && req.body.text !== undefined ? req.body.text.trim() : sub.text;
  const done = req.body && req.body.done !== undefined ? (req.body.done ? 1 : 0) : sub.done;
  db.prepare('UPDATE subtasks SET text = ?, done = ? WHERE id = ?').run(text, done, sub.id);
  const updated = db.prepare('SELECT * FROM subtasks WHERE id = ?').get(sub.id);
  res.json({ ...updated, done: !!updated.done });
});

router.delete('/subtasks/:id', (req, res) => {
  db.prepare('DELETE FROM subtasks WHERE id = ?').run(req.params.id);
  res.status(204).end();
});

module.exports = router;

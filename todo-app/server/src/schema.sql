CREATE TABLE IF NOT EXISTS todos (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  notes TEXT NOT NULL DEFAULT '',
  category TEXT NOT NULL DEFAULT '',
  priority TEXT NOT NULL DEFAULT 'medium' CHECK (priority IN ('low','medium','high')),
  due_date TEXT,
  due_time TEXT,
  completed INTEGER NOT NULL DEFAULT 0,
  completed_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  series_id TEXT,
  recur_interval INTEGER,
  recur_unit TEXT CHECK (recur_unit IN ('days','weeks','months')),
  prev_instance_id TEXT,
  next_instance_id TEXT,
  notified_due INTEGER NOT NULL DEFAULT 0,
  notified_overdue INTEGER NOT NULL DEFAULT 0,
  deleted_at TEXT,
  position INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_todos_active ON todos (deleted_at, completed, due_date);
CREATE INDEX IF NOT EXISTS idx_todos_series ON todos (series_id);

CREATE TABLE IF NOT EXISTS subtasks (
  id TEXT PRIMARY KEY,
  todo_id TEXT NOT NULL REFERENCES todos(id) ON DELETE CASCADE,
  text TEXT NOT NULL,
  done INTEGER NOT NULL DEFAULT 0,
  position INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_subtasks_todo ON subtasks (todo_id);

CREATE TABLE IF NOT EXISTS push_subscriptions (
  id TEXT PRIMARY KEY,
  endpoint TEXT NOT NULL UNIQUE,
  p256dh TEXT NOT NULL,
  auth TEXT NOT NULL,
  created_at TEXT NOT NULL
);

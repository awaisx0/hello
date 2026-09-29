#!/bin/bash
# Nightly SQLite backup. Uses the .backup command (safe on a live WAL-mode
# database, unlike a plain cp) and prunes anything older than 30 days.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_PATH="${DB_PATH:-$SCRIPT_DIR/../data/todo.db}"
DEST_DIR="${BACKUP_DIR:-$SCRIPT_DIR/../data/backups}"

mkdir -p "$DEST_DIR"
sqlite3 "$DB_PATH" ".backup '$DEST_DIR/todo-$(date +%F).db'"
find "$DEST_DIR" -name 'todo-*.db' -mtime +30 -delete

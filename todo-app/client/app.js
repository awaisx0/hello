let todos = [];
let trash = [];
let editingId = null;
let localSubtasks = [];
let pendingCompleteId = null;
let pendingEditDoneId = null;
let pendingImportData = null;

const $ = (id) => document.getElementById(id);

function escapeHtml(str) {
  return (str || '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function pad(n) { return String(n).padStart(2, '0'); }
function todayStr() {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function dueDateTimeOf(t) {
  if (!t.due_date) return null;
  return new Date(`${t.due_date}T${t.due_time || '23:59'}:00`);
}

function isOverdue(t) {
  if (t.completed) return false;
  const dt = dueDateTimeOf(t);
  return dt !== null && dt < new Date();
}

async function api(path, options = {}) {
  let res;
  try {
    res = await fetch(`/api${path}`, {
      headers: { 'Content-Type': 'application/json' },
      ...options,
    });
  } catch (err) {
    $('connError').hidden = false;
    throw err;
  }
  $('connError').hidden = true;
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || `Request failed: ${res.status}`);
  }
  if (res.status === 204) return null;
  return res.json();
}

// ---- Data loading ----

async function loadTodos() {
  todos = await api('/todos');
  render();
}

async function loadTrash() {
  trash = await api('/trash');
  $('trashCount').textContent = trash.length;
  renderTrashList();
}

// ---- Filtering / sorting / rendering ----

function getFilteredSortedTodos() {
  const status = $('filterStatus').value;
  const priority = $('filterPriority').value;
  const category = $('filterCategory').value;
  const q = $('search').value.trim().toLowerCase();
  const sortKey = $('sortBy').value;
  const t0 = todayStr();

  let list = todos.filter((t) => {
    if (status === 'active' && t.completed) return false;
    if (status === 'completed' && !t.completed) return false;
    if (status === 'overdue' && (t.completed || !isOverdue(t))) return false;
    if (status === 'today' && (t.completed || t.due_date !== t0 || isOverdue(t))) return false;
    if (status === 'upcoming' && (t.completed || !t.due_date || t.due_date <= t0)) return false;
    if (priority && t.priority !== priority) return false;
    if (category && (t.category || '') !== category) return false;
    if (q) {
      const hay = `${t.title} ${t.notes} ${t.category}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  });

  const prioRank = { high: 0, medium: 1, low: 2 };
  list = list.slice().sort((a, b) => {
    if (sortKey === 'priority') return prioRank[a.priority] - prioRank[b.priority];
    if (sortKey === 'created') return b.created_at.localeCompare(a.created_at);
    if (sortKey === 'title') return a.title.localeCompare(b.title);
    if (!a.due_date && !b.due_date) return 0;
    if (!a.due_date) return 1;
    if (!b.due_date) return -1;
    return `${a.due_date}T${a.due_time || '23:59'}`.localeCompare(`${b.due_date}T${b.due_time || '23:59'}`);
  });

  return list;
}

function renderCategoryOptions() {
  const categories = [...new Set(todos.map((t) => t.category).filter(Boolean))].sort();
  const filterSel = $('filterCategory');
  const current = filterSel.value;
  filterSel.innerHTML = '<option value="">Any category</option>' +
    categories.map((c) => `<option value="${escapeHtml(c)}">${escapeHtml(c)}</option>`).join('');
  filterSel.value = categories.includes(current) ? current : '';

  $('categoryList').innerHTML = categories.map((c) => `<option value="${escapeHtml(c)}"></option>`).join('');
}

function renderStats() {
  const active = todos.filter((t) => !t.completed).length;
  const overdue = todos.filter((t) => isOverdue(t)).length;
  const t0 = todayStr();
  const dueToday = todos.filter((t) => !t.completed && t.due_date === t0 && !isOverdue(t)).length;
  const completed = todos.filter((t) => t.completed).length;
  $('stats').innerHTML = `
    <span>${active} active</span>
    <span>${overdue} overdue</span>
    <span>${dueToday} due today</span>
    <span>${completed} completed</span>
  `;
}

function formatDueLabel(t) {
  const [, m, d] = t.due_date.split('-');
  let label = `${m}/${d}`;
  if (t.due_time) label += ` ${t.due_time}`;
  if (isOverdue(t)) return `Overdue: ${label}`;
  if (t.due_date === todayStr()) return `Today${t.due_time ? ' ' + t.due_time : ''}`;
  return label;
}

function buildSubtaskMiniList(t) {
  return `<div class="subtask-mini-list">${t.subtasks.map((s) => `
    <div class="subtask-mini-row ${s.done ? 'done' : ''}">
      <input type="checkbox" id="mini-${s.id}" data-id="${s.id}" ${s.done ? 'checked' : ''}>
      <label for="mini-${s.id}">${escapeHtml(s.text)}</label>
    </div>
  `).join('')}</div>`;
}

function buildCard(t) {
  const div = document.createElement('div');
  div.className = 'todo-card' + (t.completed ? ' completed' : '');
  div.dataset.priority = t.priority;

  const overdue = isOverdue(t);
  const isToday = !t.completed && t.due_date === todayStr() && !overdue;

  let dueBadge = '';
  if (t.due_date) {
    const cls = overdue ? 'overdue' : (isToday ? 'today' : '');
    dueBadge = `<span class="badge ${cls}">${formatDueLabel(t)}</span>`;
  }
  const recurBadge = t.recur_interval ? `<span class="badge recurring">↻ every ${t.recur_interval} ${t.recur_unit}</span>` : '';
  const categoryBadge = t.category ? `<span class="badge">${escapeHtml(t.category)}</span>` : '';

  const subtaskDone = t.subtasks.filter((s) => s.done).length;
  const subtaskTotal = t.subtasks.length;

  div.innerHTML = `
    <div class="todo-main">
      <input type="checkbox" class="todo-check" data-id="${t.id}" ${t.completed ? 'checked' : ''}>
      <div class="todo-body">
        <div class="todo-title">${escapeHtml(t.title)}</div>
        ${t.notes ? `<div class="todo-notes">${escapeHtml(t.notes)}</div>` : ''}
        <div class="todo-meta">${dueBadge}${recurBadge}${categoryBadge}</div>
        ${subtaskTotal ? `<div class="subtask-progress">${subtaskDone}/${subtaskTotal} subtasks</div>` : ''}
        ${subtaskTotal ? buildSubtaskMiniList(t) : ''}
        ${t.completed ? `<div class="completed-info">Done on ${t.completed_at || '?'} <button class="editDoneBtn" data-id="${t.id}">edit</button></div>` : ''}
      </div>
      <div class="todo-actions">
        <button class="editBtn" data-id="${t.id}" title="Edit">✏️</button>
        <button class="deleteBtn" data-id="${t.id}" title="Delete">🗑</button>
      </div>
    </div>
  `;

  div.querySelector('.todo-check').addEventListener('click', onCheckClick);
  div.querySelector('.editBtn').addEventListener('click', () => openModal(t));
  div.querySelector('.deleteBtn').addEventListener('click', () => deleteTodo(t.id));
  const editDoneBtn = div.querySelector('.editDoneBtn');
  if (editDoneBtn) editDoneBtn.addEventListener('click', () => openEditDoneModal(t));
  div.querySelectorAll('.subtask-mini-row input').forEach((cb) => {
    cb.addEventListener('change', () => toggleSubtask(cb.dataset.id, cb.checked));
  });

  return div;
}

function render() {
  renderCategoryOptions();
  const list = getFilteredSortedTodos();
  const container = $('list');
  container.innerHTML = '';
  $('emptyState').hidden = list.length !== 0;
  list.forEach((t) => container.appendChild(buildCard(t)));
  renderStats();
}

// ---- Actions ----

async function onCheckClick(e) {
  e.preventDefault();
  const id = e.target.dataset.id;
  const t = todos.find((x) => x.id === id);
  if (!t) return;
  if (t.completed) {
    await api(`/todos/${id}/reopen`, { method: 'POST' });
    await loadTodos();
  } else {
    openCompleteModal(id);
  }
}

async function toggleSubtask(id, done) {
  await api(`/subtasks/${id}`, { method: 'PATCH', body: JSON.stringify({ done }) });
  await loadTodos();
}

async function deleteTodo(id) {
  await api(`/todos/${id}`, { method: 'DELETE' });
  await loadTodos();
  await loadTrash();
}

function openCompleteModal(id) {
  pendingCompleteId = id;
  $('completeDate').value = todayStr();
  $('completeOverlay').hidden = false;
}

function openEditDoneModal(t) {
  pendingEditDoneId = t.id;
  $('editDoneDate').value = t.completed_at || todayStr();
  $('editDoneOverlay').hidden = false;
}

// ---- Add/Edit modal ----

function renderSubtaskEditor() {
  const container = $('subtaskList');
  container.innerHTML = '';
  localSubtasks.forEach((s, idx) => {
    const row = document.createElement('div');
    row.className = 'subtask-row';
    row.innerHTML = `
      <input type="checkbox" ${s.done ? 'checked' : ''} data-idx="${idx}" class="subEditDone">
      <input type="text" value="${escapeHtml(s.text)}" data-idx="${idx}" class="subEditText">
      <button type="button" data-idx="${idx}" class="subEditRemove">✕</button>
    `;
    container.appendChild(row);
  });
  container.querySelectorAll('.subEditDone').forEach((cb) => cb.addEventListener('change', (e) => {
    localSubtasks[Number(e.target.dataset.idx)].done = e.target.checked;
  }));
  container.querySelectorAll('.subEditText').forEach((inp) => inp.addEventListener('input', (e) => {
    localSubtasks[Number(e.target.dataset.idx)].text = e.target.value;
  }));
  container.querySelectorAll('.subEditRemove').forEach((btn) => btn.addEventListener('click', (e) => {
    localSubtasks.splice(Number(e.target.dataset.idx), 1);
    renderSubtaskEditor();
  }));
}

function openModal(todo) {
  editingId = todo ? todo.id : null;
  $('modalTitle').textContent = todo ? 'Edit Todo' : 'New Todo';
  $('fId').value = todo ? todo.id : '';
  $('fTitle').value = todo ? todo.title : '';
  $('fNotes').value = todo ? todo.notes : '';
  $('fDue').value = todo ? (todo.due_date || '') : '';
  $('fDueTime').value = todo ? (todo.due_time || '') : '';
  $('fPriority').value = todo ? todo.priority : 'medium';
  $('fCategory').value = todo ? (todo.category || '') : '';

  const recurEnabled = !!(todo && todo.recur_interval);
  $('fRecurEnabled').checked = recurEnabled;
  $('fRecurInterval').value = todo && todo.recur_interval ? todo.recur_interval : 1;
  $('fRecurUnit').value = todo && todo.recur_unit ? todo.recur_unit : 'weeks';
  $('recurFields').hidden = !recurEnabled;
  $('recurHint').hidden = !recurEnabled;

  localSubtasks = todo ? todo.subtasks.map((s) => ({ ...s })) : [];
  renderSubtaskEditor();

  $('modalOverlay').hidden = false;
  $('fTitle').focus();
}

function closeModal() {
  $('modalOverlay').hidden = true;
  editingId = null;
  localSubtasks = [];
}

async function submitTodoForm() {
  const title = $('fTitle').value.trim();
  if (!title) return;
  const recurOn = $('fRecurEnabled').checked;
  const payload = {
    title,
    notes: $('fNotes').value,
    dueDate: $('fDue').value || null,
    dueTime: $('fDueTime').value || null,
    priority: $('fPriority').value,
    category: $('fCategory').value.trim(),
    recurInterval: recurOn ? (Number($('fRecurInterval').value) || 1) : null,
    recurUnit: recurOn ? $('fRecurUnit').value : null,
    subtasks: editingId
      ? localSubtasks.map((s) => ({ id: s.id, text: s.text, done: s.done }))
      : localSubtasks.map((s) => s.text),
  };
  if (editingId) {
    await api(`/todos/${editingId}`, { method: 'PUT', body: JSON.stringify(payload) });
  } else {
    await api('/todos', { method: 'POST', body: JSON.stringify(payload) });
  }
  closeModal();
  await loadTodos();
}

// ---- Trash ----

function renderTrashList() {
  const container = $('trashList');
  if (!trash.length) {
    container.innerHTML = '<p class="hint">Trash is empty.</p>';
    return;
  }
  container.innerHTML = trash.map((t) => `
    <div class="trash-row">
      <span class="title">${escapeHtml(t.title)}</span>
      <span class="actions">
        <button class="btn btn-ghost restoreBtn" data-id="${t.id}">Restore</button>
        <button class="btn btn-danger purgeBtn" data-id="${t.id}">Delete forever</button>
      </span>
    </div>
  `).join('');
  container.querySelectorAll('.restoreBtn').forEach((b) => b.addEventListener('click', async () => {
    await api(`/todos/${b.dataset.id}/restore`, { method: 'POST' });
    await loadTrash();
    await loadTodos();
  }));
  container.querySelectorAll('.purgeBtn').forEach((b) => b.addEventListener('click', async () => {
    if (!confirm('Permanently delete this todo? This cannot be undone.')) return;
    await api(`/todos/${b.dataset.id}/permanent`, { method: 'DELETE' });
    await loadTrash();
  }));
}

// ---- Theme ----

function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  localStorage.setItem('todoTheme', theme);
}

function initTheme() {
  const saved = localStorage.getItem('todoTheme');
  if (saved) applyTheme(saved);
  else if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) applyTheme('dark');
}

// ---- Push notifications ----

function urlBase64ToUint8Array(base64String) {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
  const rawData = atob(base64);
  const outputArray = new Uint8Array(rawData.length);
  for (let i = 0; i < rawData.length; ++i) outputArray[i] = rawData.charCodeAt(i);
  return outputArray;
}

function updateNotifyButton(isOn) {
  const btn = $('btnNotify');
  btn.textContent = isOn ? '🔔' : '🔕';
  btn.title = isOn ? 'Reminders on (click to disable)' : 'Enable reminders';
}

async function toggleNotifications() {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
    alert('Push notifications are not supported in this browser.');
    return;
  }
  const reg = await navigator.serviceWorker.ready;
  const existing = await reg.pushManager.getSubscription();
  if (existing) {
    try {
      await api('/push/unsubscribe', { method: 'POST', body: JSON.stringify({ endpoint: existing.endpoint }) });
    } catch (e) { /* best effort */ }
    await existing.unsubscribe();
    updateNotifyButton(false);
    return;
  }
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') {
    alert('Notification permission denied.');
    return;
  }
  let publicKey;
  try {
    ({ publicKey } = await api('/push/vapid-public-key'));
  } catch (e) {
    alert('Could not reach the server.');
    return;
  }
  if (!publicKey) {
    alert('Push notifications are not configured on the server yet (missing VAPID keys). See README.');
    return;
  }
  const sub = await reg.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: urlBase64ToUint8Array(publicKey),
  });
  await api('/push/subscribe', { method: 'POST', body: JSON.stringify(sub.toJSON()) });
  updateNotifyButton(true);
}

// ---- Wiring ----

$('btnAdd').addEventListener('click', () => openModal(null));
$('btnCancel').addEventListener('click', closeModal);
$('modalOverlay').addEventListener('click', (e) => { if (e.target.id === 'modalOverlay') closeModal(); });
$('todoForm').addEventListener('submit', (e) => { e.preventDefault(); submitTodoForm(); });

$('fRecurEnabled').addEventListener('change', (e) => {
  $('recurFields').hidden = !e.target.checked;
  $('recurHint').hidden = !e.target.checked;
});

$('fSubtaskInput').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    e.preventDefault();
    const val = e.target.value.trim();
    if (val) {
      localSubtasks.push({ text: val, done: false });
      e.target.value = '';
      renderSubtaskEditor();
    }
  }
});

$('btnCompleteCancel').addEventListener('click', () => { $('completeOverlay').hidden = true; pendingCompleteId = null; });
$('btnCompleteConfirm').addEventListener('click', async () => {
  const date = $('completeDate').value || todayStr();
  await api(`/todos/${pendingCompleteId}/complete`, { method: 'POST', body: JSON.stringify({ completedAt: date }) });
  $('completeOverlay').hidden = true;
  pendingCompleteId = null;
  await loadTodos();
});

$('btnEditDoneCancel').addEventListener('click', () => { $('editDoneOverlay').hidden = true; pendingEditDoneId = null; });
$('btnEditDoneConfirm').addEventListener('click', async () => {
  const date = $('editDoneDate').value;
  if (!date) return;
  await api(`/todos/${pendingEditDoneId}/completed-at`, { method: 'PATCH', body: JSON.stringify({ completedAt: date }) });
  $('editDoneOverlay').hidden = true;
  pendingEditDoneId = null;
  await loadTodos();
});

$('btnTrash').addEventListener('click', async () => {
  await loadTrash();
  $('trashOverlay').hidden = false;
});
$('btnTrashClose').addEventListener('click', () => { $('trashOverlay').hidden = true; });
$('btnEmptyTrash').addEventListener('click', async () => {
  if (!trash.length) return;
  if (!confirm('Permanently delete all trashed todos? This cannot be undone.')) return;
  await api('/trash/empty', { method: 'POST' });
  await loadTrash();
});

$('btnExport').addEventListener('click', () => { window.location.href = '/api/export'; });

$('importFile').addEventListener('change', async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  const text = await file.text();
  try {
    pendingImportData = JSON.parse(text);
  } catch (err) {
    alert('That file is not valid JSON.');
    e.target.value = '';
    return;
  }
  $('importOverlay').hidden = false;
});
$('btnImportCancel').addEventListener('click', () => {
  $('importOverlay').hidden = true;
  pendingImportData = null;
  $('importFile').value = '';
});
$('btnImportConfirm').addEventListener('click', async () => {
  try {
    await api('/import', { method: 'POST', body: JSON.stringify(pendingImportData) });
  } catch (err) {
    alert(err.message);
  }
  $('importOverlay').hidden = true;
  $('importFile').value = '';
  pendingImportData = null;
  await loadTodos();
  await loadTrash();
});

$('btnTheme').addEventListener('click', () => {
  const current = document.documentElement.getAttribute('data-theme') || 'light';
  applyTheme(current === 'dark' ? 'light' : 'dark');
});

$('btnNotify').addEventListener('click', toggleNotifications);

['search', 'filterStatus', 'filterPriority', 'filterCategory', 'sortBy'].forEach((id) => {
  $(id).addEventListener('input', render);
  $(id).addEventListener('change', render);
});

// ---- Init ----

async function init() {
  initTheme();
  if ('serviceWorker' in navigator) {
    try {
      const reg = await navigator.serviceWorker.register('/sw.js');
      const sub = await reg.pushManager.getSubscription();
      updateNotifyButton(!!sub);
    } catch (e) {
      console.error('Service worker registration failed', e);
    }
  }
  await Promise.all([loadTodos(), loadTrash()]);
}

init();

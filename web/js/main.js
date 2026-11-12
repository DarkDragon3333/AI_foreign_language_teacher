import { api, chatTurn } from './api.js?v=9';
import { userMessage, teacherMessage, teacherPlaceholder } from './render.js?v=9';

const LEVELS = ['A1', 'A2', 'B1', 'B2', 'C1', 'C2'];
const MODE_NAMES = { conversation: 'Разговор', explain: 'Объяснение', correct: 'Проверка', drill: 'Тренировка' };
// что даст следующее нажатие «Подсказка»: ступени support_level 1..3
const HINT_STEPS = ['· правило', '· пример', '· ответ', '· ответ'];
const C_LEVEL_NOTE = 'На уровнях C1–C2 стиль небольшой локальной модели упрощён: нюансы и идиомы она передаёт хуже живого преподавателя.';

const $ = (id) => document.getElementById(id);
const state = { session: null, level: 'B1', busy: false, tab: 'lesson' };

// ---------- тема оформления ----------

$('theme').value = document.documentElement.dataset.theme || 'blue';
$('theme').addEventListener('change', (event) => {
  document.documentElement.dataset.theme = event.target.value;
  try { localStorage.setItem('tutor-theme', event.target.value); } catch { /* приватный режим: тема до перезагрузки */ }
});

// ---------- состояние сервисов ----------

async function refreshHealth() {
  const node = $('health');
  try {
    const h = await api.health();
    const down = Object.entries(h.components || {}).filter(([, c]) => c.status !== 'up');
    node.className = 'health ' + (h.status === 'up' ? 'up' : 'down');
    node.textContent = h.status === 'up' ? 'учитель на связи'
      : down.map(([name, c]) => `${name}: ${c.detail || 'недоступен'}`).join('; ');
  } catch {
    node.className = 'health down';
    node.textContent = 'сервер недоступен';
  }
}

// ---------- экран «Новый урок» ----------

function renderLevelPicker() {
  const picker = $('level-picker');
  picker.textContent = '';
  for (const level of LEVELS) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = level;
    button.setAttribute('role', 'radio');
    button.setAttribute('aria-checked', String(level === state.level));
    button.addEventListener('click', () => { state.level = level; renderLevelPicker(); });
    picker.append(button);
  }
  const note = $('level-note');
  note.hidden = !state.level.startsWith('C');
  note.textContent = C_LEVEL_NOTE;
}

function showStart() {
  state.session = null;
  renderLevelPicker();
  showTab(state.tab);
}

// ---------- разделы: урок / журнал ошибок ----------

function showTab(tab) {
  state.tab = tab;
  const lessonTab = tab === 'lesson';
  $('tab-lesson').toggleAttribute('aria-current', lessonTab);
  $('tab-journal').toggleAttribute('aria-current', !lessonTab);
  if (lessonTab) $('tab-lesson').setAttribute('aria-current', 'page'); else $('tab-journal').setAttribute('aria-current', 'page');
  $('journal').hidden = lessonTab;
  $('start').hidden = !lessonTab || !!state.session;
  $('lesson').hidden = !lessonTab || !state.session;
  if (!lessonTab) loadJournal();
}

async function loadJournal() {
  const rows = $('journal-rows');
  try {
    const entries = await api.errors();
    rows.textContent = '';
    $('journal-empty').hidden = entries.length > 0;
    $('journal-table').hidden = entries.length === 0;
    for (const e of entries) {
      const tr = document.createElement('tr');
      const rule = document.createElement('td');
      const title = document.createElement('div');
      title.className = 'rule-title';
      title.textContent = e.title || e.ruleId;
      const category = document.createElement('div');
      category.className = 'rule-category';
      category.textContent = e.category || '';
      rule.append(title, category);
      const example = document.createElement('td');
      if (e.example) {
        const span = document.createElement('span');
        span.className = 'err-span';
        span.textContent = e.example;
        example.append(span);
      }
      const count = document.createElement('td');
      count.className = 'num';
      count.textContent = e.count;
      const seen = document.createElement('td');
      seen.textContent = new Date(e.lastSeen).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });
      tr.append(rule, example, count, seen);
      rows.append(tr);
    }
  } catch (e) {
    $('journal-table').hidden = true;
    $('journal-empty').hidden = false;
    $('journal-empty').textContent = 'Не удалось загрузить журнал: ' + e.message;
  }
}

$('tab-lesson').addEventListener('click', () => showTab('lesson'));
$('tab-journal').addEventListener('click', () => showTab('journal'));

$('start-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = event.submitter;
  button.disabled = true;
  try {
    // выбранный уровень запоминаем в профиле — он станет уровнем по умолчанию
    await api.updateProfile({ cefr: state.level });
    const session = await api.startSession({ cefr: state.level, topic: $('topic').value, mode: $('mode').value });
    showLesson(session, []);
    await turn('', false); // учитель начинает урок сам
  } catch (e) {
    showStart();
    fatal(e.message);
  } finally {
    button.disabled = false;
  }
});

// ---------- экран урока ----------

function showLesson(session, messages) {
  $('fatal').hidden = true;
  state.session = session;
  showTab('lesson');
  const list = $('messages');
  list.textContent = '';
  for (const m of messages) {
    list.append(m.role === 'user' ? userMessage(m.text) : teacherMessage(m.reply, m.text));
  }
  applySession(session);
  scrollDown();
  $('input').focus();
}

function applySession(session) {
  state.session = session;
  $('lesson-level').textContent = session.cefr;
  $('lesson-mode').textContent = MODE_NAMES[session.mode] || session.mode;
  $('lesson-topic').textContent = session.topic || '';
  $('hint-level').textContent = HINT_STEPS[Math.min(session.supportLevel, 3)];
  const index = LEVELS.indexOf(session.cefr);
  $('easier').disabled = state.busy || index <= 0;
  $('harder').disabled = state.busy || index >= LEVELS.length - 1;
}

function setBusy(busy) {
  state.busy = busy;
  for (const id of ['send', 'hint', 'end']) $(id).disabled = busy;
  if (state.session) applySession(state.session);
}

function setStatus(text, isError, canRetry) {
  const node = $('status');
  node.hidden = !text;
  $('status-text').textContent = text || '';
  $('retry').hidden = !canRetry;
  node.classList.toggle('error', !!isError);
}

// первый ход урока не удался (например, модель ещё не скачана) — даём повторить его
$('retry').addEventListener('click', () => turn('', false));

function scrollDown() {
  const list = $('messages');
  list.scrollTop = list.scrollHeight;
}

async function turn(text, hint) {
  if (state.busy || !state.session) return;
  setBusy(true);
  setStatus('Учитель думает…');
  const list = $('messages');
  const shownUser = text ? userMessage(text) : null;
  if (shownUser) list.append(shownUser);
  const placeholder = teacherPlaceholder();
  list.append(placeholder.node);
  scrollDown();

  let failure = null;
  try {
    await chatTurn({ sessionId: state.session.id, text, hint }, {
      delta(chunk) { setStatus(''); placeholder.append(chunk); scrollDown(); },
      final(payload) {
        if (!shownUser && payload.user) placeholder.node.before(userMessage(payload.user.text));
        placeholder.finish(payload.teacher.reply, payload.teacher.text);
        applySession(payload.session);
      },
      error(message) { failure = message; },
    });
  } catch (e) {
    failure = e.message;
  }

  if (failure) {
    placeholder.remove();
    if (shownUser) { shownUser.remove(); $('input').value = text; } // не теряем набранный текст
    setStatus(failure, true, !text && !hint);
    refreshHealth();
  } else {
    setStatus('');
    if ($('health').classList.contains('down')) refreshHealth();
  }
  setBusy(false);
  fitInput();
  scrollDown();
  $('input').focus();
}

$('chat-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const text = $('input').value.trim();
  if (!text || state.busy) return;
  $('input').value = '';
  turn(text, false);
});

// высота поля подстраивается под текст (до предела из CSS, дальше — прокрутка)
function fitInput() {
  const input = $('input');
  input.style.height = 'auto';
  input.style.height = input.scrollHeight + 'px';
}
$('input').addEventListener('input', fitInput);

$('input').addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    $('chat-form').requestSubmit();
  }
});

$('hint').addEventListener('click', () => {
  const text = $('input').value.trim();
  $('input').value = '';
  turn(text, true);
});

async function shiftLevel(delta) {
  const next = LEVELS[LEVELS.indexOf(state.session.cefr) + delta];
  if (!next || state.busy) return;
  try {
    const previous = state.session.cefr;
    applySession(await api.setLevel(state.session.id, next));
    // отметка прямо в ленте: видно, с какого места учитель говорит по-другому
    const note = document.createElement('div');
    note.className = 'chat-note';
    note.textContent = `${delta > 0 ? 'Сложнее' : 'Проще'}: уровень ${previous} → ${next}`;
    $('messages').append(note);
    scrollDown();
    setStatus('');
  } catch (e) {
    setStatus(e.message, true);
  }
}
$('easier').addEventListener('click', () => shiftLevel(-1));
$('harder').addEventListener('click', () => shiftLevel(1));

$('end').addEventListener('click', async () => {
  if (state.busy) return;
  try {
    await api.endSession(state.session.id);
    setStatus('');
    showStart();
  } catch (e) {
    setStatus(e.message, true);
  }
});

// ---------- запуск ----------

function fatal(message) {
  const node = $('fatal');
  node.hidden = false;
  node.textContent = message;
}

async function boot() {
  refreshHealth();
  setInterval(refreshHealth, 30000);
  try {
    const profile = await api.profile();
    state.level = profile.cefr;
    // незавершённый урок продолжаем после перезагрузки страницы
    const open = (await api.sessions()).find((s) => !s.endedAt);
    if (open) {
      const messages = await api.messages(open.id);
      showLesson(open, messages);
      if (!messages.length) turn('', false); // урок был создан, но учитель так и не ответил
    } else {
      showStart();
    }
  } catch (e) {
    showStart();
    fatal('Не удалось связаться с сервером: ' + e.message);
  }
}

boot();

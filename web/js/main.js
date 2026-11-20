import { api, chatTurn, transcribe } from './api.js?v=18';
import { userMessage, teacherMessage, teacherPlaceholder, speakMessage, stopSpeaking } from './render.js?v=18';
import { openReview, refreshReviewCount } from './review.js?v=18';
import { VoiceDuplex } from './voice.js?v=18';
import { stopActivePractice, wirePracticeControls } from './practice.js?v=30';
import { enterPracticeScreen, focusPracticeTab, pausePracticeScreen } from './practice-ui.js?v=2';

const LEVELS = ['A1', 'A2', 'B1', 'B2', 'C1', 'C2'];
const LEVELS_INFO = {
  A1: { name: 'Beginner', blurb: 'Introduce yourself & use everyday words' },
  A2: { name: 'Elementary', blurb: 'Talk about routines & familiar topics' },
  B1: { name: 'Intermediate', blurb: 'Share experiences & express opinions' },
  B2: { name: 'Upper intermediate', blurb: 'Discuss ideas with greater confidence' },
  C1: { name: 'Advanced', blurb: 'Communicate fluently & precisely' },
  C2: { name: 'Proficient', blurb: 'Handle nuance, register and complex ideas' },
};
const MODE_NAMES = {
  conversation: 'Conversation', explain: 'Explain', correct: 'Check', drill: 'Drill', roleplay: 'Role-play',
};
const MODE_HEADINGS = {
  conversation: 'Start a conversation',
  explain: 'Explain a rule',
  correct: 'Check my English',
  drill: 'A short drill',
  roleplay: 'Play a scene',
};
const HINT_STEPS = ['· rule', '· example', '· answer', '· answer'];
const C_LEVEL_NOTE = 'At C1 and C2 this small local model writes more simply than a human teacher. Nuance and idioms are weaker.';
const PRACTICE_STEPS = 6;
const PROGRESS_PREVIEW = 5;
let historyShowAll = false;
let journalShowAll = false;
const ACTIVITY_NAMES = {
  review: 'Review', grammar: 'Grammar', speaking: 'Speaking', listening: 'Listening', conversation: 'Conversation',
};
const STATUS_NAMES = { planned: 'Planned', done: 'Done', skipped: 'Skipped', cancelled: 'Cancelled' };

const $ = (id) => document.getElementById(id);
const state = {
  session: null,
  history: [],
  level: 'A1',
  busy: false,
  tab: 'learn',
  voiceDuplex: null,
  voicePlaceholder: null,
  scenarios: [],
  scenarioProgress: null,
  starting: false,
  goalMin: 10,
  studyWeekdays: [1, 2, 3, 4, 5],
  lessonTimerMs: 0,
};

const CRUMBS = {
  learn: ['Learn', 'Overview'],
  levels: ['Learn', 'Levels'],
  journal: ['Learn', 'Progress'],
  review: ['Learn', 'Practice'],
  lesson: ['Learn', 'Practice'],
  grammar: ['Learn', 'Grammar'],
  speaking: ['Learn', 'Speaking'],
};
const PRACTICE_TABS = new Set(['grammar', 'speaking', 'review']);

// ---------- тема оформления ----------

$('theme').value = document.documentElement.dataset.theme === 'dusk' ? 'dusk' : 'sage';
$('theme').addEventListener('change', (event) => {
  document.documentElement.dataset.theme = event.target.value;
  try { localStorage.setItem('tutor-theme', event.target.value); } catch { /* приватный режим */ }
});

// ---------- состояние сервисов ----------

async function refreshHealth() {
  const node = $('health');
  try {
    const h = await api.health();
    const down = Object.entries(h.components || {}).filter(([, c]) => c.status !== 'up');
    node.className = 'health ' + (h.status === 'up' ? 'up' : 'down');
    node.textContent = h.status === 'up' ? '' : down.map(([name, c]) => `${name}: ${c.detail || 'down'}`).join('; ');
    node.title = h.status === 'up' ? 'Teacher is ready' : node.textContent;
  } catch {
    node.className = 'health down';
    node.textContent = 'Server is offline';
    node.title = node.textContent;
  }
}

// ---------- дневная цель ----------

function renderGoal(progress) {
  const goal = state.goalMin;
  const mins = progress ? Math.min(goal, progress.minutesDone) : 0;
  $('goal-of').textContent = '/ ' + goal + ' min';
  $('goal-value').textContent = String(mins);
  $('goal-fill').style.width = Math.min(100, goal ? (mins / goal) * 100 : 0) + '%';
  const met = progress?.goalMet;
  $('goal-caption').textContent = met
    ? 'Daily goal complete'
    : mins + ' of ' + goal + ' minutes complete';
  const bar = $('goal-fill').parentElement;
  bar.setAttribute('aria-valuemax', String(goal));
  bar.setAttribute('aria-valuenow', String(mins));
}

async function refreshGoal() {
  try {
    const progress = await api.todayProgress();
    renderGoal(progress);
  } catch {
    renderGoal(null);
  }
}

async function loadSchedule() {
  try {
    const week = await api.scheduleWeek();
    state.studyWeekdays = week.studyWeekdays || [1, 2, 3, 4, 5];
    for (const btn of document.querySelectorAll('#weekday-picks .dow-btn')) {
      btn.classList.toggle('is-on', state.studyWeekdays.includes(Number(btn.dataset.dow)));
    }
    const list = $('week-slots');
    list.textContent = '';
    for (const slot of week.slots || []) {
      const li = document.createElement('li');
      const label = document.createElement('span');
      label.className = 'slot-date';
      label.textContent = new Date(slot.date + 'T12:00:00').toLocaleDateString('en-GB', {
        weekday: 'short', day: 'numeric', month: 'short',
      });
      const select = document.createElement('select');
      for (const [key, name] of Object.entries(ACTIVITY_NAMES)) {
        const opt = document.createElement('option');
        opt.value = key;
        opt.textContent = name;
        opt.selected = key === slot.activityType;
        select.append(opt);
      }
      select.disabled = slot.status !== 'planned';
      select.addEventListener('change', () => {
        api.patchScheduleSlot(slot.date, { activityType: select.value }).catch((e) => fatal(e.message));
      });
      const status = document.createElement('span');
      status.className = 'slot-status';
      status.textContent = STATUS_NAMES[slot.status] || slot.status;
      li.append(label, select, status);
      list.append(li);
    }
  } catch {
    $('week-slots').textContent = '';
  }
}

for (const btn of document.querySelectorAll('#weekday-picks .dow-btn')) {
  btn.addEventListener('click', async () => {
    const dow = Number(btn.dataset.dow);
    let days = [...state.studyWeekdays];
    if (days.includes(dow)) days = days.filter((d) => d !== dow);
    else days.push(dow);
    days.sort((a, b) => a - b);
    try {
      const week = await api.scheduleWeekdays(days);
      state.studyWeekdays = week.studyWeekdays;
      await loadSchedule();
    } catch (e) {
      fatal(e.message);
    }
  });
}

async function loadInsights() {
  try {
    const data = await api.insights(5);
    $('insights-summary').textContent = data.lessonCount
      ? `Last ${data.lessonCount} lessons: ${data.totalMinutes} min. Rules: ${(data.frequentRules || []).slice(0, 3).join('; ') || '—'}`
      : 'Finish a few lessons to see stats here.';
  } catch {
    $('insights-summary').textContent = '';
  }
}

$('revise-plan').addEventListener('click', async () => {
  const button = $('revise-plan');
  button.disabled = true;
  try {
    const rev = await api.revisePlan();
    const node = $('plan-revision');
    node.hidden = false;
    node.textContent = `${rev.summary} (${rev.source === 'model' ? 'model' : 'rules'})`;
    await loadSchedule();
  } catch (e) {
    fatal(e.message);
  } finally {
    button.disabled = false;
  }
});

$('goal-select').addEventListener('change', async (event) => {
  const dailyGoalMin = Number(event.target.value);
  state.goalMin = dailyGoalMin;
  try {
    await api.updateProfile({ dailyGoalMin });
    await refreshGoal();
  } catch (e) {
    fatal(e.message);
  }
});

// ---------- уровни и обзор ----------

function displayedLevel() {
  return state.session?.cefr || state.level;
}

function markCurrentLevel() {
  const current = displayedLevel();
  for (const card of document.querySelectorAll('#level-grid [data-level]')) {
    const on = card.dataset.level === current;
    card.classList.toggle('is-current', on);
    const action = card.querySelector('.level-action');
    if (action) action.textContent = on ? 'Continue learning' : 'Explore level';
  }
  const info = LEVELS_INFO[current] || LEVELS_INFO.A1;
  const pathMark = $('path-current-level');
  const pathName = $('path-level-name');
  if (pathMark) pathMark.textContent = current;
  if (pathName) pathName.textContent = info.name;
  $('side-level').textContent = current + ' · ' + info.name;
  const note = $('level-note');
  note.hidden = !String(current).startsWith('C');
  note.textContent = C_LEVEL_NOTE;
}

function heroTitle(session) {
  const mode = session ? session.mode : $('mode').value;
  if (mode === 'roleplay') {
    const slug = session ? session.topic : $('scenario').value;
    return state.scenarios.find((s) => s.slug === slug)?.title || MODE_HEADINGS.roleplay;
  }
  return MODE_HEADINGS[mode] || 'Start a conversation';
}

function heroSub(session) {
  const level = session ? session.cefr : state.level;
  const info = LEVELS_INFO[level] || LEVELS_INFO.A1;
  if (session?.mode === 'roleplay' || (!session && $('mode').value === 'roleplay')) {
    const slug = session ? session.topic : $('scenario').value;
    const scene = state.scenarios.find((s) => s.slug === slug);
    return scene ? scene.titleRu : (level + ' · ' + info.name);
  }
  const topic = session ? session.topic : $('topic').value.trim();
  return topic || (level + ' · ' + (session ? info.name : info.blurb));
}

function renderHeroCopy() {
  $('continue-title').textContent = heroTitle(state.session);
  $('continue-sub').textContent = heroSub(state.session);
}

function userTurnCount() {
  const inDom = document.querySelectorAll('#messages .msg.user').length;
  const inHistory = (state.history || []).filter((m) => m.role === 'user').length;
  return Math.max(inDom, inHistory);
}

function progressCounts() {
  if (state.session?.mode === 'roleplay' && state.scenarioProgress?.total) {
    return { done: state.scenarioProgress.done, total: state.scenarioProgress.total };
  }
  const turns = state.session ? userTurnCount() : 0;
  return { done: Math.min(turns, PRACTICE_STEPS), total: PRACTICE_STEPS };
}

function progressLabel(counts) {
  if (state.session?.mode === 'roleplay' && state.scenarioProgress) {
    return counts.done + ' of ' + counts.total + ' goals';
  }
  return 'Lesson ' + counts.done + ' of ' + counts.total;
}

function paintBar(fill, label, counts, bar) {
  if (!fill) return;
  const pct = counts.total ? Math.min(100, (counts.done / counts.total) * 100) : 0;
  fill.style.width = pct + '%';
  if (label) label.textContent = counts.done + ' / ' + counts.total;
  if (bar) {
    bar.setAttribute('aria-valuenow', String(counts.done));
    bar.setAttribute('aria-valuemax', String(counts.total));
  }
}

function updateLessonProgress() {
  const counts = progressCounts();
  paintBar($('lesson-bar'), $('lesson-fraction'), counts, $('lesson-bar-wrap'));
  if (state.tab === 'learn') {
    $('continue-meta').textContent = state.session ? progressLabel(counts) : '';
    if (state.session) paintBar($('continue-bar'), null, counts, $('continue-bar-wrap'));
  }
}

function turnHadMistakes(reply) {
  const c = reply?.correction;
  if (!c) return false;
  if (c.errors?.length) return true;
  if (c.corrected && c.original && c.corrected.trim() !== c.original.trim()) return true;
  return false;
}

function statsFromMessages(messages) {
  let correct = 0;
  let mistakes = 0;
  for (let i = 0; i < messages.length; i++) {
    const m = messages[i];
    if (m.role !== 'user' || !m.text?.trim()) continue;
    const next = messages[i + 1];
    if (next?.role !== 'teacher') continue;
    if (turnHadMistakes(next.reply)) mistakes += 1;
    else correct += 1;
  }
  return { correct, mistakes };
}

function updateLessonStats() {
  const { correct, mistakes } = statsFromMessages(state.history || []);
  const correctEl = $('lesson-correct');
  const mistakesEl = $('lesson-mistakes');
  if (correctEl) correctEl.textContent = String(correct);
  if (mistakesEl) mistakesEl.textContent = String(mistakes);
}

let lessonClockTimer = null;
let lessonClockRunningSince = null;

function formatLessonElapsed(ms) {
  const totalSec = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(totalSec / 60);
  const s = totalSec % 60;
  return m + ':' + String(s).padStart(2, '0');
}

function lessonTimerStorageKey(sessionId) {
  return sessionId != null ? 'lesson-active-ms-' + sessionId : null;
}

function loadLessonTimerMs(sessionId) {
  const key = lessonTimerStorageKey(sessionId);
  if (!key) return 0;
  try {
    const raw = sessionStorage.getItem(key);
    if (raw != null) return Math.max(0, parseInt(raw, 10) || 0);
  } catch { /* приватный режим */ }
  return 0;
}

function saveLessonTimerMs(ms) {
  const key = lessonTimerStorageKey(state.session?.id);
  if (!key) return;
  try { sessionStorage.setItem(key, String(Math.max(0, ms))); } catch { /* */ }
}

function clearLessonTimerStorage(sessionId) {
  const key = lessonTimerStorageKey(sessionId ?? state.session?.id);
  if (!key) return;
  try { sessionStorage.removeItem(key); } catch { /* */ }
}

function lessonElapsedMs() {
  let ms = state.lessonTimerMs || 0;
  if (lessonClockRunningSince != null) ms += Date.now() - lessonClockRunningSince;
  return ms;
}

function updateLessonClock() {
  const el = $('lesson-elapsed');
  if (!el || !state.session) return;
  el.textContent = formatLessonElapsed(lessonElapsedMs());
}

function stopLessonClockInterval() {
  if (lessonClockTimer) clearInterval(lessonClockTimer);
  lessonClockTimer = null;
}

function pauseLessonClock() {
  if (lessonClockRunningSince != null) {
    state.lessonTimerMs = lessonElapsedMs();
    lessonClockRunningSince = null;
    saveLessonTimerMs(state.lessonTimerMs);
  }
  stopLessonClockInterval();
  updateLessonClock();
}

function resumeLessonClock() {
  if (!state.session) return;
  stopLessonClockInterval();
  lessonClockRunningSince = Date.now();
  updateLessonClock();
  lessonClockTimer = setInterval(updateLessonClock, 1000);
}

function resetLessonClock(sessionId) {
  stopLessonClockInterval();
  lessonClockRunningSince = null;
  state.lessonTimerMs = 0;
  clearLessonTimerStorage(sessionId);
  updateLessonClock();
}

function bindLessonClock(session) {
  state.lessonTimerMs = loadLessonTimerMs(session.id);
  lessonClockRunningSince = null;
  updateLessonClock();
}

function syncHistoryFromTurn(payload) {
  if (payload.user) state.history.push(payload.user);
  if (payload.teacher) state.history.push(payload.teacher);
}

function renderDashboard() {
  markCurrentLevel();
  renderHeroCopy();
  refreshGoal();
  loadSchedule();
  syncModePills();
  const open = !!state.session;
  $('start-form').hidden = open;
  $('resume-lesson').hidden = !open;
  $('continue-bar-wrap').hidden = !open;
  $('continue-meta').hidden = !open;
  if (open) {
    const counts = progressCounts();
    $('continue-meta').textContent = progressLabel(counts);
    paintBar($('continue-bar'), null, counts, $('continue-bar-wrap'));
  }
}

function syncModePills() {
  const mode = $('mode').value;
  for (const button of $('mode-pills').querySelectorAll('[data-mode]')) {
    button.setAttribute('aria-pressed', String(button.dataset.mode === mode));
  }
}

function syncStartForm() {
  const roleplay = $('mode').value === 'roleplay';
  $('scenario-wrap').hidden = !roleplay;
  $('topic-wrap').hidden = roleplay;
  syncModePills();
  if (!state.session) renderHeroCopy();
}

async function loadScenarios() {
  if (state.scenarios.length) return;
  try {
    state.scenarios = await api.scenarios();
    const select = $('scenario');
    select.textContent = '';
    for (const s of state.scenarios) {
      const option = document.createElement('option');
      option.value = s.slug;
      option.textContent = s.title + ' · ' + s.titleRu + ' (' + s.cefrMin + '–' + s.cefrMax + ')';
      select.append(option);
    }
  } catch {
    state.scenarios = [];
  }
}

// ---------- разделы ----------

function updateChrome() {
  const [a, b] = CRUMBS[state.tab] || CRUMBS.learn;
  $('crumb-a').textContent = a;
  $('crumb-b').textContent = b;
  const inLesson = state.tab === 'lesson';
  const inPractice = PRACTICE_TABS.has(state.tab);
  $('exit-lesson').hidden = !inLesson;
  $('exit-practice').hidden = !inPractice || inLesson;
  const stopBtn = $('stop-practice');
  stopBtn.hidden = !inPractice || inLesson || state.tab === 'review';
  $('range-label').hidden = inLesson;
  document.title = b + ' · English learning';
  const nav = state.tab === 'journal' ? 'journal' : state.tab === 'levels' ? 'levels' : 'learn';
  for (const name of ['learn', 'levels', 'journal']) {
    const button = $('nav-' + name);
    if (name === nav) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  }
}

function showTab(tab) {
  refreshGoal();
  if (state.tab === 'lesson' && tab !== 'lesson') {
    disableVoiceDuplex();
    pauseLessonClock();
  }
  if (PRACTICE_TABS.has(state.tab) && !PRACTICE_TABS.has(tab)) pausePracticeScreen();
  state.tab = tab;
  for (const id of ['learn', 'levels', 'journal', 'review', 'grammar', 'speaking', 'lesson']) {
    $(id).hidden = id !== tab;
  }
  updateChrome();
  if (tab === 'journal') {
    loadInsights();
    loadHistory();
    loadJournal();
  }
  if (tab === 'review') {
    enterPracticeScreen('review');
    openReview();
  }
  if (PRACTICE_TABS.has(tab) && tab !== 'review') focusPracticeTab(tab);
  if (tab === 'learn') renderDashboard();
  if (tab === 'levels') markCurrentLevel();
  if (tab === 'lesson') {
    updateLessonProgress();
    updateLessonStats();
    resumeLessonClock();
    $('input').focus();
  }
}

function showStart() {
  const endedId = state.session?.id;
  resetLessonClock(endedId);
  state.session = null;
  state.history = [];
  state.scenarioProgress = null;
  $('messages').textContent = '';
  $('scenario-panel').hidden = true;
  disableVoiceDuplex(false);
  syncStartForm();
  showTab('learn');
}

async function selectLevel(level) {
  if (level === displayedLevel()) return;
  const previous = displayedLevel();
  state.level = level;
  try {
    await api.updateProfile({ cefr: level });
    if (state.session) {
      state.session = await api.setLevel(state.session.id, level);
      if ($('messages').childElementCount) {
        applySession(state.session);
        const note = document.createElement('div');
        note.className = 'chat-note';
        note.textContent = 'Level ' + previous + ' → ' + level;
        $('messages').append(note);
      }
    }
    markCurrentLevel();
    renderHeroCopy();
  } catch (e) {
    fatal(e.message);
  }
}

async function beginLesson(opts = {}) {
  if (state.starting) return;
  if (state.session) {
    if (opts.cefr && opts.cefr !== state.session.cefr) {
      state.session = await api.setLevel(state.session.id, opts.cefr);
      state.level = opts.cefr;
      markCurrentLevel();
    }
    resumeLesson();
    return;
  }
  state.starting = true;
  const level = opts.cefr || state.level;
  const mode = opts.mode || $('mode').value;
  const topic = opts.topic !== undefined
    ? opts.topic
    : (mode === 'roleplay' ? $('scenario').value : $('topic').value.trim());
  try {
    await api.updateProfile({ cefr: level });
    state.level = level;
    const session = await api.startSession({ cefr: level, topic, mode });
    state.history = [];
    state.scenarioProgress = null;
    resetLessonClock(session.id);
    showLesson(session, []);
    await turn('', false);
  } finally {
    state.starting = false;
  }
}

function resumeLesson() {
  if (!state.session) return;
  if ($('messages').childElementCount) {
    showTab('lesson');
    applySession(state.session);
    if (state.session.mode === 'roleplay') refreshScenarioPanel();
    return;
  }
  showLesson(state.session, state.history || []);
  if (!(state.history || []).length) turn('', false);
}

$('nav-learn').addEventListener('click', () => showTab('learn'));
$('nav-levels').addEventListener('click', () => showTab('levels'));
$('nav-journal').addEventListener('click', () => showTab('journal'));
$('side-level-card').addEventListener('click', () => showTab('levels'));
$('explore-levels').addEventListener('click', () => showTab('levels'));
$('open-review').addEventListener('click', () => showTab('review'));
$('open-review-progress').addEventListener('click', () => showTab('review'));
$('history-show-all').addEventListener('click', () => {
  historyShowAll = !historyShowAll;
  loadHistory();
});
$('journal-show-all').addEventListener('click', () => {
  journalShowAll = !journalShowAll;
  loadJournal();
});
$('exit-lesson').addEventListener('click', () => showTab('learn'));
$('exit-practice').addEventListener('click', () => {
  stopActivePractice(state.tab);
  showTab('learn');
});
$('stop-practice').addEventListener('click', () => stopActivePractice(state.tab));
$('resume-lesson').addEventListener('click', () => resumeLesson());

$('mode-pills').addEventListener('click', (event) => {
  const button = event.target.closest('[data-mode]');
  if (!button) return;
  $('mode').value = button.dataset.mode;
  $('mode').dispatchEvent(new Event('change'));
});
$('mode').addEventListener('change', syncStartForm);
$('topic').addEventListener('input', () => { if (!state.session) renderHeroCopy(); });
$('scenario').addEventListener('change', () => { if (!state.session) renderHeroCopy(); });

$('level-grid').addEventListener('click', (event) => {
  const button = event.target.closest('.level-action');
  if (!button) return;
  const level = button.closest('[data-level]').dataset.level;
  if (level === displayedLevel()) {
    beginLesson({ cefr: level, mode: 'conversation', topic: '' }).catch((e) => fatal(e.message));
  } else {
    selectLevel(level);
  }
});
$('level-check').addEventListener('click', () => {
  beginLesson({ mode: 'conversation', topic: 'level check' }).catch((e) => fatal(e.message));
});
$('open-grammar').addEventListener('click', () => showTab('grammar'));
$('open-speaking').addEventListener('click', () => showTab('speaking'));

$('start-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = event.submitter;
  button.disabled = true;
  try {
    await beginLesson();
  } catch (e) {
    fatal(e.message);
  } finally {
    button.disabled = false;
  }
});

function setProgressMoreButton(button, total, expanded) {
  if (!button) return;
  if (total <= PROGRESS_PREVIEW) {
    button.hidden = true;
    return;
  }
  button.hidden = false;
  button.textContent = expanded ? 'Show less' : `View all (${total})`;
}

function setProgressTableExpanded(tableId, expanded) {
  const wrap = $(tableId)?.closest('.progress-table-wrap');
  if (wrap) wrap.classList.toggle('is-expanded', expanded);
}

function appendHistoryRow(rows, s) {
  const tr = document.createElement('tr');
  const when = document.createElement('td');
  when.textContent = new Date(s.startedAt).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  const mode = document.createElement('td');
  mode.textContent = MODE_NAMES[s.mode] || s.mode;
  const min = document.createElement('td');
  min.className = 'num';
  min.textContent = s.summary ? String(s.summary.durationMinutes) : '—';
  const turns = document.createElement('td');
  turns.className = 'num';
  turns.textContent = s.summary ? String(s.summary.userTurns) : '—';
  const rule = document.createElement('td');
  const top = s.summary?.topRules?.[0];
  rule.textContent = top ? (top.title || top.ruleId) : '—';
  tr.append(when, mode, min, turns, rule);
  rows.append(tr);
}

function appendJournalRow(rows, e) {
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
  seen.textContent = new Date(e.lastSeen).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
  tr.append(rule, example, count, seen);
  rows.append(tr);
}

async function loadHistory() {
  const rows = $('history-rows');
  const moreBtn = $('history-show-all');
  rows.textContent = '';
  try {
    const list = await api.sessions();
    const finished = list
      .filter((s) => s.endedAt)
      .sort((a, b) => new Date(b.startedAt) - new Date(a.startedAt));
    $('history-empty').hidden = finished.length > 0;
    $('history-table').hidden = finished.length === 0;
    const shown = historyShowAll ? finished : finished.slice(0, PROGRESS_PREVIEW);
    for (const s of shown) appendHistoryRow(rows, s);
    setProgressMoreButton(moreBtn, finished.length, historyShowAll);
    setProgressTableExpanded('history-table', historyShowAll);
  } catch {
    $('history-table').hidden = true;
    $('history-empty').hidden = false;
    $('history-empty').textContent = 'Could not load lesson history.';
    if (moreBtn) moreBtn.hidden = true;
  }
}

async function loadJournal() {
  const rows = $('journal-rows');
  const moreBtn = $('journal-show-all');
  try {
    const entries = (await api.errors()).slice().sort(
      (a, b) => new Date(b.lastSeen) - new Date(a.lastSeen),
    );
    rows.textContent = '';
    $('journal-empty').hidden = entries.length > 0;
    $('journal-table').hidden = entries.length === 0;
    const shown = journalShowAll ? entries : entries.slice(0, PROGRESS_PREVIEW);
    for (const e of shown) appendJournalRow(rows, e);
    setProgressMoreButton(moreBtn, entries.length, journalShowAll);
    setProgressTableExpanded('journal-table', journalShowAll);
  } catch (e) {
    $('journal-table').hidden = true;
    $('journal-empty').hidden = false;
    $('journal-empty').textContent = 'Could not load progress: ' + e.message;
    if (moreBtn) moreBtn.hidden = true;
  }
}

// ---------- экран урока ----------

function showLesson(session, messages) {
  $('fatal').hidden = true;
  state.session = session;
  bindLessonClock(session);
  state.history = messages || [];
  const list = $('messages');
  list.textContent = '';
  for (const m of state.history) {
    list.append(m.role === 'user' ? userMessage(m.text) : teacherMessage(m.reply, m.text));
  }
  showTab('lesson');
  applySession(session);
  if (session.mode === 'roleplay') refreshScenarioPanel();
  syncVoiceControls();
  updateLessonStats();
  scrollDown();
}

function renderScenarioProgress(progress) {
  if (!progress) {
    $('scenario-panel').hidden = true;
    return;
  }
  state.scenarioProgress = progress;
  $('scenario-panel').hidden = false;
  $('scenario-title').textContent = progress.titleRu + ' — ' + progress.done + '/' + progress.total;
  const list = $('scenario-goals');
  list.textContent = '';
  for (const g of progress.goals) {
    const li = document.createElement('li');
    li.className = g.done ? 'done' : '';
    li.textContent = (g.done ? '✓ ' : '○ ') + g.textRu;
    list.append(li);
  }
  updateLessonProgress();
  if (state.tab === 'learn') renderDashboard();
}

async function refreshScenarioPanel() {
  if (!state.session || state.session.mode !== 'roleplay') {
    renderScenarioProgress(null);
    return;
  }
  try {
    renderScenarioProgress(await api.scenarioProgress(state.session.id));
  } catch {
    renderScenarioProgress(state.scenarioProgress);
  }
}

function applySession(session) {
  state.session = session;
  $('lesson-level').textContent = session.cefr;
  $('lesson-mode').textContent = session.cefr + ' · ' + (MODE_NAMES[session.mode] || session.mode);
  const topicLabel = session.mode === 'roleplay'
    ? (state.scenarios.find((s) => s.slug === session.topic)?.titleRu || session.topic)
    : (session.topic || '');
  const topicEl = $('lesson-topic');
  topicEl.textContent = topicLabel;
  topicEl.hidden = !topicLabel;
  $('lesson-heading').textContent = heroTitle(session);
  $('lesson-lede').textContent = session.mode === 'roleplay'
    ? 'Stay in the scene. A goal ticks off when your line includes the cue.'
    : 'Write the next line in English, or press Speak.';
  $('hint-level').textContent = HINT_STEPS[Math.min(session.supportLevel, 3)];
  const index = LEVELS.indexOf(session.cefr);
  $('easier').disabled = state.busy || index <= 0;
  $('harder').disabled = state.busy || index >= LEVELS.length - 1;
  if (LEVELS_INFO[session.cefr]) {
    state.level = session.cefr;
    markCurrentLevel();
  }
  updateLessonProgress();
}

function setBusy(busy) {
  state.busy = busy;
  for (const id of ['send', 'hint', 'end', 'talk']) $(id).disabled = busy;
  syncVoiceControls();
  if (state.session) applySession(state.session);
}

function syncVoiceControls() {
  const on = !!$('voice-duplex').checked && !!state.session;
  $('voice-mode-wrap').hidden = !on;
  $('voice-phase').hidden = !on;
  const ptt = on && $('voice-mode').value === 'ptt';
  $('talk').disabled = state.busy || (on && !ptt);
  $('talk').title = on && !ptt
    ? 'In hands-free mode just speak — a pause ends the phrase'
    : 'Tap to speak. Hold the button, or tap again, to send';
}

async function enableVoiceDuplex() {
  if (!state.session || state.voiceDuplex) return;
  stopSpeaking();
  const duplex = new VoiceDuplex();
  const list = $('messages');
  try {
    await duplex.connect(state.session.id, {
      onPhase: () => {
        $('voice-phase').textContent = duplex.phaseLabel();
        syncVoiceControls();
      },
      onTranscript: (text) => {
        list.append(userMessage(text));
        state.voicePlaceholder = teacherPlaceholder();
        list.append(state.voicePlaceholder.node);
        scrollDown();
      },
      onDelta: (chunk) => {
        state.voicePlaceholder?.append(chunk);
        scrollDown();
      },
      onFinal: (payload) => {
        if (!state.voicePlaceholder) {
          state.voicePlaceholder = teacherPlaceholder();
          list.append(state.voicePlaceholder.node);
        }
        state.voicePlaceholder.finish(payload.teacher.reply, payload.teacher.text);
        applySession(payload.session);
        if (payload.scenario) renderScenarioProgress(payload.scenario);
        state.voicePlaceholder = null;
        scrollDown();
        refreshReviewCount();
      },
      onError: (message) => setStatus(message, true),
      onClose: () => {
        if (state.voiceDuplex === duplex) {
          state.voiceDuplex = null;
          $('voice-duplex').checked = false;
          syncVoiceControls();
        }
      },
    }, $('voice-mode').value);
    state.voiceDuplex = duplex;
    $('voice-phase').textContent = duplex.phaseLabel();
    syncVoiceControls();
    setStatus('');
  } catch (e) {
    $('voice-duplex').checked = false;
    syncVoiceControls();
    setStatus(e.message || 'Could not start voice mode', true);
  }
}

function disableVoiceDuplex(clearCheckbox = true) {
  if (state.voiceDuplex) {
    state.voiceDuplex.disconnect();
    state.voiceDuplex = null;
  }
  state.voicePlaceholder = null;
  if (clearCheckbox && $('voice-duplex')) $('voice-duplex').checked = false;
  if ($('voice-phase')) $('voice-phase').hidden = true;
  if ($('voice-mode-wrap')) $('voice-mode-wrap').hidden = true;
  if ($('talk')) syncVoiceControls();
}

$('voice-duplex').addEventListener('change', () => {
  if ($('voice-duplex').checked) enableVoiceDuplex();
  else disableVoiceDuplex(false);
});
$('voice-mode').addEventListener('change', () => {
  if ($('voice-duplex').checked) {
    disableVoiceDuplex(false);
    enableVoiceDuplex();
  }
});

function setStatus(text, isError, canRetry) {
  const node = $('status');
  node.hidden = !text;
  $('status-text').textContent = text || '';
  $('retry').hidden = !canRetry;
  node.classList.toggle('error', !!isError);
}

const talk = { recorder: null, chunks: [], startedAt: 0, stream: null, timer: null, arming: false };

function setTalking(on) {
  const button = $('talk');
  button.setAttribute('aria-pressed', String(on));
  button.textContent = on ? 'Speaking…' : 'Speak';
}

async function startTalk() {
  if (state.busy || talk.recorder || talk.arming) return;
  stopSpeaking();
  talk.arming = true;
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
  } catch {
    talk.arming = false;
    setStatus('Microphone is blocked', true);
    return;
  }
  const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : '';
  const recorder = new MediaRecorder(stream, mime ? { mimeType: mime } : undefined);
  talk.chunks = [];
  talk.stream = stream;
  talk.recorder = recorder;
  talk.startedAt = Date.now();
  talk.arming = false;
  recorder.ondataavailable = (event) => { if (event.data.size) talk.chunks.push(event.data); };
  recorder.start();
  setTalking(true);
  setStatus('Listening… release the button or tap again');
  talk.timer = setTimeout(() => { if (talk.recorder) stopTalk(); }, 30000);
}

function stopTalk() {
  const recorder = talk.recorder;
  if (!recorder || recorder.state === 'inactive') return;
  clearTimeout(talk.timer);
  const startedAt = talk.startedAt;
  talk.recorder = null;
  recorder.onstop = async () => {
    talk.stream.getTracks().forEach((track) => track.stop());
    talk.stream = null;
    setTalking(false);
    const blob = new Blob(talk.chunks, { type: recorder.mimeType || 'audio/webm' });
    talk.chunks = [];
    if (Date.now() - startedAt < 400 || blob.size < 800) {
      setStatus('That recording is too short', true);
      return;
    }
    setStatus('Transcribing…');
    try {
      const text = await transcribe(blob, state.session?.id);
      setStatus('');
      turn(text, false, true);
    } catch (e) {
      setStatus(e.message, true);
    }
  };
  recorder.stop();
}

window.addEventListener('tutor-speak-error', (event) => setStatus(event.detail, true));

const talkButton = $('talk');
talkButton.addEventListener('pointerdown', (event) => {
  if (event.button !== 0 || state.busy) return;
  event.preventDefault();
  if (state.voiceDuplex && $('voice-duplex').checked && $('voice-mode').value === 'ptt') {
    stopSpeaking();
    state.voiceDuplex.pttDown();
    setTalking(true);
    setStatus('Listening… release the button');
    return;
  }
  if (talk.recorder) {
    stopTalk();
    return;
  }
  talkButton.setPointerCapture(event.pointerId);
  startTalk();
});
talkButton.addEventListener('pointerup', () => {
  if (state.voiceDuplex && $('voice-duplex').checked && $('voice-mode').value === 'ptt' && talk.recorder == null) {
    state.voiceDuplex.pttUp();
    setTalking(false);
    setStatus('');
    return;
  }
  if (talk.recorder && Date.now() - talk.startedAt > 400) stopTalk();
});
talkButton.addEventListener('contextmenu', (event) => event.preventDefault());

$('retry').addEventListener('click', () => turn('', false));

function scrollDown() {
  const scroller = $('lesson-body');
  if (!scroller) return;
  scroller.scrollTop = scroller.scrollHeight;
}

async function turn(text, hint, aloud) {
  if (state.busy || !state.session) return;
  setBusy(true);
  setStatus('The teacher is thinking…');
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
        syncHistoryFromTurn(payload);
        updateLessonStats();
        if (payload.scenario) renderScenarioProgress(payload.scenario);
        if (aloud) {
          speakMessage(placeholder.node).catch(() => setStatus('Press Listen to hear the reply'));
        }
      },
      error(message) { failure = message; },
    });
  } catch (e) {
    failure = e.message;
  }

  if (failure) {
    placeholder.remove();
    if (shownUser) { shownUser.remove(); $('input').value = text; }
    setStatus(failure, true, !text && !hint);
    refreshHealth();
  } else {
    setStatus('');
    if ($('health').classList.contains('down')) refreshHealth();
    refreshReviewCount();
    updateLessonProgress();
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
    const note = document.createElement('div');
    note.className = 'chat-note';
    note.textContent = (delta > 0 ? 'Harder' : 'Easier') + ': ' + previous + ' → ' + next;
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
  if (state.busy || !state.session) return;
  try {
    disableVoiceDuplex();
    await api.endSession(state.session.id);
    await refreshGoal();
    setStatus('');
    showStart();
  } catch (e) {
    setStatus(e.message, true);
  }
});

function fatal(message) {
  const node = $('fatal');
  node.hidden = false;
  node.textContent = message;
}

async function boot() {
  refreshHealth();
  setInterval(refreshHealth, 30000);
  setInterval(() => { if (state.tab === 'learn' || state.tab === 'lesson') refreshGoal(); }, 30000);
  await loadScenarios();
  try {
    const profile = await api.profile();
    state.level = LEVELS_INFO[profile.cefr] ? profile.cefr : state.level;
    state.goalMin = profile.dailyGoalMin || 10;
    state.studyWeekdays = profile.studyWeekdays || state.studyWeekdays;
    $('goal-select').value = String(state.goalMin);
    await refreshGoal();
    const open = (await api.sessions()).find((s) => !s.endedAt);
    if (open) {
      state.session = open;
      bindLessonClock(open);
      state.level = open.cefr || state.level;
      state.history = await api.messages(open.id);
      if (open.mode === 'roleplay') {
        try { state.scenarioProgress = await api.scenarioProgress(open.id); } catch { /* чеклист подгрузится в уроке */ }
      }
    }
    showTab('learn');
  } catch (e) {
    showTab('learn');
    fatal('Cannot reach the server: ' + e.message);
  }
  refreshReviewCount();
  wirePracticeControls();
}

boot();

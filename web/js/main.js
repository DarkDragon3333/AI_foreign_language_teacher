import { api, chatTurn, transcribe } from './api.js?v=23';
import {
  userMessage, teacherMessage, teacherPlaceholder, speakMessage, stopSpeaking,
  lockPlacementCard, lockPlacementScheduleCard, placementLadderCard, placementOfferCard,
  createBrandDatePicker, isoLocalDate,
} from './render.js?v=31';
import { openReview, refreshReviewCount } from './review.js?v=19';
import { VoiceDuplex } from './voice.js?v=18';
import { stopActivePractice, wirePracticeControls } from './practice.js?v=31';
import { enterPracticeScreen, focusPracticeTab, pausePracticeScreen } from './practice-ui.js?v=2';

const LEVELS = ['A1', 'A2', 'B1', 'B2', 'C1', 'C2'];
const PLACEMENT_LEVEL_LABELS = {
  A1: 'A1 · Beginner',
  A2: 'A2 · Elementary',
  B1: 'B1 · Intermediate',
  B2: 'B2 · Upper intermediate',
  C1: 'C1 · Advanced',
  C2: 'C2 · Proficient',
};
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
  structured: 'Guided lesson', placement: 'Level check',
};
const MODE_HEADINGS = {
  conversation: 'Start a conversation',
  explain: 'Explain a rule',
  correct: 'Check my English',
  drill: 'A short drill',
  roleplay: 'Play a scene',
  structured: 'Guided lesson',
  placement: 'Find your level',
};
const PLACEMENT_STEP_TOTAL = 11;
const GOAL_MINUTES = [5, 10, 15, 20, 25, 30, 45, 60];
let placementSpeakInput = null;
let calTargetPicker = null;
const STRUCTURED_PHASES = ['warmup', 'input', 'practice', 'feedback'];
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
const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const DOW_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

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
  goalMin: 15,
  studyWeekdays: [1, 2, 3, 4, 5],
  targetEndDate: null,
  lessonTimerMs: 0,
  calendarYear: new Date().getFullYear(),
  calendarMonth: new Date().getMonth() + 1,
  selectedDate: null,
  monthSlots: new Map(),
};

const TAB_TITLES = {
  learn: 'Home',
  levels: 'Levels',
  journal: 'Progress',
  review: 'Useful words',
  lesson: 'Lesson',
  grammar: 'Grammar drills',
  speaking: 'Speaking',
  listening: 'Listen & learn',
};
const PRACTICE_TABS = new Set(['grammar', 'speaking', 'listening', 'review']);

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

function localIsoDate(date = new Date()) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

const TODAY_OPEN = {
  grammar: { tab: 'grammar' },
  speaking: { tab: 'speaking' },
  listening: { tab: 'listening' },
  review: { tab: 'review' },
  conversation: { tab: null },
};

function focusConversationStart() {
  showTab('learn');
  $('mode').value = 'conversation';
  $('mode').dispatchEvent(new Event('change'));
  document.querySelector('.hero')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

let scheduleLoad = 0;
let weekdayDraft = [];

function isoDate(year, month, day) {
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

function longDate(date) {
  return new Date(date + 'T12:00:00').toLocaleDateString('en-GB', {
    weekday: 'long', day: 'numeric', month: 'long',
  });
}

function startLabel(activityType) {
  const name = ACTIVITY_NAMES[activityType] || 'lesson';
  return 'Start ' + name.toLowerCase();
}

function paintWeekdayDraft() {
  for (const btn of document.querySelectorAll('#weekday-picks .dow-btn')) {
    const on = weekdayDraft.includes(Number(btn.dataset.dow));
    btn.classList.toggle('is-on', on);
    btn.setAttribute('aria-pressed', on ? 'true' : 'false');
  }
  const empty = weekdayDraft.length === 0;
  $('cal-days-hint').hidden = !empty;
  $('cal-settings-save').disabled = empty;
}

function shiftCalendarMonth(delta) {
  const date = new Date(state.calendarYear, state.calendarMonth - 1 + delta, 1);
  state.calendarYear = date.getFullYear();
  state.calendarMonth = date.getMonth() + 1;
  state.selectedDate = null;
  $('cal-day').hidden = true;
  loadSchedule();
}

function selectCalendarDay(date) {
  const cells = document.querySelectorAll('#cal-grid .cal-cell');
  if (state.selectedDate === date) {
    state.selectedDate = null;
    $('cal-day').hidden = true;
    for (const btn of cells) btn.setAttribute('aria-pressed', 'false');
    return;
  }
  state.selectedDate = date;
  for (const btn of cells) btn.setAttribute('aria-pressed', btn.dataset.date === date ? 'true' : 'false');
  renderDayPanel(date);
}

function renderCalendar() {
  const year = state.calendarYear;
  const month = state.calendarMonth;
  const monthName = MONTH_NAMES[month - 1];
  $('cal-month').textContent = monthName;
  $('cal-year').textContent = String(year);
  const grid = $('cal-grid');
  grid.setAttribute('aria-label', monthName + ' ' + year);
  grid.textContent = '';
  for (const name of DOW_LABELS) {
    const head = document.createElement('div');
    head.className = 'cal-dow';
    head.textContent = name;
    head.setAttribute('aria-hidden', 'true');
    grid.append(head);
  }
  const lead = (new Date(year, month - 1, 1).getDay() + 6) % 7;
  for (let i = 0; i < lead; i += 1) {
    const pad = document.createElement('div');
    pad.className = 'cal-pad';
    pad.setAttribute('aria-hidden', 'true');
    grid.append(pad);
  }
  const today = localIsoDate();
  const days = new Date(year, month, 0).getDate();
  const prefix = isoDate(year, month, 1).slice(0, 8);
  for (let day = 1; day <= days; day += 1) {
    const date = isoDate(year, month, day);
    const slot = state.monthSlots.get(date);
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'cal-cell';
    btn.dataset.date = date;
    btn.textContent = String(day);
    if (date === today) btn.classList.add('is-today');
    if (slot?.status === 'done') btn.classList.add('is-done');
    else if (slot?.status === 'skipped') btn.classList.add('is-skipped');
    else if (slot?.status === 'planned' && date >= today) btn.classList.add('is-planned');
    const bits = [longDate(date)];
    if (slot) {
      bits.push(ACTIVITY_NAMES[slot.activityType] || slot.activityType);
      bits.push(STATUS_NAMES[slot.status] || slot.status);
    }
    btn.setAttribute('aria-label', bits.join(', '));
    btn.setAttribute('aria-pressed', state.selectedDate === date ? 'true' : 'false');
    grid.append(btn);
  }
  if (state.selectedDate && !state.selectedDate.startsWith(prefix)) {
    state.selectedDate = null;
    $('cal-day').hidden = true;
  }
}

function renderDayPanel(date) {
  const panel = $('cal-day');
  panel.hidden = false;
  panel.textContent = '';
  const slot = state.monthSlots.get(date);
  const title = document.createElement('p');
  title.className = 'cal-day-date';
  title.textContent = longDate(date);
  panel.append(title);
  if (!slot) {
    const meta = document.createElement('p');
    meta.className = 'cal-day-meta';
    meta.textContent = 'Nothing planned.';
    panel.append(meta);
    return;
  }
  const meta = document.createElement('p');
  meta.className = 'cal-day-meta';
  meta.textContent = STATUS_NAMES[slot.status] || slot.status;
  panel.append(meta);
  const row = document.createElement('div');
  row.className = 'cal-day-row';
  if (slot.status === 'planned') {
    const select = document.createElement('select');
    select.setAttribute('aria-label', 'Lesson type');
    for (const [key, name] of Object.entries(ACTIVITY_NAMES)) {
      const opt = document.createElement('option');
      opt.value = key;
      opt.textContent = name;
      opt.selected = key === slot.activityType;
      select.append(opt);
    }
    select.addEventListener('change', () => {
      api.patchScheduleSlot(date, { activityType: select.value })
        .then(() => loadSchedule())
        .catch((e) => fatal(e.message));
    });
    row.append(select);
  } else {
    const type = document.createElement('span');
    type.className = 'cal-day-type';
    type.textContent = ACTIVITY_NAMES[slot.activityType] || slot.activityType;
    row.append(type);
  }
  const open = TODAY_OPEN[slot.activityType];
  if (open) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'cal-start';
    button.textContent = startLabel(slot.activityType);
    button.addEventListener('click', () => {
      if (open.tab) showTab(open.tab);
      else focusConversationStart();
    });
    row.append(button);
  }
  panel.append(row);
}

function showCalendarError(message) {
  const panel = $('cal-day');
  panel.hidden = false;
  panel.textContent = '';
  const meta = document.createElement('p');
  meta.className = 'cal-day-meta';
  meta.textContent = message;
  panel.append(meta);
}

async function loadSchedule() {
  const ticket = ++scheduleLoad;
  const year = state.calendarYear;
  const month = state.calendarMonth;
  $('cal-month').textContent = MONTH_NAMES[month - 1];
  $('cal-year').textContent = String(year);
  try {
    const data = await api.scheduleMonth(year, month);
    if (ticket !== scheduleLoad) return;
    state.studyWeekdays = data.studyWeekdays || [1, 2, 3, 4, 5];
    state.monthSlots = new Map((data.slots || []).map((slot) => [slot.date, slot]));
    renderCalendar();
    if (state.selectedDate) renderDayPanel(state.selectedDate);
  } catch (e) {
    if (ticket !== scheduleLoad) return;
    $('cal-grid').replaceChildren();
    showCalendarError(e.message || 'The calendar did not load.');
  }
}

$('cal-prev').addEventListener('click', () => shiftCalendarMonth(-1));
$('cal-next').addEventListener('click', () => shiftCalendarMonth(1));
$('cal-grid').addEventListener('click', (event) => {
  const btn = event.target.closest('.cal-cell');
  if (!btn || !$('cal-grid').contains(btn)) return;
  selectCalendarDay(btn.dataset.date);
});
$('cal-grid').addEventListener('keydown', (event) => {
  const step = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }[event.key];
  if (!step) return;
  const cells = [...document.querySelectorAll('#cal-grid .cal-cell')];
  const index = cells.indexOf(document.activeElement);
  if (index < 0) return;
  const next = cells[index + step];
  if (!next) return;
  event.preventDefault();
  next.focus();
});

function ensureCalTargetPicker() {
  const wrap = $('cal-target-end-wrap');
  if (!wrap || calTargetPicker) return;
  calTargetPicker = createBrandDatePicker({ min: isoLocalDate(new Date()) });
  wrap.append(calTargetPicker.root);
}

$('cal-settings').addEventListener('click', () => {
  weekdayDraft = [...state.studyWeekdays];
  paintWeekdayDraft();
  ensureCalTargetPicker();
  calTargetPicker?.setValue(state.targetEndDate || '');
  $('cal-settings').setAttribute('aria-expanded', 'true');
  $('cal-settings-dialog').showModal();
});
$('cal-settings-dialog').addEventListener('close', () => {
  $('cal-settings').setAttribute('aria-expanded', 'false');
});
$('cal-settings-dialog').addEventListener('click', (event) => {
  if (event.target === $('cal-settings-dialog')) $('cal-settings-dialog').close();
});
$('cal-settings-close').addEventListener('click', () => $('cal-settings-dialog').close());
for (const btn of document.querySelectorAll('#weekday-picks .dow-btn')) {
  btn.addEventListener('click', () => {
    const dow = Number(btn.dataset.dow);
    if (weekdayDraft.includes(dow)) weekdayDraft = weekdayDraft.filter((day) => day !== dow);
    else weekdayDraft = [...weekdayDraft, dow].sort((a, b) => a - b);
    paintWeekdayDraft();
  });
}
$('cal-settings-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (weekdayDraft.length === 0) return;
  const button = $('cal-settings-save');
  button.disabled = true;
  try {
    const targetEndDate = calTargetPicker?.getValue()?.trim() || '';
    await api.updateProfile({ studyWeekdays: weekdayDraft, targetEndDate });
    const saved = await api.scheduleWeekdays(weekdayDraft);
    state.studyWeekdays = saved.studyWeekdays || weekdayDraft;
    state.targetEndDate = targetEndDate || null;
    $('cal-settings-dialog').close();
    await loadSchedule();
  } catch (e) {
    fatal(e.message);
    paintWeekdayDraft();
  }
});

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
  if (state.session?.mode === 'placement') {
    const idx = state.session.placementStepIndex || 1;
    return {
      done: Math.min(idx, PLACEMENT_STEP_TOTAL),
      total: state.session.placementStepTotal || PLACEMENT_STEP_TOTAL,
    };
  }
  if (state.session?.mode === 'structured' && state.session.lessonPhase) {
    const idx = STRUCTURED_PHASES.indexOf(state.session.lessonPhase);
    const done = idx >= 0 ? idx + 1 : 1;
    return { done, total: STRUCTURED_PHASES.length };
  }
  const turns = state.session ? userTurnCount() : 0;
  return { done: Math.min(turns, PRACTICE_STEPS), total: PRACTICE_STEPS };
}

function progressLabel(counts) {
  if (state.session?.mode === 'roleplay' && state.scenarioProgress) {
    return counts.done + ' of ' + counts.total + ' goals';
  }
  if (state.session?.mode === 'placement') {
    return 'Step ' + counts.done + ' of ' + counts.total;
  }
  if (state.session?.mode === 'structured') {
    return 'Phase ' + counts.done + ' of ' + counts.total;
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
  const form = $('start-form');
  if (!form) return;
  for (const button of form.querySelectorAll('[data-mode]')) {
    button.setAttribute('aria-pressed', String(button.dataset.mode === mode));
  }
}

function selectedDurationMin() {
  const checked = document.querySelector('input[name="duration"]:checked');
  return checked ? Number(checked.value) : 15;
}

const SECONDARY_LESSON_MODES = new Set(['explain', 'correct', 'drill', 'roleplay']);

function syncStartForm() {
  const mode = $('mode').value;
  const roleplay = mode === 'roleplay';
  const structured = mode === 'structured';
  $('scenario-wrap').hidden = !roleplay;
  $('topic-wrap').hidden = roleplay;
  const guided = $('structured-options');
  if (guided) guided.hidden = !structured;
  const more = $('lesson-mode-more');
  if (more) more.open = SECONDARY_LESSON_MODES.has(mode);
  syncModePills();
  if (!state.session) renderHeroCopy();
}

function placementUiStage(session) {
  const idx = session?.placementStepIndex || 1;
  if (idx >= PLACEMENT_STEP_TOTAL || session?.placementStage === 'plan' || session?.placementStage === 'ready') {
    return 'plan';
  }
  if (idx >= 9 || session?.placementStage === 'survey') return 'survey';
  return 'test';
}

function updatePlacementPanel(session) {
  const wrap = $('placement-phase-wrap');
  if (!wrap) return;
  const on = session?.mode === 'placement';
  wrap.hidden = !on;
  if (!on) return;
  const stage = placementUiStage(session);
  for (const seg of wrap.querySelectorAll('[data-placement-stage]')) {
    seg.classList.toggle('is-current', seg.dataset.placementStage === stage);
  }
  const label = $('placement-step-label');
  if (label) label.textContent = session.placementStepLabel || '';
}

function summarizePlacementPlan(plan) {
  if (!plan) return '';
  const days = (plan.studyWeekdays || []).map((d) => DOW_LABELS[d - 1] || d).join(', ');
  const slots = plan.monthSlots?.length || 0;
  const goal = plan.dailyGoalMin || state.goalMin || 15;
  const wd = (plan.studyWeekdays?.length ? plan.studyWeekdays : state.studyWeekdays) || [1, 2, 3, 4, 5];
  const dayLabels = wd.map((d) => DOW_LABELS[d - 1] || d).join(', ');
  const end = plan.targetEndDate || state.targetEndDate;
  const endNote = end ? ` · until ${end}` : '';
  return `${goal} min/day · ${dayLabels || days || 'Mon–Fri'} · ${slots} sessions this month${endNote}`;
}

function placementRenderMeta(session, messageIndex, messages) {
  const idx = session?.placementStepIndex || 1;
  const total = session?.placementStepTotal || PLACEMENT_STEP_TOTAL;
  const label = session?.placementStepLabel || 'Placement';
  const unansweredTeacher = placementPendingTeacherIndex(messages);
  if (messageIndex === unansweredTeacher) {
    return `${idx} / ${total} · ${label}`;
  }
  let step = 0;
  for (let i = 0; i <= messageIndex; i++) {
    const m = messages[i];
    if (m?.role === 'teacher' && teacherPlacementPrompt(m)) step += 1;
  }
  return `${step || idx} / ${total} · Placement`;
}

function teacherPlacementPrompt(teacherMsg) {
  const reply = teacherMsg?.reply;
  if (!reply) return '';
  return (reply.next_prompt || '').trim() || (reply.reply_en || '').trim();
}

function placementUserAnswerInCard(session, messages, index) {
  if (session?.mode !== 'placement' || session?.placementPlanReady) return false;
  if (messages[index]?.role !== 'user') return false;
  const prev = messages[index - 1];
  return prev?.role === 'teacher' && !!teacherPlacementPrompt(prev);
}

function placementPendingTeacherIndex(messages) {
  if (!messages?.length) return -1;
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.role === 'teacher' && teacherPlacementPrompt(m)) {
      if (i === messages.length - 1) return i;
      if (messages[i + 1]?.role !== 'user') return i;
      return -1;
    }
  }
  return -1;
}

function isPlacementScheduleStep(session) {
  return session?.mode === 'placement'
    && !session?.placementPlanReady
    && session.placementStepIndex === 10;
}

function placementMessageStepIndex(messages, messageIndex) {
  let step = 0;
  for (let i = 0; i <= messageIndex; i++) {
    const m = messages[i];
    if (m?.role === 'teacher' && teacherPlacementPrompt(m)) step += 1;
  }
  return step;
}

function placementScheduleWidgetFor(session, messageIndex, messages, active, lockedAnswer) {
  if (session?.mode !== 'placement' || session?.placementPlanReady) return false;
  const stage = session?.placementStage || '';
  if (stage === 'plan' || stage === 'ready') return false;
  if (active && isPlacementScheduleStep(session)) return true;
  return placementMessageStepIndex(messages, messageIndex) === 10
    && !!(lockedAnswer && /days a week/i.test(lockedAnswer));
}

function placementRenderOpts(session, messageIndex, messages) {
  if (session?.mode !== 'placement' || session?.placementPlanReady) return {};
  const pending = placementPendingTeacherIndex(messages);
  const active = messageIndex === pending;
  let lockedAnswer = active ? null : (messages[messageIndex + 1]?.role === 'user' ? messages[messageIndex + 1].text : null);
  if (lockedAnswer != null) {
    const t = lockedAnswer.trim();
    if (!t || t === '-' || t === '—' || t === '–') lockedAnswer = null;
  }
  const scheduleWidget = placementScheduleWidgetFor(session, messageIndex, messages, active, lockedAnswer);
  return {
    placement: true,
    stepMeta: placementRenderMeta(session, messageIndex, messages),
    active,
    lockedAnswer,
    scheduleWidget,
    scheduleDefaults: {
      weekdays: state.studyWeekdays, goalMin: state.goalMin, targetEndDate: state.targetEndDate,
    },
    onScheduleSubmit: scheduleWidget && active ? submitPlacementSchedule : null,
    onSubmit: scheduleWidget ? null : (active ? submitPlacementAnswer : null),
    onSpeak: scheduleWidget ? null : (active ? startPlacementSpeak : null),
  };
}

function syncPlacementComposer() {
  const form = $('chat-form');
  if (!form) return;
  const on = state.session?.mode === 'placement' && !state.session?.placementPlanReady;
  form.classList.toggle('placement-composer-slim', on);
}

function placementLadderMeta(session) {
  const idx = session?.placementStepIndex || 1;
  const total = session?.placementStepTotal || 8;
  const label = session?.placementStepLabel || 'Level check';
  return `${idx} / ${total} · ${label}`;
}

function syncPlacementLadderCard() {
  const list = $('messages');
  if (!list) return;
  list.querySelectorAll('.placement-ladder, .placement-offer').forEach((node) => node.remove());
  const session = state.session;
  if (!session || session.mode !== 'placement' || session.placementPlanReady) return;
  if (session.placementOffer) {
    list.append(placementOfferCard({
      level: session.placementOffer.level,
      note: session.placementOffer.note,
      onAccept: acceptPlacementLevel,
      onRefine: startPlacementRefine,
    }));
  } else if (session.placementLadderItem) {
    const item = session.placementLadderItem;
    list.append(placementLadderCard({
      id: item.id,
      stem: item.stem,
      choices: item.choices,
      stepMeta: placementLadderMeta(session),
      onChoose: submitPlacementLadder,
    }));
  }
  scrollDown();
}

async function submitPlacementLadder(itemId, choice) {
  if (state.busy || !state.session) return;
  setBusy(true);
  let session = null;
  try {
    session = await api.placementLadder(state.session.id, itemId, choice);
    applySession(session);
    syncPlacementLadderCard();
  } catch (e) {
    fatal(e.message);
    syncPlacementLadderCard();
  } finally {
    setBusy(false);
  }
  if (session?.placementStage === 'survey') await turn('', false);
}

async function acceptPlacementLevel() {
  if (state.busy || !state.session) return;
  setBusy(true);
  let session = null;
  try {
    session = await api.placementLadderAccept(state.session.id);
    applySession(session);
    syncPlacementLadderCard();
  } catch (e) {
    fatal(e.message);
    syncPlacementLadderCard();
  } finally {
    setBusy(false);
  }
  if (session?.placementStage === 'survey') await turn('', false);
}

async function startPlacementRefine() {
  if (state.busy || !state.session) return;
  setBusy(true);
  try {
    const session = await api.placementLadderRefine(state.session.id);
    applySession(session);
    syncPlacementLadderCard();
  } catch (e) {
    fatal(e.message);
    syncPlacementLadderCard();
  } finally {
    setBusy(false);
  }
}

function submitPlacementAnswer(text, card) {
  if (state.busy || !state.session) return;
  const trimmed = (text || '').trim();
  if (!trimmed || /^[-—–.]+$/.test(trimmed)) return;
  lockPlacementCard(card, trimmed);
  scrollDown();
  turn(trimmed, false, false, { userAlreadyShown: true });
}

async function submitPlacementSchedule({ weekdays, goalMin, targetEndDate, summary }, card) {
  if (state.busy || !state.session) return;
  lockPlacementScheduleCard(card, summary);
  try {
    await api.updateProfile({
      dailyGoalMin: goalMin, studyWeekdays: weekdays, targetEndDate: targetEndDate || '',
    });
    state.goalMin = goalMin;
    state.studyWeekdays = weekdays;
    state.targetEndDate = targetEndDate || null;
    if ($('goal-select')) $('goal-select').value = String(goalMin);
  } catch (e) {
    fatal(e.message);
    return;
  }
  scrollDown();
  turn(summary, false, false, { userAlreadyShown: true, suppressScheduleWidget: true });
}

function startPlacementSpeak(card) {
  if (state.busy || talk.recorder) return;
  placementSpeakInput = card?.querySelector('.grammar-answer-input');
  startTalk();
}

function showPlacementResult(session) {
  const ready = session?.placementPlanReady && session.placementPlan;
  const card = $('placement-result');
  const form = $('chat-form');
  if (!card || !form) return;
  if (!ready) {
    card.hidden = true;
    form.hidden = false;
    return;
  }
  card.hidden = false;
  form.hidden = true;
  const recommended = session.placementAssessedLevel || session.cefr || state.level;
  const pick = $('placement-level-pick');
  for (const opt of pick.options) {
    const base = PLACEMENT_LEVEL_LABELS[opt.value] || opt.textContent.replace(/\s*\(Recommended\)\s*$/, '');
    opt.textContent = opt.value === recommended ? `${base} (Recommended)` : base;
  }
  pick.value = LEVELS.includes(recommended) ? recommended : state.level;
  const badge = $('placement-recommended-badge');
  badge.textContent = `Recommended: ${recommended}`;
  badge.hidden = false;
  $('placement-result-lead').textContent = 'We estimated your level from the test. You can adjust it before saving.';
  const planView = session.placementPlan ? {
    ...session.placementPlan,
    dailyGoalMin: state.goalMin,
    studyWeekdays: state.studyWeekdays,
  } : null;
  $('placement-plan-summary').textContent = summarizePlacementPlan(planView);
  syncPlacementComposer();
}

function updateStructuredPanel(session) {
  const wrap = $('structured-phase-wrap');
  if (!wrap) return;
  const on = session?.mode === 'structured';
  wrap.hidden = !on;
  if (!on) return;
  const phase = session.lessonPhase || 'warmup';
  for (const seg of wrap.querySelectorAll('.structured-phase-seg')) {
    seg.classList.toggle('is-current', seg.dataset.phase === phase);
  }
  const left = $('structured-time-left');
  if (left) {
    const mins = session.minutesRemaining;
    const label = session.phaseLabel || phase;
    if (session.lessonPhase === 'feedback') {
      left.textContent = mins != null
        ? `Feedback · about ${mins} min left — you can wrap up when ready.`
        : 'Feedback — you can wrap up when ready.';
    } else {
      left.textContent = mins != null
        ? `${label} · about ${mins} min left in the lesson`
        : label;
    }
  }
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
  const inLesson = state.tab === 'lesson';
  const inPlacement = inLesson && state.session?.mode === 'placement';
  const inPractice = PRACTICE_TABS.has(state.tab);
  document.querySelector('.app')?.classList.toggle('placement-onboarding', inPlacement);
  $('placement-manual').hidden = !inPlacement || $('placement-result')?.hidden === false;
  $('exit-lesson').hidden = !inLesson;
  $('exit-practice').hidden = !inPractice || inLesson;
  const stopBtn = $('stop-practice');
  stopBtn.hidden = !inPractice || inLesson || state.tab === 'review';
  $('range-label').hidden = inLesson;
  document.title = (TAB_TITLES[state.tab] || TAB_TITLES.learn) + ' · English learning';
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
  for (const id of ['learn', 'levels', 'journal', 'review', 'grammar', 'speaking', 'listening', 'lesson']) {
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

async function beginPlacement() {
  return beginLesson({ mode: 'placement', topic: 'placement', cefr: state.level });
}

async function applyPlacementPlan() {
  const session = state.session;
  const level = $('placement-level-pick').value;
  const plan = session?.placementPlan;
  if (!plan) {
    fatal('Plan is not ready yet.');
    return;
  }
  $('placement-apply').disabled = true;
  try {
    const planToApply = {
      ...plan,
      dailyGoalMin: state.goalMin,
      studyWeekdays: state.studyWeekdays,
      targetEndDate: state.targetEndDate || plan.targetEndDate || null,
    };
    await api.updateProfile({
      cefr: level,
      dailyGoalMin: planToApply.dailyGoalMin,
      studyWeekdays: planToApply.studyWeekdays,
      targetEndDate: planToApply.targetEndDate || '',
    });
    state.level = level;
    $('goal-select').value = String(planToApply.dailyGoalMin);
    await api.applySchedulePlan(planToApply);
    if (session.id) await api.endSession(session.id);
    state.session = null;
    state.history = [];
    showStart();
    markCurrentLevel();
    loadSchedule();
    renderHeroCopy();
  } catch (e) {
    fatal(e.message);
  } finally {
    $('placement-apply').disabled = false;
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
    const payload = { cefr: level, topic, mode };
    if (mode === 'structured') payload.durationMin = selectedDurationMin();
    const session = await api.startSession(payload);
    state.history = [];
    state.scenarioProgress = null;
    resetLessonClock(session.id);
    showLesson(session, []);
    if (session.placementLadderItem || session.placementOffer) return;
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
  if (!(state.history || []).length
      && !state.session.placementLadderItem
      && !state.session.placementOffer) turn('', false);
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

$('start-form').addEventListener('click', (event) => {
  const button = event.target.closest('[data-mode]');
  if (!button) return;
  event.preventDefault();
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
  beginPlacement().catch((e) => fatal(e.message));
});
$('placement-manual').addEventListener('click', async () => {
  if (!state.session) return;
  const level = window.prompt('CEFR level (A1–C2)', state.level);
  if (!level || !LEVELS.includes(level.trim().toUpperCase())) return;
  try {
    const updated = await api.placementManual(state.session.id, level.trim().toUpperCase());
    state.session = updated;
    applySession(updated);
    showPlacementResult(updated);
    updateChrome();
  } catch (e) {
    fatal(e.message);
  }
});
$('placement-apply').addEventListener('click', () => applyPlacementPlan());
$('open-grammar').addEventListener('click', () => showTab('grammar'));
$('open-speaking').addEventListener('click', () => showTab('speaking'));
$('open-listen').addEventListener('click', () => showTab('listening'));

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
  const msgs = state.history || [];
  const pending = placementPendingTeacherIndex(msgs);
  msgs.forEach((m, i) => {
    if (m.role === 'user') {
      if (!placementUserAnswerInCard(session, msgs, i)) list.append(userMessage(m.text));
    } else {
      list.append(teacherMessage(m.reply, m.text, placementRenderOpts(session, i, msgs)));
    }
  });
  syncPlacementComposer();
  showTab('lesson');
  applySession(session);
  if (session.mode === 'roleplay') refreshScenarioPanel();
  else updateStructuredPanel(session);
  updatePlacementPanel(session);
  showPlacementResult(session);
  syncPlacementLadderCard();
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
    : session.mode === 'structured'
      ? 'Follow the phase: warm-up, input, practice, then feedback.'
      : session.mode === 'placement'
        ? 'Answer in English when you can; the test adapts to your replies.'
        : 'Write the next line in English, or press Speak.';
  updateStructuredPanel(session);
  updatePlacementPanel(session);
  showPlacementResult(session);
  syncPlacementComposer();
  updateChrome();
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
  for (const card of document.querySelectorAll('.placement-task.is-active')) {
    const input = card.querySelector('.grammar-answer-input');
    const send = card.querySelector('.placement-send');
    const mic = card.querySelector('.placement-mic');
    if (input && !card.classList.contains('is-locked')) input.disabled = busy;
    if (send) send.disabled = busy;
    if (mic) mic.disabled = busy;
    for (const btn of card.querySelectorAll('.dow-btn, .placement-duration-chip')) {
      if (!card.classList.contains('is-locked')) btn.disabled = busy;
    }
  }
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
  setStatus('Recording… tap Speak again to send (up to 60 s)');
  talk.timer = setTimeout(() => { if (talk.recorder) stopTalk(); }, 60000);
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
      if (placementSpeakInput) {
        placementSpeakInput.value = text;
        placementSpeakInput.focus();
        placementSpeakInput = null;
      } else {
        turn(text, false, true);
      }
    } catch (e) {
      setStatus(e.message, true);
    }
  };
  recorder.stop();
}

window.addEventListener('tutor-speak-error', (event) => setStatus(event.detail, true));

const talkButton = $('talk');
talkButton.addEventListener('click', (event) => {
  if (event.button !== 0 || state.busy) return;
  if (state.voiceDuplex && $('voice-duplex').checked && $('voice-mode').value === 'ptt') return;
  event.preventDefault();
  if (talk.recorder) {
    stopTalk();
    return;
  }
  startTalk();
});
talkButton.addEventListener('pointerdown', (event) => {
  if (event.button !== 0 || state.busy) return;
  if (state.voiceDuplex && $('voice-duplex').checked && $('voice-mode').value === 'ptt') {
    event.preventDefault();
    stopSpeaking();
    state.voiceDuplex.pttDown();
    setTalking(true);
    setStatus('Listening… release the button');
    talkButton.setPointerCapture(event.pointerId);
  }
});
talkButton.addEventListener('pointerup', () => {
  if (state.voiceDuplex && $('voice-duplex').checked && $('voice-mode').value === 'ptt' && talk.recorder == null) {
    state.voiceDuplex.pttUp();
    setTalking(false);
    setStatus('');
  }
});
talkButton.addEventListener('contextmenu', (event) => event.preventDefault());

$('retry').addEventListener('click', () => {
  const { text, hint, aloud } = lastTurnDraft;
  turn(text, hint, aloud);
});

function scrollDown() {
  const scroller = $('lesson-body');
  if (!scroller) return;
  scroller.scrollTop = scroller.scrollHeight;
}

const lastTurnDraft = { text: '', hint: false, aloud: false };

async function turn(text, hint, aloud, opts = {}) {
  if (state.busy || !state.session) return;
  setBusy(true);
  setStatus('The teacher is thinking…');
  const list = $('messages');
  const placement = state.session.mode === 'placement' && !state.session.placementPlanReady;
  const shownUser = !opts.userAlreadyShown && text ? userMessage(text) : null;
  if (shownUser) list.append(shownUser);
  const scheduleTurn = placement && isPlacementScheduleStep(state.session) && !opts.suppressScheduleWidget;
  const placeholderOpts = placement ? {
    placement: true,
    suppressScheduleWidget: !!opts.suppressScheduleWidget,
    stepMeta: placementRenderMeta(state.session, placementPendingTeacherIndex(state.history), state.history),
    active: true,
    scheduleWidget: scheduleTurn,
    scheduleDefaults: {
      weekdays: state.studyWeekdays, goalMin: state.goalMin, targetEndDate: state.targetEndDate,
    },
    onScheduleSubmit: scheduleTurn ? submitPlacementSchedule : null,
    onSubmit: scheduleTurn ? null : submitPlacementAnswer,
    onSpeak: scheduleTurn ? null : startPlacementSpeak,
  } : {};
  const placeholder = teacherPlaceholder(placeholderOpts);
  list.append(placeholder.node);
  scrollDown();
  lastTurnDraft.text = text;
  lastTurnDraft.hint = !!hint;
  lastTurnDraft.aloud = !!aloud;

  let failure = null;
  try {
    await chatTurn({ sessionId: state.session.id, text, hint }, {
      delta(chunk) {
        setStatus('');
        if (!placement) {
          placeholder.append(chunk);
          scrollDown();
        }
      },
      final(payload) {
        if (!shownUser && !opts.userAlreadyShown && payload.user) {
          placeholder.node.before(userMessage(payload.user.text));
        }
        applySession(payload.session);
        syncHistoryFromTurn(payload);
        const finishOpts = placement
          ? placementRenderOpts(state.session, state.history.length - 1, state.history)
          : {};
        placeholder.finish(payload.teacher.reply, payload.teacher.text, finishOpts);
        syncPlacementComposer();
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
    const placement = state.session?.mode === 'placement';
    const retryable = placement || !text || hint;
    const message = placement
      ? `Не удалось получить ответ учителя. ${failure} Текст сообщения сохранён — нажмите Try again.`
      : failure;
    setStatus(message, true, retryable);
    refreshHealth();
    if (placement) updatePlacementPanel(state.session);
  } else {
    setStatus('');
    if ($('health').classList.contains('down')) refreshHealth();
    refreshReviewCount();
    updateLessonProgress();
  }
  setBusy(false);
  fitInput();
  scrollDown();
  const cardInput = document.querySelector('.placement-task.is-active .grammar-answer-input:not(:disabled)');
  if (cardInput) cardInput.focus();
  else if (!placement) $('input').focus();
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
  window.addEventListener('schedule-refresh', () => loadSchedule());
  refreshHealth();
  setInterval(refreshHealth, 30000);
  setInterval(() => { if (state.tab === 'learn' || state.tab === 'lesson') refreshGoal(); }, 30000);
  await loadScenarios();
  try {
    const profile = await api.profile();
    state.level = LEVELS_INFO[profile.cefr] ? profile.cefr : state.level;
    state.goalMin = profile.dailyGoalMin || 15;
    state.studyWeekdays = profile.studyWeekdays || state.studyWeekdays;
    state.targetEndDate = profile.targetEndDate || null;
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

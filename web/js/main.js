import { api, chatTurn, transcribe } from './api.js?v=16';
import { userMessage, teacherMessage, teacherPlaceholder, speakMessage, stopSpeaking } from './render.js?v=16';
import { openReview, refreshReviewCount } from './review.js?v=16';
import { VoiceDuplex } from './voice.js?v=16';

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
const GOAL_MIN = 10;

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
};

const CRUMBS = {
  learn: ['Learn', 'Overview'],
  levels: ['Learn', 'Levels'],
  journal: ['Learn', 'Progress'],
  review: ['Learn', 'Practice'],
  lesson: ['Learn', 'Practice'],
};

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

function todayKey() {
  const d = new Date();
  return d.getFullYear() + '-' + (d.getMonth() + 1) + '-' + d.getDate();
}

function readPractice() {
  try {
    const raw = JSON.parse(localStorage.getItem('tutor-practice') || '{}');
    if (raw.date !== todayKey()) return 0;
    return Number(raw.ms) || 0;
  } catch {
    return 0;
  }
}

function addPractice(ms) {
  if (ms <= 0) return readPractice();
  const next = readPractice() + ms;
  try { localStorage.setItem('tutor-practice', JSON.stringify({ date: todayKey(), ms: next })); } catch { /* приватный режим */ }
  return next;
}

let practiceMark = Date.now();
function flushPractice() {
  const now = Date.now();
  if (state.tab === 'lesson' && document.visibilityState === 'visible') addPractice(now - practiceMark);
  practiceMark = now;
}

function renderGoal() {
  const ms = readPractice();
  const mins = Math.min(GOAL_MIN, Math.floor(ms / 60000));
  $('goal-value').textContent = String(mins);
  $('goal-fill').style.width = Math.min(100, (ms / (GOAL_MIN * 60000)) * 100) + '%';
  $('goal-caption').textContent = mins + ' of ' + GOAL_MIN + ' minutes complete';
  $('goal-fill').parentElement.setAttribute('aria-valuenow', String(mins));
}

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
  for (const chip of document.querySelectorAll('#path-chips [data-level]')) {
    chip.classList.toggle('is-current', chip.dataset.level === current);
  }
  const info = LEVELS_INFO[current] || LEVELS_INFO.A1;
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

function renderDashboard() {
  markCurrentLevel();
  renderHeroCopy();
  renderGoal();
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
  $('exit-lesson').hidden = !inLesson;
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
  flushPractice();
  if (state.tab === 'lesson' && tab !== 'lesson') disableVoiceDuplex();
  state.tab = tab;
  for (const id of ['learn', 'levels', 'journal', 'review', 'lesson']) {
    $(id).hidden = id !== tab;
  }
  updateChrome();
  if (tab === 'journal') loadJournal();
  if (tab === 'review') openReview();
  if (tab === 'learn') renderDashboard();
  if (tab === 'levels') markCurrentLevel();
  if (tab === 'lesson') {
    updateLessonProgress();
    $('input').focus();
  }
}

function showStart() {
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
$('exit-lesson').addEventListener('click', () => showTab('learn'));
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
$('path-chips').addEventListener('click', (event) => {
  const chip = event.target.closest('[data-level]');
  if (!chip) return;
  selectLevel(chip.dataset.level);
  showTab('levels');
});
$('level-check').addEventListener('click', () => {
  beginLesson({ mode: 'conversation', topic: 'level check' }).catch((e) => fatal(e.message));
});
$('open-listen').addEventListener('click', () => {
  const topic = $('topic').value.trim() || 'everyday English';
  beginLesson({ mode: 'conversation', topic }).catch((e) => fatal(e.message));
});

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
      seen.textContent = new Date(e.lastSeen).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
      tr.append(rule, example, count, seen);
      rows.append(tr);
    }
  } catch (e) {
    $('journal-table').hidden = true;
    $('journal-empty').hidden = false;
    $('journal-empty').textContent = 'Could not load progress: ' + e.message;
  }
}

// ---------- экран урока ----------

function showLesson(session, messages) {
  $('fatal').hidden = true;
  state.session = session;
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
  $('lesson-mode').textContent = MODE_NAMES[session.mode] || session.mode;
  const topicLabel = session.mode === 'roleplay'
    ? (state.scenarios.find((s) => s.slug === session.topic)?.titleRu || session.topic)
    : (session.topic || '');
  $('lesson-topic').textContent = topicLabel;
  $('lesson-kicker').textContent = session.cefr + ' · ' + (MODE_NAMES[session.mode] || session.mode);
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
  const list = $('messages');
  list.scrollTop = list.scrollHeight;
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
  setInterval(flushPractice, 5000);
  document.addEventListener('visibilitychange', flushPractice);
  renderGoal();
  await loadScenarios();
  try {
    const profile = await api.profile();
    state.level = LEVELS_INFO[profile.cefr] ? profile.cefr : state.level;
    const open = (await api.sessions()).find((s) => !s.endedAt);
    if (open) {
      state.session = open;
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
}

boot();

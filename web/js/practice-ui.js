const $ = (id) => document.getElementById(id);

let practiceTimerMs = 0;
let practiceRunningSince = null;
let practiceTimerInterval = null;
let activePractice = null;
const practiceCounts = {
  grammar: { correct: 0, mistakes: 0, done: 0, total: 6 },
  speaking: { correct: 0, mistakes: 0, done: 0, total: 6 },
  listening: { correct: 0, mistakes: 0, done: 0, total: 4 },
  review: { correct: 0, mistakes: 0, done: 0, total: 0 },
};

function formatElapsed(ms) {
  const totalSec = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(totalSec / 60);
  const s = totalSec % 60;
  return m + ':' + String(s).padStart(2, '0');
}

function practiceElapsedMs() {
  let ms = practiceTimerMs;
  if (practiceRunningSince != null) ms += Date.now() - practiceRunningSince;
  return ms;
}

function paintPracticeProgress(prefix, done, total) {
  const counts = { done: done ?? 0, total: total || 1 };
  const pct = counts.total ? Math.min(100, (counts.done / counts.total) * 100) : 0;
  const fill = $(`${prefix}-bar`);
  const label = $(`${prefix}-fraction`);
  const bar = $(`${prefix}-bar-wrap`);
  if (fill) fill.style.width = pct + '%';
  if (label) label.textContent = counts.done + ' / ' + counts.total;
  if (bar) {
    bar.setAttribute('aria-valuenow', String(counts.done));
    bar.setAttribute('aria-valuemax', String(counts.total));
  }
}

export function refreshPracticeChrome(name) {
  const key = name && practiceCounts[name] ? name : activePractice;
  if (!key) return;
  const c = practiceCounts[key];
  paintPracticeProgress(key, c.done, c.total);
  const correctEl = $(`${key}-correct`);
  const mistakesEl = $(`${key}-mistakes`);
  if (correctEl) correctEl.textContent = String(c.correct);
  if (mistakesEl) mistakesEl.textContent = String(c.mistakes);
  const elapsed = $(`${key}-elapsed`);
  if (elapsed) elapsed.textContent = formatElapsed(practiceElapsedMs());
}

function stopPracticeInterval() {
  if (practiceTimerInterval) clearInterval(practiceTimerInterval);
  practiceTimerInterval = null;
}

export function enterPracticeScreen(name) {
  if (activePractice !== name) {
    pausePracticeScreen();
    practiceTimerMs = 0;
  }
  activePractice = name;
  if (practiceRunningSince == null) practiceRunningSince = Date.now();
  stopPracticeInterval();
  refreshPracticeChrome(name);
  practiceTimerInterval = setInterval(() => refreshPracticeChrome(name), 1000);
}

export function pausePracticeScreen() {
  if (practiceRunningSince != null) {
    practiceTimerMs = practiceElapsedMs();
    practiceRunningSince = null;
  }
  stopPracticeInterval();
  refreshPracticeChrome(activePractice);
}

export function stopPracticeTimer() {
  stopPracticeInterval();
  if (practiceRunningSince != null) {
    practiceTimerMs = practiceElapsedMs();
    practiceRunningSince = null;
  }
  refreshPracticeChrome(activePractice);
}

/** Ends the timed drill: zero clock, stop interval, reset session counters. */
export function endPracticeSession(name) {
  const key = name || activePractice;
  stopPracticeInterval();
  practiceTimerMs = 0;
  practiceRunningSince = null;
  if (key && practiceCounts[key]) {
    const total = practiceCounts[key].total;
    resetPracticeStats(key, total);
  }
}

export function focusPracticeTab(name) {
  activePractice = name;
  refreshPracticeChrome(name);
}

export function startPracticeTimedSession(name, total) {
  stopPracticeInterval();
  activePractice = name;
  practiceTimerMs = 0;
  practiceRunningSince = Date.now();
  resetPracticeStats(name, total);
  refreshPracticeChrome(name);
  practiceTimerInterval = setInterval(() => refreshPracticeChrome(name), 1000);
}

export function resetPracticeStats(name, total) {
  practiceCounts[name] = { correct: 0, mistakes: 0, done: 0, total: total ?? practiceCounts[name]?.total ?? 6 };
  refreshPracticeChrome(name);
}

export function bumpPracticeAnswer(name, correct, progress) {
  const c = practiceCounts[name];
  if (!c) return;
  if (correct) {
    c.correct += 1;
    if (!progress && c.total) c.done = Math.min(c.total, c.done + 1);
  } else {
    c.mistakes += 1;
  }
  if (progress) {
    c.done = progress.done;
    c.total = progress.total;
  }
  refreshPracticeChrome(name);
}

export function setPracticeProgress(name, done, total) {
  const c = practiceCounts[name];
  if (!c) return;
  c.done = done;
  if (total != null) c.total = total;
  refreshPracticeChrome(name);
}

export function setPracticeScore(name, correct, mistakes, done, total) {
  const c = practiceCounts[name];
  if (!c) return;
  c.correct = correct;
  c.mistakes = mistakes;
  c.done = done;
  if (total != null) c.total = total;
  refreshPracticeChrome(name);
}

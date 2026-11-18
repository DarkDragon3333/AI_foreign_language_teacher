import { api } from './api.js?v=18';
import {
  bumpPracticeAnswer,
  endPracticeSession,
  resetPracticeStats,
  setPracticeProgress,
  setPracticeScore,
  startPracticeTimedSession,
  stopPracticeTimer,
} from './practice-ui.js?v=2';

const $ = (id) => document.getElementById(id);
let grammarRun = null;
let grammarCurrent = null;
function grammarApiError(e) {
  const msg = e.message || '';
  if (msg === 'Not Found') {
    return 'Grammar practice is unavailable: rebuild the app container (docker compose up -d --build app).';
  }
  return msg;
}

function setGrammarFeedback(text, tone) {
  const el = $('grammar-status');
  el.textContent = text || '';
  el.hidden = !text;
  el.classList.remove('feedback-good', 'feedback-bad');
  if (tone === 'good') el.classList.add('feedback-good');
  if (tone === 'bad') el.classList.add('feedback-bad');
}

function setGrammarRunActive(active) {
  $('grammar-launch').hidden = active;
  for (const input of document.querySelectorAll('#grammar-types input')) {
    input.disabled = active;
  }
}

function clearGrammarTrail() {
  grammarCurrent = null;
  $('grammar-trail-wrong').textContent = '';
  $('grammar-trail-right').textContent = '';
  $('grammar-trail-wrap').hidden = true;
}

function grammarTrailLine(label, text, lineKind) {
  const row = document.createElement('div');
  row.className = 'grammar-trail-line ' + lineKind;
  const lab = document.createElement('span');
  lab.className = 'grammar-trail-label';
  lab.textContent = label;
  const val = document.createElement('span');
  val.className = 'grammar-trail-value';
  const shown = text.trim() || '—';
  val.textContent = shown;
  val.title = shown;
  row.append(lab, val);
  return row;
}

function appendGrammarTrail(prompt, answer, correct, expected) {
  const trail = correct ? $('grammar-trail-right') : $('grammar-trail-wrong');
  const item = document.createElement('div');
  item.setAttribute('role', 'listitem');
  item.className = 'grammar-trail-item ' + (correct ? 'good' : 'bad');
  const task = document.createElement('p');
  task.className = 'grammar-trail-prompt';
  task.textContent = prompt;
  task.title = prompt;
  item.append(task);
  const yours = answer.trim() || '—';
  if (correct) {
    item.append(grammarTrailLine('You', yours, 'yours'));
  } else {
    const right = (expected ?? '').trim() || '—';
    item.append(
      grammarTrailLine('You', yours, 'yours-wrong'),
      grammarTrailLine('Correct', right, 'expected'),
    );
  }
  trail.append(item);
  $('grammar-trail-wrap').hidden = false;
  item.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

export function stopGrammarSet() {
  grammarRun = null;
  $('grammar-exercise').hidden = true;
  $('grammar-done').hidden = true;
  $('grammar-submit').disabled = true;
  $('grammar-answer').value = '';
  setGrammarFeedback('', null);
  setGrammarRunActive(false);
  clearGrammarTrail();
  endPracticeSession('grammar');
}

export async function openGrammar() {
  grammarRun = null;
  resetPracticeStats('grammar', 6);
  setGrammarFeedback('', null);
  clearGrammarTrail();
  $('grammar-answer').value = '';
  $('grammar-exercise').hidden = true;
  $('grammar-done').hidden = true;
  $('grammar-submit').disabled = true;
  try {
    const types = [];
    for (const box of document.querySelectorAll('#grammar-types input:checked')) {
      types.push(box.value);
    }
    const random = $('grammar-random').checked;
    const start = await api.grammarStart({ types, random });
    grammarRun = start.runId;
    startPracticeTimedSession('grammar', 6);
    setGrammarRunActive(true);
    renderGrammar(start.current);
  } catch (e) {
    setGrammarRunActive(false);
    setGrammarFeedback(grammarApiError(e), 'bad');
  }
}

function renderGrammar(exercise) {
  if (!exercise) return;
  grammarCurrent = exercise;
  $('grammar-submit').disabled = false;
  $('grammar-exercise').hidden = false;
  $('grammar-meta').textContent = `${exercise.index} / ${exercise.total} · ${exercise.type}`;
  setPracticeProgress('grammar', Math.max(0, exercise.index - 1), exercise.total);
  $('grammar-prompt').textContent = exercise.prompt;
  $('grammar-rule').textContent = exercise.rule || '';
  const choices = $('grammar-choices');
  choices.textContent = '';
  const choiceMode = exercise.type === 'choice' && exercise.choices?.length;
  choices.hidden = !choiceMode;
  if (choiceMode) {
  for (const c of exercise.choices) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'rate';
      btn.textContent = c;
      btn.addEventListener('click', () => { $('grammar-answer').value = c; });
      choices.append(btn);
    }
  }
  $('grammar-answer').focus();
}

export async function submitGrammar() {
  if (!grammarRun) return;
  const answer = $('grammar-answer').value;
  const prompt = grammarCurrent?.prompt ?? $('grammar-prompt').textContent;
  try {
    const result = await api.grammarAnswer(grammarRun, answer);
    appendGrammarTrail(prompt, answer, result.correct, result.expected);
    setGrammarFeedback(
      result.correct ? 'Correct' : `Not quite. Expected: ${result.expected}`,
      result.correct ? 'good' : 'bad',
    );
    bumpPracticeAnswer('grammar', result.correct, result.next
      ? { done: result.next.index - 1, total: result.next.total }
      : { done: 6, total: 6 });
    $('grammar-answer').value = '';
    if (result.finished) {
      $('grammar-done').hidden = false;
      $('grammar-exercise').hidden = true;
      setGrammarRunActive(false);
      setGrammarFeedback('Set complete.', 'good');
      stopPracticeTimer();
      setPracticeProgress('grammar', 6, 6);
      return;
    }
    renderGrammar(result.next);
  } catch (e) {
    setGrammarFeedback(grammarApiError(e), 'bad');
  }
}

export function stopActivePractice(tab) {
  if (tab === 'grammar') stopGrammarSet();
}

export function wirePracticeControls() {
  $('grammar-start').addEventListener('click', () => openGrammar());
  $('grammar-submit').addEventListener('click', () => submitGrammar());
  $('grammar-submit').disabled = true;
  $('grammar-answer').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      submitGrammar();
    }
  });
}

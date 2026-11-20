import { api, speak } from './api.js?v=18';
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
let speakingRun = null;
let speakingPhraseIndex = 0;
const SPEAKING_SET_SIZE = 6;
const speakRec = { recorder: null, chunks: [], stream: null, startedAt: 0, timer: null, arming: false };

function resetSpeakingRecordUi() {
  const recordBtn = $('speaking-record');
  if (!recordBtn) return;
  recordBtn.setAttribute('aria-pressed', 'false');
  recordBtn.textContent = 'Record';
}

/** Stops mic without sending audio (End drill, leave screen). */
export function abortSpeakingRecord() {
  clearTimeout(speakRec.timer);
  const recorder = speakRec.recorder;
  speakRec.recorder = null;
  speakRec.chunks = [];
  speakRec.arming = false;
  if (recorder && recorder.state !== 'inactive') {
    recorder.onstop = null;
    try { recorder.stop(); } catch { /* already stopped */ }
  }
  speakRec.stream?.getTracks().forEach((t) => t.stop());
  speakRec.stream = null;
  resetSpeakingRecordUi();
}

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

function setSpeakingFeedback(text, tone) {
  const el = $('speaking-status');
  if (!el) return;
  el.textContent = text || '';
  el.hidden = !text;
  el.classList.remove('feedback-good', 'feedback-bad');
  if (tone === 'good') el.classList.add('feedback-good');
  if (tone === 'bad') el.classList.add('feedback-bad');
}

function setSpeakingRunActive(active) {
  const launch = $('speaking-launch');
  if (launch) launch.hidden = active;
}

function clearSpeakingCard() {
  $('speaking-card').hidden = true;
  $('speaking-phrase').textContent = '';
  $('speaking-heard').textContent = '';
  $('speaking-list').textContent = '';
  setSpeakingFeedback('', null);
}

export function stopSpeakingSet() {
  abortSpeakingRecord();
  speakingRun = null;
  speakingPhraseIndex = 0;
  $('speaking-done').hidden = true;
  clearSpeakingCard();
  setSpeakingRunActive(false);
  endPracticeSession('speaking');
}

function finishSpeakingSet() {
  abortSpeakingRecord();
  speakingRun = null;
  speakingPhraseIndex = 0;
  $('speaking-card').hidden = true;
  $('speaking-done').hidden = false;
  setSpeakingRunActive(false);
  stopPracticeTimer();
  setPracticeProgress('speaking', SPEAKING_SET_SIZE, SPEAKING_SET_SIZE);
}

export function stopActivePractice(tab) {
  if (tab === 'grammar') stopGrammarSet();
  else if (tab === 'speaking') stopSpeakingSet();
}

async function ensureSpeakingRun() {
  if (speakingRun) return true;
  setSpeakingFeedback('Start a set first.', 'bad');
  return false;
}

function renderSpeakingPhrase(phrase, index) {
  $('speaking-card').hidden = false;
  $('speaking-done').hidden = true;
  $('speaking-meta').textContent = `${index} / ${SPEAKING_SET_SIZE} · repeat`;
  setPracticeProgress('speaking', Math.max(0, index - 1), SPEAKING_SET_SIZE);
  $('speaking-phrase').textContent = phrase;
  $('speaking-heard').textContent = '';
  $('speaking-list').textContent = '';
  setSpeakingFeedback('', null);
}

export async function startSpeakingSet() {
  abortSpeakingRecord();
  speakingRun = null;
  speakingPhraseIndex = 0;
  $('speaking-done').hidden = true;
  clearSpeakingCard();
  setSpeakingRunActive(false);
  try {
    const start = await api.speakingStart();
    speakingRun = start.runId;
    speakingPhraseIndex = 1;
    startPracticeTimedSession('speaking', SPEAKING_SET_SIZE);
    setSpeakingRunActive(true);
    renderSpeakingPhrase(start.phrase, speakingPhraseIndex);
    await speakPhrase(start.phrase);
  } catch (e) {
    setSpeakingRunActive(false);
    $('speaking-card').hidden = false;
    $('speaking-phrase').textContent = e.message;
  }
}

async function speakPhrase(text) {
  try {
    const url = await speak(text);
    const audio = new Audio(url);
    await audio.play();
  } catch { /* optional */ }
}

export async function recordSpeaking(blob) {
  if (!speakingRun) {
    setSpeakingFeedback('Start a set first.', 'bad');
    return;
  }
  setSpeakingFeedback('Checking…', null);
  try {
    const heard = await fetch(`/api/practice/speaking/${speakingRun}/speak`, {
      method: 'POST',
      body: (() => {
        const body = new FormData();
        body.append('file', blob, 'speech.webm');
        return body;
      })(),
    });
    const data = await heard.json();
    if (!heard.ok) throw new Error(data.error || 'Recognition failed');
    $('speaking-heard').textContent = data.heard ? `Heard: ${data.heard}` : '';
    const list = $('speaking-list');
    list.textContent = '';
    for (const m of data.mismatches || []) {
      const row = document.createElement('li');
      const label = m.kind === 'missing' ? `Missing: ${m.expected}`
        : m.kind === 'extra' ? `Extra: ${m.heard}`
          : `${m.heard || '?'} → ${m.expected}`;
      row.textContent = label;
      const listen = document.createElement('button');
      listen.type = 'button';
      listen.className = 'text-btn';
      listen.textContent = 'Listen';
      const sample = m.expected || m.heard;
      if (sample) listen.addEventListener('click', () => speakPhrase(sample));
      row.append(' ', listen);
      list.append(row);
    }
    if (!(data.mismatches || []).length) {
      bumpPracticeAnswer('speaking', true, { done: speakingPhraseIndex, total: SPEAKING_SET_SIZE });
      if (speakingPhraseIndex >= SPEAKING_SET_SIZE) {
        finishSpeakingSet();
        return;
      }
      setSpeakingFeedback('Well done!', 'good');
      const next = await api.speakingNext(speakingRun);
      speakingPhraseIndex += 1;
      renderSpeakingPhrase(next.phrase, speakingPhraseIndex);
      await speakPhrase(next.phrase);
    } else {
      setSpeakingFeedback('Try again.', 'bad');
      bumpPracticeAnswer('speaking', false);
    }
  } catch (e) {
    setSpeakingFeedback(e.message, 'bad');
  }
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
  $('speaking-start').addEventListener('click', () => startSpeakingSet());
  $('speaking-listen').addEventListener('click', () => {
    const phrase = $('speaking-phrase').textContent;
    if (phrase) speakPhrase(phrase);
  });
  const recordBtn = $('speaking-record');

  function setSpeakingRecording(on) {
    recordBtn.setAttribute('aria-pressed', String(on));
    recordBtn.textContent = on ? 'Recording…' : 'Record';
  }

  async function startSpeakingRecord() {
    if (speakRec.recorder || speakRec.arming) return;
    if (!(await ensureSpeakingRun())) return;
    speakRec.arming = true;
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
    } catch (e) {
      speakRec.arming = false;
      setSpeakingFeedback(e.message || 'Microphone is blocked', 'bad');
      return;
    }
    const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : '';
    const recorder = new MediaRecorder(stream, mime ? { mimeType: mime } : undefined);
    speakRec.chunks = [];
    speakRec.stream = stream;
    speakRec.recorder = recorder;
    speakRec.startedAt = Date.now();
    speakRec.arming = false;
    recorder.ondataavailable = (e) => { if (e.data.size) speakRec.chunks.push(e.data); };
    recorder.start(250);
    setSpeakingRecording(true);
    setSpeakingFeedback('Listening… release to send, or tap again to stop', null);
    speakRec.timer = setTimeout(() => { if (speakRec.recorder) stopSpeakingRecord(); }, 30000);
  }

  function stopSpeakingRecord() {
    const recorder = speakRec.recorder;
    if (!recorder || recorder.state === 'inactive') return;
    clearTimeout(speakRec.timer);
    const startedAt = speakRec.startedAt;
    speakRec.recorder = null;
    recorder.onstop = async () => {
      speakRec.stream?.getTracks().forEach((t) => t.stop());
      speakRec.stream = null;
      setSpeakingRecording(false);
      const blob = new Blob(speakRec.chunks, { type: recorder.mimeType || 'audio/webm' });
      speakRec.chunks = [];
      if (Date.now() - startedAt < 400 || blob.size < 800) {
        setSpeakingFeedback('That was too short. Hold a little longer or tap twice.', 'bad');
        return;
      }
      await recordSpeaking(blob);
    };
    recorder.stop();
  }

  recordBtn.addEventListener('pointerdown', (event) => {
    if (event.button !== 0) return;
    event.preventDefault();
    if (speakRec.recorder) {
      stopSpeakingRecord();
      return;
    }
    recordBtn.setPointerCapture(event.pointerId);
    startSpeakingRecord();
  });
  recordBtn.addEventListener('pointerup', () => {
    if (speakRec.recorder && Date.now() - speakRec.startedAt > 400) stopSpeakingRecord();
  });
  recordBtn.addEventListener('pointercancel', () => {
    if (speakRec.recorder) stopSpeakingRecord();
  });
  recordBtn.addEventListener('contextmenu', (event) => event.preventDefault());
}
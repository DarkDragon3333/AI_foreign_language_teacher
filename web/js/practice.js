import { api, speak } from './api.js?v=19';
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
let grammarAnswered = 0;
let speakingRun = null;
let speakingPhraseIndex = 0;
let speakingDonePhrases = 0;
let listeningRun = null;
let listeningChecked = false;
let listeningQuestions = [];
let listeningPassageText = '';
const SPEAKING_SET_SIZE = 6;

function markToday(activity) {
  api.completeSchedule(activity)
    .then(() => window.dispatchEvent(new CustomEvent('schedule-refresh')))
    .catch(() => { /* today's plan may be a different activity */ });
}
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
  const answered = grammarAnswered;
  grammarAnswered = 0;
  grammarRun = null;
  $('grammar-exercise').hidden = true;
  $('grammar-done').hidden = true;
  $('grammar-submit').disabled = true;
  $('grammar-answer').value = '';
  setGrammarFeedback('', null);
  setGrammarRunActive(false);
  clearGrammarTrail();
  endPracticeSession('grammar');
  if (answered > 0) markToday('grammar');
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
    grammarAnswered = 0;
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
    grammarAnswered += 1;
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
      grammarAnswered = 0;
      markToday('grammar');
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
  const donePhrases = speakingDonePhrases;
  speakingDonePhrases = 0;
  abortSpeakingRecord();
  speakingRun = null;
  speakingPhraseIndex = 0;
  $('speaking-done').hidden = true;
  clearSpeakingCard();
  setSpeakingRunActive(false);
  endPracticeSession('speaking');
  if (donePhrases > 0) markToday('speaking');
}

function finishSpeakingSet() {
  speakingDonePhrases = 0;
  abortSpeakingRecord();
  speakingRun = null;
  speakingPhraseIndex = 0;
  $('speaking-card').hidden = true;
  $('speaking-done').hidden = false;
  setSpeakingRunActive(false);
  stopPracticeTimer();
  setPracticeProgress('speaking', SPEAKING_SET_SIZE, SPEAKING_SET_SIZE);
  markToday('speaking');
}

export function stopActivePractice(tab) {
  if (tab === 'grammar') stopGrammarSet();
  else if (tab === 'speaking') stopSpeakingSet();
  else if (tab === 'listening') stopListeningSet();
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
  speakingDonePhrases = 0;
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
      speakingDonePhrases += 1;
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

function setListeningFeedback(text, tone) {
  const el = $('listening-result');
  if (!el) return;
  el.textContent = text || '';
  el.hidden = !text;
  el.classList.remove('feedback-good', 'feedback-bad');
  if (tone === 'good') el.classList.add('feedback-good');
  if (tone === 'bad') el.classList.add('feedback-bad');
}

function setListeningRunActive(active) {
  const launch = $('listening-launch');
  if (launch) launch.hidden = active;
  const custom = $('listening-custom');
  if (custom) custom.disabled = active;
}

function clearListeningTrail() {
  $('listening-trail-wrong').textContent = '';
  $('listening-trail-right').textContent = '';
  $('listening-trail-wrap').hidden = true;
}

function formatListeningAcceptable(acceptable) {
  if (!acceptable?.length) return '—';
  return acceptable.join(' · ');
}

function appendListeningTrail(prompt, answer, correct, acceptable) {
  const trail = correct ? $('listening-trail-right') : $('listening-trail-wrong');
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
    item.append(
      grammarTrailLine('You', yours, 'yours-wrong'),
      grammarTrailLine('Acceptable', formatListeningAcceptable(acceptable), 'expected'),
    );
  }
  trail.append(item);
  $('listening-trail-wrap').hidden = false;
}

function setListeningQuestionFeedback(block, ok, acceptable) {
  if (!block) return;
  let el = block.querySelector('.listening-q-feedback');
  if (!el) {
    el = document.createElement('p');
    el.className = 'listening-q-feedback grammar-feedback';
    el.setAttribute('role', 'status');
    block.append(el);
  }
  el.hidden = false;
  el.classList.remove('feedback-good', 'feedback-bad');
  if (ok) {
    el.textContent = 'Correct';
    el.classList.add('feedback-good');
  } else {
    el.textContent = `Not quite. Acceptable: ${formatListeningAcceptable(acceptable)}`;
    el.classList.add('feedback-bad');
  }
}

function clearListeningCard() {
  listeningQuestions = [];
  listeningPassageText = '';
  $('listening-card').hidden = true;
  $('listening-meta').textContent = '';
  $('listening-passage').textContent = '';
  $('listening-passage').hidden = true;
  $('listening-warning').hidden = true;
  $('listening-warning').textContent = '';
  $('listening-questions').textContent = '';
  setListeningFeedback('', null);
  const submit = $('listening-submit');
  if (submit) {
    submit.disabled = true;
    submit.textContent = 'Check answers';
  }
}

export function stopListeningSet() {
  const checked = listeningChecked;
  listeningChecked = false;
  listeningRun = null;
  const done = $('listening-done');
  done.hidden = true;
  done.textContent = 'Finished. Start another set if you like.';
  clearListeningCard();
  clearListeningTrail();
  setListeningRunActive(false);
  endPracticeSession('listening');
  if (checked) markToday('listening');
}

function completeListeningSet(correct, total) {
  listeningChecked = false;
  listeningRun = null;
  $('listening-card').hidden = true;
  const done = $('listening-done');
  done.hidden = false;
  done.textContent = correct === total
    ? `All ${total} correct. Start another set if you like.`
    : `${correct} / ${total} correct. Start another set if you like.`;
  setListeningRunActive(false);
  stopPracticeTimer();
  setPracticeProgress('listening', total, total);
  markToday('listening');
}

async function beginListening(start) {
  listeningChecked = false;
  listeningRun = start.runId;
  listeningPassageText = start.passage || '';
  const total = start.questions?.length || 3;
  resetPracticeStats('listening', total);
  startPracticeTimedSession('listening', total);
  $('listening-done').hidden = true;
  clearListeningTrail();
  setListeningRunActive(true);
  $('listening-card').hidden = false;
  $('listening-warning').hidden = !start.warning;
  $('listening-warning').textContent = start.warning || '';
  $('listening-passage').textContent = listeningPassageText;
  $('listening-passage').hidden = true;
  renderListeningQuestions(start.questions);
  setListeningFeedback('', null);
  await speakPhrase(listeningPassageText);
}

export async function openListeningGenerated() {
  listeningRun = null;
  $('listening-done').hidden = true;
  try {
    const start = await api.listeningStart();
    await beginListening(start);
  } catch (e) {
    setListeningRunActive(false);
    $('listening-card').hidden = false;
    $('listening-passage').hidden = false;
    $('listening-passage').textContent = e.message;
    setListeningFeedback('', null);
  }
}

export async function openListeningCustom() {
  const text = $('listening-custom').value.trim();
  if (!text) return;
  listeningRun = null;
  $('listening-done').hidden = true;
  try {
    const start = await api.listeningCustom(text);
    await beginListening(start);
  } catch (e) {
    setListeningRunActive(false);
    $('listening-card').hidden = false;
    $('listening-passage').hidden = false;
    $('listening-passage').textContent = e.message;
    setListeningFeedback('', null);
  }
}

function renderListeningQuestions(questions) {
  listeningQuestions = questions || [];
  const wrap = $('listening-questions');
  wrap.textContent = '';
  const total = listeningQuestions.length || 3;
  $('listening-meta').textContent = `${total} question${total === 1 ? '' : 's'} · short answer (a word or phrase)`;
  setPracticeProgress('listening', 0, total);
  $('listening-submit').disabled = !listeningQuestions.length;
  for (let i = 0; i < listeningQuestions.length; i++) {
    const q = listeningQuestions[i];
    const block = document.createElement('div');
    block.className = 'listening-q';
    const label = document.createElement('label');
    const span = document.createElement('span');
    span.className = 'listening-q-label';
    span.textContent = `${i + 1}. ${q.question}`;
    const input = document.createElement('input');
    input.type = 'text';
    input.maxLength = 500;
    input.dataset.index = String(i);
    input.className = 'grammar-answer-input listening-answer';
    input.placeholder = 'Short answer';
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        submitListening();
      }
    });
    label.append(span, input);
    block.append(label);
    wrap.append(block);
  }
}

export async function submitListening() {
  if (!listeningRun) return;
  const inputs = [...document.querySelectorAll('.listening-answer')];
  const answers = inputs.map((n) => n.value);
  try {
    const result = await api.listeningSubmit(listeningRun, answers);
    listeningChecked = true;
    clearListeningTrail();
    let allCorrect = true;
    for (let i = 0; i < result.results.length; i++) {
      const ok = result.results[i];
      if (!ok) allCorrect = false;
      const q = listeningQuestions[i];
      const prompt = q?.question ?? `Question ${i + 1}`;
      appendListeningTrail(prompt, answers[i] ?? '', ok, q?.acceptable);
      const block = inputs[i]?.closest('.listening-q');
      setListeningQuestionFeedback(block, ok, q?.acceptable);
      const input = inputs[i];
      if (!input) continue;
      input.classList.remove('answer-good', 'answer-wrong');
      input.classList.add(ok ? 'answer-good' : 'answer-wrong');
      input.disabled = ok;
    }
    const mistakes = result.total - result.correct;
    setPracticeScore('listening', result.correct, mistakes, result.correct, result.total);
    const submit = $('listening-submit');
    if (allCorrect) {
      setListeningFeedback('All correct.', 'good');
      if (submit) submit.disabled = true;
      completeListeningSet(result.correct, result.total);
      return;
    }
    setListeningFeedback('Fix answers marked below, then check again.', 'bad');
    if (submit) {
      submit.disabled = false;
      submit.textContent = 'Check again';
    }
    const next = inputs.find((n) => !n.disabled);
    next?.focus();
  } catch (e) {
    setListeningFeedback(e.message, 'bad');
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
  $('listening-generated').addEventListener('click', () => openListeningGenerated());
  $('listening-custom-go').addEventListener('click', () => openListeningCustom());
  $('listening-replay').addEventListener('click', () => {
    if (listeningPassageText) speakPhrase(listeningPassageText);
  });
  $('listening-submit').addEventListener('click', () => submitListening());

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
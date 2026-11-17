// Отрисовка реплик. Весь текст вставляется через textContent — HTML из ответа модели не исполняется.

import { speak } from './api.js?v=14';

let current = null;

export function stopSpeaking() {
  if (!current) return;
  current.audio.pause();
  URL.revokeObjectURL(current.url);
  current.button.textContent = 'Listen';
  current.button.setAttribute('aria-pressed', 'false');
  current = null;
}

function play(button) {
  if (button.dataset.loading === '1') return Promise.resolve();
  if (current && current.button === button) {
    stopSpeaking();
    return Promise.resolve();
  }
  stopSpeaking();
  button.dataset.loading = '1';
  button.disabled = true;
  button.textContent = '…';
  return speak(button.dataset.text).then((url) => {
    const audio = new Audio(url);
    current = { button, audio, url };
    button.textContent = 'Stop';
    button.setAttribute('aria-pressed', 'true');
    audio.addEventListener('ended', () => {
      if (current && current.audio === audio) stopSpeaking();
    });
    return audio.play();
  }).catch((error) => {
    if (current && current.button === button) stopSpeaking();
    button.title = error.message;
    throw error;
  }).finally(() => {
    button.dataset.loading = '0';
    button.disabled = false;
    if (!current || current.button !== button) {
      button.textContent = 'Listen';
      button.setAttribute('aria-pressed', 'false');
    }
  });
}

function listenButton(text) {
  const button = el('button', 'listen', 'Listen');
  button.type = 'button';
  button.dataset.text = text;
  button.setAttribute('aria-pressed', 'false');
  button.addEventListener('click', () => {
    play(button).catch((error) => {
      window.dispatchEvent(new CustomEvent('tutor-speak-error', { detail: error.message }));
    });
  });
  return button;
}

/** Озвучить уже показанную реплику. Если плеер браузера запретит автозапуск, кнопка останется. */
export function speakMessage(node) {
  const button = node.querySelector('.listen');
  if (!button || button.getAttribute('aria-pressed') === 'true') return Promise.resolve();
  return play(button);
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}

export function userMessage(text) {
  const wrap = el('div', 'msg user');
  wrap.append(el('div', 'bubble', text));
  return wrap;
}

/** Пустой пузырь учителя, который наполняется по мере стриминга. */
export function teacherPlaceholder() {
  const wrap = el('div', 'msg teacher');
  const bubble = el('div', 'bubble cursor');
  wrap.append(bubble);
  return {
    node: wrap,
    append(text) { bubble.textContent += text; },
    finish(reply, fallbackText) { fillTeacher(wrap, bubble, reply, fallbackText); },
    remove() { wrap.remove(); },
  };
}

export function teacherMessage(reply, fallbackText) {
  const wrap = el('div', 'msg teacher');
  const bubble = el('div', 'bubble');
  wrap.append(bubble);
  fillTeacher(wrap, bubble, reply, fallbackText);
  return wrap;
}

/**
 * Фраза ученика, в которой помечены только ошибочные места:
 * неверное слово зачёркнуто красным, сразу за ним — верное зелёным. Остальной текст не тронут.
 */
function fixLine(correction) {
  const line = el('div', 'fix-line');
  const original = correction.original || '';
  if (!correction.corrected || correction.corrected === original) {
    // исправленной фразы нет — подчёркиваем места ошибок в исходной
    markSpans(line, original, (correction.errors || []).map((e) => e.span).filter(Boolean));
    return line;
  }
  const a = original.split(/\s+/).filter(Boolean);
  const b = correction.corrected.split(/\s+/).filter(Boolean);
  const same = (x, y) => x.replace(/[.,!?;:]+$/, '') === y.replace(/[.,!?;:]+$/, '');
  // наибольшая общая подпоследовательность слов
  const lcs = Array.from({ length: a.length + 1 }, () => new Array(b.length + 1).fill(0));
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lcs[i][j] = same(a[i], b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const push = (cls, words) => {
    if (!words.length) return;
    if (line.childNodes.length) line.append(' ');
    line.append(cls ? el('span', cls, words.join(' ')) : words.join(' '));
  };
  let i = 0, j = 0, keep = [], del = [], ins = [];
  const flush = () => { push(null, keep); push('diff-del', del); push('diff-ins', ins); keep = []; del = []; ins = []; };
  while (i < a.length || j < b.length) {
    if (i < a.length && j < b.length && same(a[i], b[j])) {
      if (del.length || ins.length) flush();
      keep.push(a[i]); i++; j++;
    } else {
      if (keep.length) flush();
      if (j < b.length && (i === a.length || lcs[i][j + 1] >= lcs[i + 1][j])) { ins.push(b[j]); j++; }
      else { del.push(a[i]); i++; }
    }
  }
  flush();
  return line;
}

function markSpans(line, text, spans) {
  let rest = text;
  for (const span of spans) {
    const at = rest.toLowerCase().indexOf(span.toLowerCase());
    if (at < 0) continue;
    line.append(rest.slice(0, at), el('span', 'diff-mark', rest.slice(at, at + span.length)));
    rest = rest.slice(at + span.length);
  }
  line.append(rest);
}

function fillTeacher(wrap, bubble, reply, fallbackText) {
  bubble.classList.remove('cursor');
  bubble.textContent = '';
  wrap.querySelectorAll('.extra, .translate, .listen').forEach((n) => n.remove());

  if (!reply) {
    bubble.textContent = fallbackText || '';
    if (bubble.textContent.trim()) wrap.append(listenButton(bubble.textContent.trim()));
    return;
  }
  // одна реплика учителя обычным текстом: ответ и следующий вопрос подряд
  bubble.textContent = reply.next_prompt ? reply.reply_en + ' ' + reply.next_prompt : reply.reply_en;
  if (bubble.textContent.trim()) wrap.append(listenButton(bubble.textContent.trim()));

  const c = reply.correction;
  if (c) {
    const box = el('div', 'extra');
    box.append(el('div', 'extra-title', 'Correction'));
    box.append(fixLine(c));
    if (c.errors && c.errors.length) {
      const list = el('ul', 'errors');
      for (const e of c.errors) {
        const item = el('li');
        if (e.span) item.append(el('span', 'err-span', e.span), ' ');
        if (e.rule) item.append(el('span', 'err-rule', e.rule), e.hint ? ' — ' : '');
        if (e.hint) item.append(e.hint);
        list.append(item);
      }
      box.append(list);
    }
    wrap.append(box);
  }

  const x = reply.explanation;
  if (x && (x.simple || x.example_ok || x.example_bad)) {
    const box = el('div', 'extra');
    box.append(el('div', 'extra-title', x.title ? 'Rule · ' + x.title : 'Rule'));
    if (x.simple) box.append(el('div', null, x.simple));
    if (x.example_ok) box.append(el('div', 'example ok', '✓ ' + x.example_ok));
    if (x.example_bad) box.append(el('div', 'example bad', '✗ ' + x.example_bad));
    if (x.trap) box.append(el('div', 'ru', x.trap));
    wrap.append(box);
  }

  // русское пояснение — только по кнопке
  if (reply.reply_ru) {
    const box = el('div', 'extra ru');
    box.append(el('div', 'extra-title', 'In Russian'), el('div', null, reply.reply_ru));
    box.hidden = true;
    const toggle = el('button', 'translate', 'Translate');
    toggle.type = 'button';
    toggle.setAttribute('aria-expanded', 'false');
    toggle.addEventListener('click', () => {
      box.hidden = !box.hidden;
      toggle.textContent = box.hidden ? 'Translate' : 'Hide translation';
      toggle.setAttribute('aria-expanded', String(!box.hidden));
    });
    wrap.append(toggle, box);
  }
}

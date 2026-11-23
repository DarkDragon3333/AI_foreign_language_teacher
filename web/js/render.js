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

const LADDER_LETTERS = ['A', 'B', 'C', 'D'];

export function placementLadderCard({ id, stem, choices, stepMeta, onChoose }) {
  const card = el('article', 'placement-task placement-ladder review-card grammar-card is-active');
  card.append(el('p', 'muted placement-task-meta', stepMeta || ''));
  card.append(el('p', 'review-prompt placement-task-prompt', stem || ''));
  const wrap = el('div', 'grammar-choices placement-choices');
  (choices || []).forEach((text, index) => {
    const letter = LADDER_LETTERS[index] || String(index + 1);
    const btn = el('button', 'placement-choice grammar-choice-chip', `${letter}) ${text}`);
    btn.type = 'button';
    btn.addEventListener('click', () => {
      if (card.classList.contains('is-locked')) return;
      card.classList.add('is-locked');
      card.classList.remove('is-active');
      wrap.querySelectorAll('button').forEach((node) => { node.disabled = true; });
      onChoose(id, letter);
    });
    wrap.append(btn);
  });
  card.append(wrap);
  return card;
}

export function placementOfferCard({ level, note, onAccept, onRefine }) {
  const card = el('article', 'placement-task placement-offer review-card grammar-card is-active');
  card.append(el('p', 'muted placement-task-meta', 'Preliminary result'));
  card.append(el('p', 'placement-offer-level', level || ''));
  card.append(el('p', 'placement-offer-note', note || ''));
  const row = el('div', 'placement-offer-actions');
  const keep = el('button', 'solid', 'Оставить этот уровень');
  const refine = el('button', 'lime', 'Уточнить');
  keep.type = 'button';
  refine.type = 'button';
  const lock = () => {
    keep.disabled = true;
    refine.disabled = true;
    card.classList.add('is-locked');
    card.classList.remove('is-active');
  };
  keep.addEventListener('click', () => { lock(); onAccept(); });
  refine.addEventListener('click', () => { lock(); onRefine(); });
  row.append(keep, refine);
  card.append(row);
  return card;
}

export function userMessage(text) {
  const wrap = el('div', 'msg user');
  wrap.append(el('div', 'bubble', text));
  return wrap;
}

/** @typedef {Object} PlacementRenderOpts
 *  @property {boolean} [placement]
 *  @property {string} [stepMeta]
 *  @property {boolean} [active]
 *  @property {string} [lockedAnswer]
 *  @property {(text: string, card: HTMLElement) => void} [onSubmit]
 *  @property {(card: HTMLElement) => void} [onSpeak]
 */

/** Пустой пузырь учителя. В placement текст не дописывается по кускам: ответ рисуется целиком в finish. */
export function teacherPlaceholder(opts = {}) {
  const wrap = el('div', 'msg teacher');
  const bubble = el('div', 'bubble cursor');
  wrap.append(bubble);
  return {
    node: wrap,
    earlyCard: null,
    append(text) { bubble.textContent += text; },
    finish(reply, fallbackText, optsOverride = {}) {
      fillTeacher(wrap, bubble, reply, fallbackText, { ...opts, ...optsOverride });
    },
    remove() { wrap.remove(); },
  };
}

export function teacherMessage(reply, fallbackText, opts = {}) {
  const wrap = el('div', 'msg teacher');
  const bubble = el('div', 'bubble');
  wrap.append(bubble);
  fillTeacher(wrap, bubble, reply, fallbackText, opts);
  return wrap;
}

const PLACEMENT_GOAL_MINUTES = [5, 10, 15, 20, 25, 30, 45, 60];
const DOW_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export function lockPlacementCard(card, answer) {
  if (!card) return;
  card.classList.remove('is-active');
  card.classList.add('is-locked');
  const input = card.querySelector('.grammar-answer-input');
  if (input) {
    input.value = answer || '';
    input.readOnly = true;
    input.disabled = true;
    fitPlacementAnswerInput(input);
    requestAnimationFrame(() => fitPlacementAnswerInput(input));
  }
  const send = card.querySelector('.placement-send');
  const mic = card.querySelector('.placement-mic');
  if (send) send.disabled = true;
  if (mic) mic.disabled = true;
}

export function lockPlacementScheduleCard(card, summary) {
  if (!card) return;
  card.classList.remove('is-active');
  card.classList.add('is-locked');
  const interactive = card.querySelector('.placement-schedule-interactive');
  const summaryEl = card.querySelector('.placement-schedule-summary');
  if (interactive) interactive.hidden = true;
  if (summaryEl) {
    summaryEl.textContent = summary || '';
    summaryEl.hidden = !summary;
  }
  const send = card.querySelector('.placement-send');
  if (send) send.disabled = true;
}

function shouldSplitTaskStem(stepMeta, stem) {
  if (/reading/i.test(stepMeta || '') && !/vocabulary/i.test(stepMeta || '')) return false;
  const s = (stem || '').trim();
  if (/vocabulary/i.test(stepMeta || '')) return true;
  if (/complete (?:this |the )?sentence/i.test(s)) return true;
  if (/^choose the (?:best word|correct option)/i.test(s) && s.includes(':')) return true;
  return false;
}

function unwrapTaskQuotes(text) {
  return (text || '').trim().replace(/^['"“”]+|['"“”]+$/g, '').trim();
}

const VOCAB_BLANK = /____+|_{3,}/;

function splitVocabularyStem(stem) {
  const s = (stem || '').trim();
  if (!s) return null;
  const colon = s.indexOf(':');
  if (colon > 0) {
    return {
      instruction: s.slice(0, colon).trim(),
      task: s.slice(colon + 1).trim(),
    };
  }
  const blankAt = s.search(VOCAB_BLANK);
  if (blankAt > 0) {
    let taskStart = blankAt;
    const before = s.slice(0, blankAt);
    const dot = before.lastIndexOf('. ');
    if (dot >= 0) taskStart = dot + 2;
    else {
      const cap = before.search(/\s[A-Z]/);
      if (cap > 0) taskStart = cap + 1;
    }
    if (taskStart > 0 && taskStart < s.length) {
      return { instruction: s.slice(0, taskStart).trim(), task: s.slice(taskStart).trim() };
    }
  }
  return null;
}

function mountPlacementPromptRegion(card, prompt, readingPassage, stepMeta, pending) {
  let region = card.querySelector('.placement-prompt-region');
  if (!region) {
    region = el('div', 'placement-prompt-region');
    const anchor = card.querySelector('.placement-compose, .placement-schedule-form');
    card.insertBefore(region, anchor || null);
  }
  region.replaceChildren();

  const parts = parsePlacementPrompt(prompt, readingPassage);
  const passageText = parts.passage || (readingPassage || '').trim();
  if (passageText) {
    region.append(el('p', 'placement-reading', passageText));
  }

  const vocab = shouldSplitTaskStem(stepMeta, parts.stem)
    ? splitVocabularyStem(parts.stem) : null;
  if (vocab?.instruction) {
    region.append(el('p', 'placement-vocab-instruction', vocab.instruction));
  }
  const stemText = vocab?.task ? unwrapTaskQuotes(vocab.task) : parts.stem;
  if (stemText) {
    const cls = vocab ? 'review-prompt placement-vocab-task' : 'review-prompt placement-task-prompt';
    region.append(el('p', cls, stemText));
  } else if (pending) {
    const pendingEl = el('p', 'review-prompt placement-task-prompt is-pending', 'Writing the question…');
    region.append(pendingEl);
  }

  if (parts.choices.length) {
    const choicesWrap = el('div', 'grammar-choices placement-choices');
    populateChoiceChips(choicesWrap, parts.choices);
    region.append(choicesWrap);
  }
  return parts.choices.length > 0;
}

export function patchPlacementTaskCard(card, prompt, opts = {}) {
  if (!card) return;
  const {
    stepMeta = '', active = false, lockedAnswer = null,
    onSubmit, onSpeak, readingPassage = '', pending = false,
  } = opts;
  const meta = card.querySelector('.placement-task-meta');
  if (meta && stepMeta) meta.textContent = stepMeta;
  card.classList.toggle('is-active', !!active);
  card.classList.toggle('is-locked', lockedAnswer != null);
  const hasChoices = mountPlacementPromptRegion(card, prompt, readingPassage, stepMeta, pending);
  const send = card.querySelector('.placement-send');
  if (send) send.textContent = hasChoices ? 'Check' : 'Send';
  if (prompt?.trim()) card.dataset.streamPrompt = prompt.trim();
}

function createPlacementTaskCard(prompt, opts = {}) {
  const {
    stepMeta = '', active = false, lockedAnswer = null,
    onSubmit, onSpeak, scheduleWidget = false, scheduleDefaults = {},
    onScheduleSubmit, readingPassage = '', pending = false,
  } = opts;
  const card = el('article', 'placement-task review-card grammar-card');
  if (active) card.classList.add('is-active');

  const meta = el('p', 'muted placement-task-meta', stepMeta);
  card.append(meta);

  const hasChoices = mountPlacementPromptRegion(card, prompt, readingPassage, stepMeta, pending);

  if (scheduleWidget) {
    mountPlacementSchedule(card, { active, lockedAnswer, scheduleDefaults, onScheduleSubmit });
  } else {
    const compose = el('div', 'grammar-compose placement-compose');
    const input = el('textarea', 'grammar-answer-input placement-answer-input');
    input.rows = 2;
    input.maxLength = 2000;
    input.placeholder = 'Your answer';
    if (!active || lockedAnswer != null) {
      input.readOnly = true;
      input.disabled = true;
    }

    const send = el('button', 'solid placement-send', hasChoices ? 'Check' : 'Send');
    send.type = 'button';
    if (!active || lockedAnswer != null) send.disabled = true;

    const mic = el('button', 'placement-mic', '🎤');
    mic.type = 'button';
    mic.title = 'Speak your answer';
    mic.setAttribute('aria-label', 'Speak your answer');
    if (!active || lockedAnswer != null) mic.disabled = true;

    compose.append(input, mic, send);
    card.append(compose);

    const fitInput = () => fitPlacementAnswerInput(input);

    if (active && lockedAnswer == null && typeof onSubmit === 'function') {
      const submit = () => {
        if (card.classList.contains('is-locked') || !input.value.trim()) return;
        onSubmit(input.value.trim(), card);
      };
      send.addEventListener('click', submit);
      input.addEventListener('input', fitInput);
      input.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
          event.preventDefault();
          submit();
        }
      });
      fitInput();
    }
    if (active && lockedAnswer == null && typeof onSpeak === 'function') {
      mic.addEventListener('click', () => onSpeak(card));
    }

    if (!card.dataset.placementChoicesWired) {
      card.dataset.placementChoicesWired = '1';
      card.addEventListener('click', (event) => {
        const chip = event.target.closest('.placement-choice');
        if (!chip || card.classList.contains('is-locked')) return;
        const field = card.querySelector('.placement-answer-input');
        if (!field || field.disabled) return;
        const val = chip.dataset.choiceValue || chip.textContent;
        field.value = val;
        field.dispatchEvent(new Event('input', { bubbles: true }));
        field.focus();
      });
    }

    if (lockedAnswer != null) {
      lockPlacementCard(card, lockedAnswer);
    }
  }

  return card;
}

const BRAND_PICKER_DOW = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

function placementInputMaxHeight(el) {
  const style = getComputedStyle(el);
  const lineHeight = parseFloat(style.lineHeight);
  const lh = Number.isFinite(lineHeight) && lineHeight > 0 ? lineHeight : 22;
  const padTop = parseFloat(style.paddingTop) || 0;
  const padBottom = parseFloat(style.paddingBottom) || 0;
  const borderTop = parseFloat(style.borderTopWidth) || 0;
  const borderBottom = parseFloat(style.borderBottomWidth) || 0;
  return lh * 15 + padTop + padBottom + borderTop + borderBottom;
}

export function fitPlacementAnswerInput(el) {
  if (!el) return;
  el.style.height = 'auto';
  const maxH = placementInputMaxHeight(el);
  const needed = el.scrollHeight;
  const h = Math.min(needed, maxH);
  el.style.height = `${h}px`;
  el.style.overflowY = needed > maxH ? 'auto' : 'hidden';
}

function parseIsoLocal(iso) {
  if (!iso) return null;
  const parts = iso.split('-').map(Number);
  if (parts.length !== 3 || parts.some((n) => Number.isNaN(n))) return null;
  return new Date(parts[0], parts[1] - 1, parts[2]);
}

function formatPickerLabel(iso) {
  const d = parseIsoLocal(iso);
  if (!d) return 'Pick a date';
  return d.toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' });
}

/** Кастомный выбор даты в фирменной палитре (поповер). */
export function createBrandDatePicker({ value = '', min = '', disabled = false, onChange } = {}) {
  const root = el('div', 'brand-date-picker');
  const trigger = el('button', 'brand-date-trigger', formatPickerLabel(value));
  trigger.type = 'button';
  trigger.setAttribute('aria-haspopup', 'dialog');
  trigger.setAttribute('aria-expanded', 'false');
  const pop = el('div', 'brand-date-popover');
  pop.setAttribute('role', 'dialog');
  pop.setAttribute('aria-label', 'Choose date');
  pop.hidden = true;

  const head = el('div', 'brand-date-head');
  const prev = el('button', 'brand-date-nav', '‹');
  const next = el('button', 'brand-date-nav', '›');
  const title = el('p', 'brand-date-title', '');
  prev.type = 'button';
  next.type = 'button';
  head.append(prev, title, next);

  const dowRow = el('div', 'brand-date-dow-row');
  for (const name of BRAND_PICKER_DOW) {
    dowRow.append(el('span', 'brand-date-dow', name));
  }
  const grid = el('div', 'brand-date-grid');

  const actions = el('div', 'brand-date-actions');
  const todayBtn = el('button', 'brand-date-quick', 'Today');
  const clearBtn = el('button', 'brand-date-quick', 'Clear');
  todayBtn.type = 'button';
  clearBtn.type = 'button';
  actions.append(todayBtn, clearBtn);

  pop.append(head, dowRow, grid, actions);
  root.append(trigger);
  pop.hidden = true;

  const POP_EST_HEIGHT = 300;
  let selected = value || '';
  let minIso = min || '';
  let isDisabled = disabled;
  let viewportBound = false;
  const base = parseIsoLocal(selected) || new Date();
  let viewYear = base.getFullYear();
  let viewMonth = base.getMonth();

  function portalParent() {
    const dialog = trigger.closest('dialog');
    if (dialog && dialog.open) return dialog;
    return document.body;
  }

  function sameIso(y, m, d) {
    return isoLocalDate(new Date(y, m, d));
  }

  function isBeforeMin(y, m, d) {
    if (!minIso) return false;
    const iso = sameIso(y, m, d);
    return iso < minIso;
  }

  function setSelected(iso) {
    selected = iso || '';
    trigger.textContent = formatPickerLabel(selected);
    if (typeof onChange === 'function') onChange(selected);
    paintGrid();
  }

  function positionPop() {
    const parent = portalParent();
    if (pop.parentElement !== parent) parent.appendChild(pop);
    const rect = trigger.getBoundingClientRect();
    pop.style.position = 'fixed';
    pop.style.zIndex = '9999';
    pop.style.bottom = 'auto';
    pop.style.minWidth = `${Math.max(rect.width, 296)}px`;
    const popH = pop.offsetHeight || POP_EST_HEIGHT;
    const popW = pop.offsetWidth;
    const gap = 6;
    const spaceBelow = window.innerHeight - rect.bottom;
    let top = spaceBelow < popH + gap && rect.top > popH + gap
      ? rect.top - popH - gap
      : rect.bottom + gap;
    top = Math.max(8, Math.min(top, window.innerHeight - popH - 8));
    let left = rect.left;
    const maxLeft = window.innerWidth - popW - 8;
    if (left > maxLeft) left = Math.max(8, maxLeft);
    if (left < 8) left = 8;
    pop.style.left = `${left}px`;
    pop.style.top = `${top}px`;
    pop.dataset.opens = top < rect.top ? 'above' : 'below';
  }

  function onViewportChange() {
    if (pop.hidden) return;
    positionPop();
  }

  function bindViewport() {
    if (viewportBound) return;
    viewportBound = true;
    window.addEventListener('scroll', onViewportChange, true);
    window.addEventListener('resize', onViewportChange);
  }

  function unbindViewport() {
    if (!viewportBound) return;
    viewportBound = false;
    window.removeEventListener('scroll', onViewportChange, true);
    window.removeEventListener('resize', onViewportChange);
  }

  function openPop() {
    if (isDisabled) return;
    const anchor = parseIsoLocal(selected) || parseIsoLocal(minIso) || new Date();
    viewYear = anchor.getFullYear();
    viewMonth = anchor.getMonth();
    portalParent().appendChild(pop);
    pop.hidden = false;
    root.classList.add('is-open');
    trigger.setAttribute('aria-expanded', 'true');
    paintGrid();
    positionPop();
    bindViewport();
  }

  function closePop() {
    pop.hidden = true;
    root.classList.remove('is-open');
    trigger.setAttribute('aria-expanded', 'false');
    unbindViewport();
  }

  function paintGrid() {
    title.textContent = new Date(viewYear, viewMonth, 1)
      .toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
    grid.textContent = '';
    const first = new Date(viewYear, viewMonth, 1);
    const startPad = (first.getDay() + 6) % 7;
    const daysInMonth = new Date(viewYear, viewMonth + 1, 0).getDate();
    const todayIso = isoLocalDate(new Date());
    for (let i = 0; i < startPad; i++) grid.append(el('span', 'brand-date-pad'));
    for (let day = 1; day <= daysInMonth; day++) {
      const iso = sameIso(viewYear, viewMonth, day);
      const btn = el('button', 'brand-date-day', String(day));
      btn.type = 'button';
      btn.dataset.date = iso;
      if (iso === selected) btn.classList.add('is-selected');
      if (iso === todayIso) btn.classList.add('is-today');
      if (isBeforeMin(viewYear, viewMonth, day)) {
        btn.disabled = true;
        btn.classList.add('is-disabled');
      }
      btn.addEventListener('click', () => {
        setSelected(iso);
        closePop();
      });
      grid.append(btn);
    }
    if (!pop.hidden) positionPop();
  }

  pop.addEventListener('click', (event) => event.stopPropagation());

  trigger.addEventListener('click', (event) => {
    event.stopPropagation();
    if (pop.hidden) openPop();
    else closePop();
  });
  prev.addEventListener('click', (event) => {
    event.stopPropagation();
    viewMonth -= 1;
    if (viewMonth < 0) {
      viewMonth = 11;
      viewYear -= 1;
    }
    paintGrid();
  });
  next.addEventListener('click', (event) => {
    event.stopPropagation();
    viewMonth += 1;
    if (viewMonth > 11) {
      viewMonth = 0;
      viewYear += 1;
    }
    paintGrid();
  });
  todayBtn.addEventListener('click', () => {
    const today = isoLocalDate(new Date());
    if (minIso && today < minIso) return;
    setSelected(today);
    closePop();
  });
  clearBtn.addEventListener('click', () => {
    setSelected('');
    closePop();
  });
  document.addEventListener('click', (event) => {
    if (pop.hidden) return;
    if (root.contains(event.target) || pop.contains(event.target)) return;
    closePop();
  });

  trigger.disabled = isDisabled;
  if (isDisabled) {
    prev.disabled = true;
    next.disabled = true;
    todayBtn.disabled = true;
    clearBtn.disabled = true;
  }

  return {
    root,
    getValue: () => selected,
    setValue(iso) {
      setSelected(iso || '');
    },
    setDisabled(flag) {
      isDisabled = !!flag;
      trigger.disabled = isDisabled;
      prev.disabled = isDisabled;
      next.disabled = isDisabled;
      todayBtn.disabled = isDisabled;
      clearBtn.disabled = isDisabled;
    },
    setMin(iso) {
      minIso = iso || '';
      paintGrid();
    },
  };
}

export function isoLocalDate(d) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function addCalendarAmount(base, amount, unit) {
  const d = new Date(base.getFullYear(), base.getMonth(), base.getDate());
  const n = Math.max(1, Number(amount) || 1);
  if (unit === 'days') d.setDate(d.getDate() + n);
  else if (unit === 'weeks') d.setDate(d.getDate() + n * 7);
  else if (unit === 'months') d.setMonth(d.getMonth() + n);
  else if (unit === 'years') d.setFullYear(d.getFullYear() + n);
  return d;
}

export function formatPlacementUntil(iso) {
  if (!iso) return '';
  const d = new Date(`${iso}T12:00:00`);
  return `Until ${d.toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' })}`;
}

function createPeriodUnitPicker(initial, onChange) {
  const units = ['days', 'weeks', 'months', 'years'];
  const root = el('div', 'period-unit-picker');
  const trigger = el('button', 'period-unit-trigger', initial);
  trigger.type = 'button';
  trigger.setAttribute('aria-haspopup', 'listbox');
  trigger.setAttribute('aria-expanded', 'false');
  const menu = el('div', 'period-unit-menu');
  menu.setAttribute('role', 'listbox');
  menu.hidden = true;
  let current = initial;

  function paint() {
    menu.textContent = '';
    for (const unit of units) {
      const item = el('button', 'period-unit-option', unit);
      item.type = 'button';
      item.setAttribute('role', 'option');
      item.setAttribute('aria-selected', String(unit === current));
      if (unit === current) item.classList.add('is-on');
      item.addEventListener('click', (event) => {
        event.stopPropagation();
        current = unit;
        trigger.textContent = unit;
        close();
        if (typeof onChange === 'function') onChange(unit);
      });
      menu.append(item);
    }
  }

  function close() {
    menu.hidden = true;
    root.classList.remove('is-open');
    trigger.setAttribute('aria-expanded', 'false');
  }

  trigger.addEventListener('click', (event) => {
    event.stopPropagation();
    if (trigger.disabled) return;
    if (menu.hidden) {
      menu.hidden = false;
      root.classList.add('is-open');
      trigger.setAttribute('aria-expanded', 'true');
      paint();
    } else {
      close();
    }
  });
  trigger.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') close();
  });
  document.addEventListener('click', (event) => {
    if (!root.contains(event.target)) close();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') close();
  });
  root.append(trigger, menu);
  return {
    root,
    setDisabled(flag) {
      trigger.disabled = !!flag;
      if (flag) close();
    },
  };
}

function mountPlacementSchedule(card, { active, lockedAnswer, scheduleDefaults, onScheduleSubmit }) {
  const wrap = el('div', 'placement-schedule');
  const interactive = el('div', 'placement-schedule-interactive');
  const summaryEl = el('p', 'placement-schedule-summary note');
  summaryEl.hidden = true;

  let draft = [...(scheduleDefaults.weekdays || [1, 2, 3, 4, 5])];
  let goalMin = PLACEMENT_GOAL_MINUTES.includes(scheduleDefaults.goalMin)
    ? scheduleDefaults.goalMin : 15;
  const defaultEnd = scheduleDefaults.targetEndDate || '';
  let timelineMode = defaultEnd ? 'date' : 'period';
  let periodAmount = 7;
  let periodUnit = 'months';
  let exactDate = defaultEnd;

  const timeLabel = el('p', 'placement-schedule-label muted', 'Target timeline');
  const modeRow = el('div', 'placement-timeline-modes');
  const modePeriod = el('button', 'dow-btn placement-timeline-mode', 'By period');
  const modeDate = el('button', 'dow-btn placement-timeline-mode', 'Exact date');
  modePeriod.type = 'button';
  modeDate.type = 'button';
  modeRow.append(modePeriod, modeDate);

  const periodPanel = el('div', 'placement-timeline-panel placement-timeline-period');
  const periodRow = el('div', 'placement-period-row');
  const periodInput = el('input', 'placement-period-amount');
  periodInput.type = 'number';
  periodInput.min = '1';
  periodInput.value = String(periodAmount);
  const periodUnitPicker = createPeriodUnitPicker(periodUnit, (next) => {
    periodUnit = next;
    syncTimelinePanels();
  });
  periodRow.append(periodInput, periodUnitPicker.root);
  const untilHint = el('p', 'placement-until-hint note');
  periodPanel.append(periodRow, untilHint);

  const datePanel = el('div', 'placement-timeline-panel placement-timeline-date');
  const minToday = isoLocalDate(new Date());
  const datePicker = createBrandDatePicker({
    value: exactDate,
    min: minToday,
    disabled: lockedAnswer != null,
    onChange: (iso) => {
      exactDate = iso || '';
      syncTimelinePanels();
    },
  });
  datePanel.append(datePicker.root);

  const daysLabel = el('p', 'placement-schedule-label muted', 'Study days');
  const daysRow = el('div', 'weekday-picks placement-weekdays');
  const hint = el('p', 'cal-days-hint placement-schedule-hint', 'Pick at least one day.');
  hint.hidden = true;

  const durLabel = el('p', 'placement-schedule-label muted', 'Minutes per day');
  const durRow = el('div', 'placement-duration-chips');

  const actions = el('div', 'placement-schedule-actions');
  const send = el('button', 'solid placement-send', 'Confirm schedule');
  send.type = 'button';

  const locked = lockedAnswer != null;

  function resolvedTargetEnd() {
    if (timelineMode === 'date') {
      return exactDate || null;
    }
    const base = new Date();
    const end = addCalendarAmount(base, periodAmount, periodUnit);
    return isoLocalDate(end);
  }

  function syncTimelinePanels() {
    const isPeriod = timelineMode === 'period';
    periodPanel.hidden = !isPeriod;
    datePanel.hidden = isPeriod;
    modePeriod.classList.toggle('is-on', isPeriod);
    modeDate.classList.toggle('is-on', !isPeriod);
    modePeriod.setAttribute('aria-pressed', String(isPeriod));
    modeDate.setAttribute('aria-pressed', String(!isPeriod));
    const end = resolvedTargetEnd();
    untilHint.textContent = end ? formatPlacementUntil(end) : 'Pick a period.';
    syncSend();
  }

  function syncSend() {
    const timelineOk = timelineMode === 'period'
      || (exactDate && exactDate >= minToday);
    if (!locked) send.disabled = !active || draft.length === 0 || !timelineOk;
  }

  function paintDays() {
    daysRow.textContent = '';
    for (let dow = 1; dow <= 7; dow++) {
      const btn = el('button', 'dow-btn', DOW_SHORT[dow - 1]);
      btn.type = 'button';
      btn.dataset.dow = String(dow);
      if (draft.includes(dow)) btn.classList.add('is-on');
      btn.disabled = locked;
      btn.addEventListener('click', () => {
        if (card.classList.contains('is-locked')) return;
        if (draft.includes(dow)) draft = draft.filter((d) => d !== dow);
        else draft = [...draft, dow].sort((a, b) => a - b);
        paintDays();
      });
      daysRow.append(btn);
    }
    hint.hidden = draft.length > 0;
    syncSend();
  }

  function paintGoals() {
    durRow.textContent = '';
    for (const min of PLACEMENT_GOAL_MINUTES) {
      const chip = el('button', 'placement-duration-chip dow-btn', `${min} min`);
      chip.type = 'button';
      chip.dataset.minutes = String(min);
      if (min === goalMin) chip.classList.add('is-on');
      chip.disabled = locked;
      chip.addEventListener('click', () => {
        if (card.classList.contains('is-locked')) return;
        goalMin = min;
        paintGoals();
      });
      durRow.append(chip);
    }
  }

  if (!locked) {
    modePeriod.addEventListener('click', () => {
      timelineMode = 'period';
      syncTimelinePanels();
    });
    modeDate.addEventListener('click', () => {
      timelineMode = 'date';
      if (!exactDate) exactDate = resolvedTargetEnd();
      datePicker.setValue(exactDate);
      syncTimelinePanels();
    });
    periodInput.addEventListener('input', () => {
      periodAmount = Math.max(1, Number(periodInput.value) || 1);
      periodInput.value = String(periodAmount);
      syncTimelinePanels();
    });
    periodUnitPicker.setDisabled(false);
  } else {
    modePeriod.disabled = true;
    modeDate.disabled = true;
    periodInput.disabled = true;
    periodUnitPicker.setDisabled(true);
    datePicker.setDisabled(true);
  }

  interactive.append(
    timeLabel, modeRow, periodPanel, datePanel,
    daysLabel, daysRow, hint, durLabel, durRow,
  );
  actions.append(send);
  wrap.append(interactive, summaryEl, actions);
  card.append(wrap);

  paintDays();
  paintGoals();
  syncTimelinePanels();

  if (locked) {
    lockPlacementScheduleCard(card, lockedAnswer);
  } else if (active && typeof onScheduleSubmit === 'function') {
    send.addEventListener('click', () => {
      if (card.classList.contains('is-locked') || draft.length === 0) return;
      const targetEndDate = resolvedTargetEnd();
      if (!targetEndDate) return;
      const weekdays = [...draft];
      const summary = formatPlacementScheduleSummary(weekdays, goalMin, targetEndDate);
      onScheduleSubmit({ weekdays, goalMin, targetEndDate, summary }, card);
    });
  } else {
    send.disabled = true;
  }
}

export function formatPlacementScheduleSummary(weekdays, goalMin, targetEndDate) {
  const labels = weekdays.map((d) => DOW_SHORT[d - 1] || d).join(', ');
  const until = targetEndDate ? `${formatPlacementUntil(targetEndDate)}. ` : '';
  return `${until}${weekdays.length} days a week (${labels}), ${goalMin} minutes per day`;
}

function parsePlacementPrompt(prompt, readingPassage = '') {
  const raw = (prompt || '').trim();
  const passage = (readingPassage || '').trim();
  const choices = [];

  const addChoice = (label, text) => {
    const t = text.trim().replace(/\s+/g, ' ');
    if (t) choices.push({ label: label.toUpperCase(), text: t });
  };

  let stem = '';
  const parenChunks = raw.split(/\s*(?=\([a-dA-D]\)\s)/).filter(Boolean);
  if (parenChunks.length) {
    if (!/^\([a-dA-D]\)/.test(parenChunks[0])) stem = parenChunks[0].trim();
    for (const chunk of parenChunks) {
      const m = chunk.match(/^\(([a-dA-D])\)\s*(.+)$/s);
      if (m) addChoice(m[1], m[2]);
    }
  }
  if (!choices.length) {
    const letterChunks = raw.split(/\s*(?=[A-D]\)\s)/).filter(Boolean);
    if (letterChunks.length && !/^[A-D]\)/.test(letterChunks[0])) stem = letterChunks[0].trim();
    for (const chunk of letterChunks) {
      const m = chunk.match(/^([A-D])\)\s*(.+)$/s);
      if (m) addChoice(m[1], m[2]);
    }
  }
  if (!stem && raw) {
    let stripped = raw;
    stripped = stripped.replace(/\s*\([a-dA-D]\)\s*[\s\S]*?(?=\s*\([a-dA-D]\)|$)/g, ' ');
    stripped = stripped.replace(/\s*[A-D]\)\s*[\s\S]*?(?=\s*[A-D]\)|$)/g, ' ');
    stem = stripped.replace(/\s+/g, ' ').trim();
  }
  stem = stripPassageFromStem(stem, passage);
  return { stem, choices, passage };
}

function stripPassageFromStem(stem, passage) {
  let s = (stem || '').trim();
  if (!s) return '';
  if (passage) {
    if (s.includes(passage)) s = s.replace(passage, '').trim();
    const compactPassage = passage.replace(/\s+/g, ' ').trim();
    const compactStem = s.replace(/\s+/g, ' ');
    if (compactStem.includes(compactPassage)) {
      s = compactStem.replace(compactPassage, '').trim();
    }
  }
  s = s.replace(/^Please read (this short passage|the text)[^.]*\.\s*/i, '');
  s = s.replace(/^Read the text silently[^.]*\.\s*/i, '');
  s = s.replace(/^Sounds good!\s*/i, '');
  s = s.replace(/^Here is a new topic[.:]?\s*/i, '');
  return s.replace(/\s+/g, ' ').trim();
}

function populateChoiceChips(wrap, promptOrChoices) {
  const add = (label, text) => {
    const t = text.trim().replace(/\s+/g, ' ');
    if (!t) return;
    const btn = el('button', 'placement-choice grammar-choice-chip', `${label}) ${t}`);
    btn.type = 'button';
    btn.dataset.choiceValue = t;
    wrap.append(btn);
  };

  if (Array.isArray(promptOrChoices)) {
    for (const c of promptOrChoices) add(c.label, c.text);
    return;
  }

  const { choices } = parsePlacementPrompt(promptOrChoices || '');
  for (const c of choices) add(c.label, c.text);
}

/**
 * Фраза ученика, в которой помечены только ошибочные места:
 * неверное слово зачёркнуто красным, сразу за ним — верное зелёным. Остальной текст не тронут.
 */
function fixLine(correction) {
  const line = el('div', 'fix-line');
  const original = correction.original || '';
  if (!correction.corrected || correction.corrected === original) {
    markSpans(line, original, (correction.errors || []).map((e) => e.span).filter(Boolean));
    return line;
  }
  const a = original.split(/\s+/).filter(Boolean);
  const b = correction.corrected.split(/\s+/).filter(Boolean);
  const same = (x, y) => x.replace(/[.,!?;:]+$/, '') === y.replace(/[.,!?;:]+$/, '');
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

const PLACEMENT_GOAL_QUESTION =
  'What is your main goal for learning English (for example: work, IT interviews, exams, or travel)?';

function isJunkPlacementPrompt(text) {
  const t = (text || '').trim();
  if (!t || t.length < 2) return true;
  if (/^[-—–.]+$/.test(t)) return true;
  if (/writing the question/i.test(t)) return true;
  return false;
}

/** Если модель положила задание в reply_en, а follow_up пустой или «.», забираем задание в карточку. */
function extractTaskFromAnswer(en) {
  const text = (en || '').trim();
  if (!text) return null;
  const hasChoices = /\b[A-D]\)\s/.test(text);
  if (!hasChoices && !text.includes('?')) return null;
  const split = text.match(/^([\s\S]{0,120}?[.!?])\s+([\s\S]+)$/);
  if (split && !/\b[A-D]\)\s/.test(split[1])
      && (split[2].includes('?') || /\b[A-D]\)\s/.test(split[2]))) {
    return { bubble: split[1].trim(), prompt: split[2].trim() };
  }
  return { bubble: '', prompt: text };
}

function placementResolved(reply, stepMeta) {
  const en = (reply?.reply_en || '').trim();
  let prompt = (reply?.next_prompt || '').trim();
  let passage = (reply?.reading_passage || '').trim();
  if (isJunkPlacementPrompt(prompt)) prompt = '';
  if (/main learning goal/i.test(stepMeta || '')) {
    let bubble = en;
    if (/\b[A-D]\)\s/.test(en) || en.length > 180) {
      const first = en.match(/^([\s\S]{0,100}?[.!?])/);
      bubble = first ? first[1].trim() : 'Thanks!';
    }
    if (!bubble) bubble = 'Thanks!';
    return { bubble, prompt: PLACEMENT_GOAL_QUESTION, passage: '' };
  }
  if (!prompt || (!prompt.includes(' ') && !prompt.includes('?'))) {
    const extracted = extractTaskFromAnswer(en);
    if (extracted) return { bubble: extracted.bubble, prompt: extracted.prompt, passage };
  }
  if (!prompt) return { bubble: en, prompt: '', passage };
  return { bubble: en, prompt, passage };
}

function placementCardPrompt(reply, fallbackText, stepMeta) {
  if (!reply) return (fallbackText || '').trim();
  return placementResolved(reply, stepMeta).prompt;
}

/** Реакция в пузыре: вопрос остаётся в карточке и не повторяется здесь. */
function placementBubbleText(reply, stepMeta) {
  if (!reply) return '';
  return placementResolved(reply, stepMeta).bubble;
}

function fillTeacher(wrap, bubble, reply, fallbackText, options = {}) {
  bubble.classList.remove('cursor');
  bubble.textContent = '';
  wrap.querySelectorAll('.extra, .translate, .listen').forEach((n) => n.remove());
  const earlyCard = options.earlyCard || null;
  let existing = wrap.nextElementSibling?.classList?.contains('placement-task')
    ? wrap.nextElementSibling : null;
  if (earlyCard && earlyCard.isConnected && earlyCard !== existing) {
    if (!existing) wrap.insertAdjacentElement('afterend', earlyCard);
    existing = earlyCard;
  }
  const draft = existing?.querySelector('.placement-answer-input')?.value || '';
  if (options.scheduleWidget) {
    const ack = (reply?.reply_en || fallbackText || '').trim();
    bubble.textContent = ack;
    if (!ack) wrap.classList.add('placement-teacher-slim');
    else if (ack) wrap.append(listenButton(ack));
    if (!existing?.querySelector('.placement-schedule')) {
      existing?.remove();
      const card = createPlacementTaskCard('', {
        ...options,
        scheduleWidget: true,
        pending: false,
        readingPassage: '',
      });
      wrap.insertAdjacentElement('afterend', card);
    }
    return;
  }
  wrap.classList.remove('placement-teacher-slim');

  const stepMeta = options.stepMeta || '';
  const resolved = options.placement && reply ? placementResolved(reply, stepMeta) : null;
  const cardPrompt = options.placement
    ? (resolved ? resolved.prompt : placementCardPrompt(reply, fallbackText, stepMeta))
    : '';
  let placementSplit = options.placement && cardPrompt;
  if (options.placement && !cardPrompt && !options.scheduleWidget && existing) {
    existing.remove();
    existing = null;
    placementSplit = false;
  }
  const cardOpts = {
    ...options,
    readingPassage: resolved ? resolved.passage : (reply?.reading_passage || '').trim(),
    pending: false,
  };

  function mountCard() {
    if (existing && options.placement && !options.scheduleWidget) {
      patchPlacementTaskCard(existing, cardPrompt, cardOpts);
      if (draft && cardOpts.active && cardOpts.lockedAnswer == null) {
        const input = existing.querySelector('.placement-answer-input');
        if (input && !input.value) {
          input.value = draft;
          fitPlacementAnswerInput(input);
        }
      }
      return;
    }
    existing?.remove();
    const card = createPlacementTaskCard(cardPrompt, cardOpts);
    wrap.insertAdjacentElement('afterend', card);
    if (draft && cardOpts.active && cardOpts.lockedAnswer == null) {
      const input = card.querySelector('.placement-answer-input');
      if (input && !input.value) {
        input.value = draft;
        fitPlacementAnswerInput(input);
      }
    }
  }

  if (!reply) {
    if (placementSplit) {
      bubble.textContent = '';
      if (!placementBubbleText(reply, options.stepMeta)) wrap.classList.add('placement-teacher-slim');
      mountCard();
    } else {
      bubble.textContent = fallbackText || '';
      if (bubble.textContent.trim()) wrap.append(listenButton(bubble.textContent.trim()));
    }
    return;
  }

  if (placementSplit) {
    bubble.textContent = placementBubbleText(reply, options.stepMeta);
    if (!bubble.textContent.trim()) wrap.classList.add('placement-teacher-slim');
    const listenText = bubble.textContent.trim();
    if (listenText) wrap.append(listenButton(listenText));
    mountCard();
  } else {
    const prompt = reply.next_prompt?.trim();
    bubble.textContent = prompt ? reply.reply_en + ' ' + prompt : reply.reply_en;
    if (bubble.textContent.trim()) wrap.append(listenButton(bubble.textContent.trim()));
  }

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
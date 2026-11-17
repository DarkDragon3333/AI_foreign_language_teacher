import { api } from './api.js?v=11';

const $ = (id) => document.getElementById(id);

let queue = [];
let index = 0;
let revealed = false;
let busy = false;

export async function refreshReviewCount() {
  const badge = $('review-count');
  try {
    const data = await api.review();
    badge.hidden = data.due < 1;
    badge.textContent = String(data.due);
  } catch {
    badge.hidden = true;
  }
}

export async function openReview() {
  $('review-status').hidden = true;
  try {
    const data = await api.review();
    queue = data.cards || [];
    index = 0;
    showCurrent();
    await refreshReviewCount();
  } catch (e) {
    $('review-card').hidden = true;
    $('review-empty').hidden = true;
    const status = $('review-status');
    status.hidden = false;
    status.textContent = e.message;
    status.classList.add('error');
  }
}

function showCurrent() {
  const card = queue[index];
  const empty = !card;
  $('review-empty').hidden = !empty;
  $('review-card').hidden = empty;
  if (empty) return;
  revealed = false;
  $('review-title').textContent = card.titleRu || card.title;
  $('review-cefr').textContent = card.cefr;
  $('review-left').textContent = (index + 1) + ' из ' + queue.length;
  $('review-prompt').textContent = 'Как сказать правильно?';
  $('review-front').textContent = '✗ ' + card.exampleBad;
  $('review-rule').textContent = card.ruleRu;
  $('review-ok').textContent = '✓ ' + card.exampleOk;
  $('review-trap').textContent = card.trap;
  $('review-back').hidden = true;
  $('review-show').hidden = false;
  for (const button of document.querySelectorAll('#review-card .rate')) button.hidden = true;
}

function reveal() {
  revealed = true;
  $('review-back').hidden = false;
  $('review-show').hidden = true;
  for (const button of document.querySelectorAll('#review-card .rate')) button.hidden = false;
}

$('review-show').addEventListener('click', reveal);

for (const [id, rating] of [['review-again', 1], ['review-hard', 2], ['review-good', 3], ['review-easy', 4]]) {
  $(id).addEventListener('click', () => answer(rating));
}

async function answer(rating) {
  const card = queue[index];
  if (!card || busy || !revealed) return;
  busy = true;
  const status = $('review-status');
  try {
    const result = await api.rateReview(card.slug, rating);
    status.hidden = false;
    status.classList.remove('error');
    status.textContent = result.intervalDays > 0
      ? 'Следующий раз: ' + when(result.intervalDays)
      : 'Снова сегодня, чуть позже';
    queue.splice(index, 1);
    if (index >= queue.length) index = 0;
    showCurrent();
    await refreshReviewCount();
  } catch (e) {
    status.hidden = false;
    status.classList.add('error');
    status.textContent = e.message;
  }
  busy = false;
}

function when(days) {
  if (days === 1) return 'завтра';
  const n = days % 10;
  const teen = days % 100;
  const word = n === 1 && teen !== 11 ? 'день' : n >= 2 && n <= 4 && (teen < 12 || teen > 14) ? 'дня' : 'дней';
  return 'через ' + days + ' ' + word;
}

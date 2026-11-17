import { api, downloadAnki } from './api.js?v=16';

const $ = (id) => document.getElementById(id);

let queue = [];
let index = 0;
let revealed = false;
let busy = false;

export async function refreshReviewCount() {
  const badges = [$('review-count'), $('review-count-progress')].filter(Boolean);
  try {
    const data = await api.review();
    for (const badge of badges) {
      badge.hidden = data.due < 1;
      badge.textContent = data.due + ' due';
    }
  } catch {
    for (const badge of badges) badge.hidden = true;
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
  $('review-left').textContent = (index + 1) + ' of ' + queue.length;
  $('review-prompt').textContent = 'How would you say this?';
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
      ? 'Next review: ' + when(result.intervalDays)
      : 'Again later today';
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

$('export-anki-fsrs').addEventListener('click', () => downloadAnki('fsrs'));

function when(days) {
  if (days === 1) return 'tomorrow';
  return 'in ' + days + ' days';
}

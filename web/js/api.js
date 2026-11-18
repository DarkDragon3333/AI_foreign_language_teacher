// Обёртка над REST API бэкенда.

async function request(method, path, body) {
  const response = await fetch(path, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let data = null;
  try { data = await response.json(); } catch { /* пустое тело */ }
  if (!response.ok && path !== '/api/health') {
    throw new Error((data && data.error) || `Ошибка сервера (${response.status})`);
  }
  return data;
}

export const api = {
  health: () => request('GET', '/api/health'),
  profile: () => request('GET', '/api/profile'),
  updateProfile: (patch) => request('PUT', '/api/profile', patch),
  todayProgress: () => request('GET', '/api/progress/today'),
  scheduleWeek: () => request('GET', '/api/schedule/week'),
  scheduleWeekdays: (days) => request('PATCH', '/api/schedule/weekdays', { days }),
  patchScheduleSlot: (date, patch) => request('PATCH', `/api/schedule/slots?date=${encodeURIComponent(date)}`, patch),
  insights: (limit = 5) => request('GET', `/api/insights?limit=${limit}`),
  revisePlan: () => request('POST', '/api/insights/plan'),
  sessions: () => request('GET', '/api/sessions'),
  startSession: (params) => request('POST', '/api/sessions', params),
  messages: (sessionId) => request('GET', `/api/sessions/${sessionId}/messages`),
  setLevel: (sessionId, cefr) => request('PUT', `/api/sessions/${sessionId}/level`, { cefr }),
  errors: () => request('GET', '/api/errors'),
  review: () => request('GET', '/api/review'),
  rateReview: (slug, rating) => request('POST', `/api/review/${encodeURIComponent(slug)}`, { rating }),
  endSession: (sessionId) => request('POST', `/api/sessions/${sessionId}/end`),
  scenarios: () => request('GET', '/api/scenarios'),
  scenarioProgress: (sessionId) => request('GET', `/api/scenarios/sessions/${sessionId}`),
};

/** CSV для Anki; scope: fsrs (по умолчанию), due, catalog */
export function downloadAnki(scope = 'fsrs') {
  window.location.assign(`/api/export/anki?scope=${encodeURIComponent(scope)}`);
}

/** Запись с микрофона → текст. Не ставит Content-Type: границу формы ставит браузер. */
export async function transcribe(blob, sessionId) {
  const body = new FormData();
  body.append('file', blob, 'speech.webm');
  if (sessionId != null) body.append('sessionId', String(sessionId));
  const response = await fetch('/api/audio/stt', { method: 'POST', body });
  let data = null;
  try { data = await response.json(); } catch { /* не JSON */ }
  if (!response.ok) {
    throw new Error((data && data.error) || `Ошибка распознавания (${response.status})`);
  }
  return data.text;
}

/** Английский текст реплики → адрес WAV, который можно отдать плееру. */
export async function speak(text) {
  const response = await fetch('/api/audio/tts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  });
  if (!response.ok) {
    let data = null;
    try { data = await response.json(); } catch { /* тело может быть пустым */ }
    throw new Error((data && data.error) || `Озвучка недоступна (${response.status})`);
  }
  return URL.createObjectURL(await response.blob());
}

/**
 * Ход урока. Ответ приходит потоком SSE; EventSource не умеет POST, поэтому читаем поток вручную.
 * handlers: { delta(text), final(payload), error(message) }
 */
export async function chatTurn({ sessionId, text, hint }, handlers) {
  const response = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ sessionId, text, hint: !!hint }),
  });
  if (!response.ok || !response.body) {
    let message = `Ошибка сервера (${response.status})`;
    try { message = (await response.json()).error || message; } catch { /* не JSON */ }
    throw new Error(message);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finished = false;

  const dispatch = (block) => {
    let event = 'message';
    const data = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    if (!data.length) return;
    const payload = JSON.parse(data.join('\n'));
    if (event === 'delta') handlers.delta?.(payload.text);
    else if (event === 'final') { finished = true; handlers.final?.(payload); }
    else if (event === 'error') { finished = true; handlers.error?.(payload.error); }
  };

  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
    let split;
    while ((split = buffer.indexOf('\n\n')) >= 0) {
      dispatch(buffer.slice(0, split));
      buffer = buffer.slice(split + 2);
    }
  }
  if (buffer.trim()) dispatch(buffer);
  if (!finished) throw new Error('Соединение прервалось до конца ответа');
}

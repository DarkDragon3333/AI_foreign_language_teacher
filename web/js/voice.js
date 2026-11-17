// Дуплексный голос: WebSocket /ws/voice, PCM 16 kHz, перебивание ответа.

const SAMPLE_RATE = 16000;

const PHASE_LABEL = {
  listen: 'Listening…',
  think: 'The teacher is thinking…',
  speak: 'Speaking…',
};

export class VoiceDuplex {
  #ws = null;
  #sessionId = null;
  #mode = 'handsfree';
  #handlers = null;
  #stream = null;
  #ctx = null;
  #processor = null;
  #expectAudio = false;
  #queue = [];
  #current = null;
  #playing = false;
  #phase = 'listen';

  connect(sessionId, handlers, mode = 'handsfree') {
    this.disconnect();
    this.#sessionId = sessionId;
    this.#handlers = handlers;
    this.#mode = mode === 'ptt' ? 'ptt' : 'handsfree';
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    this.#ws = new WebSocket(`${proto}//${location.host}/ws/voice`);
    this.#ws.binaryType = 'arraybuffer';
    this.#ws.addEventListener('message', (event) => this.#onMessage(event));
    return new Promise((resolve, reject) => {
      let settled = false;
      const done = (ok, value) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        this.#ws.removeEventListener('message', onHandshake);
        if (ok) resolve(value);
        else reject(value);
      };
      const timer = setTimeout(() => {
        done(false, new Error('Сервер долго не отвечает на голосовой канал'));
      }, 20000);

      const onHandshake = (event) => {
        if (typeof event.data !== 'string') return;
        let payload;
        try { payload = JSON.parse(event.data); } catch { return; }
        if (payload.type === 'ready') {
          done(true, payload);
          return;
        }
        if (payload.type === 'error') {
          done(false, new Error(payload.message || 'Голосовой режим недоступен'));
        }
      };
      this.#ws.addEventListener('message', onHandshake);
      this.#ws.addEventListener('close', (event) => {
        if (!settled) {
          const hint = event.code === 1006 || event.code === 1002
            ? 'Не открывается WebSocket /ws/voice — после правки nginx.conf выполните: docker compose restart nginx'
            : 'Соединение голосового канала оборвалось';
          done(false, new Error(hint));
        } else {
          this.#handlers?.onClose?.();
        }
      });
      this.#ws.addEventListener('open', () => {
        this.#ws.send(JSON.stringify({
          type: 'hello',
          sessionId: this.#sessionId,
          mode: this.#mode,
        }));
        this.#startMic().catch((e) => done(false, e instanceof Error ? e : new Error(String(e))));
      });
    });
  }

  beginLesson() {
    this.#send({ type: 'begin' });
  }

  pttDown() {
    if (this.#mode !== 'ptt') return;
    this.#send({ type: 'ptt_start' });
  }

  pttUp() {
    if (this.#mode !== 'ptt') return;
    this.#send({ type: 'ptt_end' });
  }

  disconnect() {
    this.#stopPlayback();
    if (this.#processor) {
      this.#processor.disconnect();
      this.#processor = null;
    }
    if (this.#ctx) {
      this.#ctx.close().catch(() => {});
      this.#ctx = null;
    }
    if (this.#stream) {
      this.#stream.getTracks().forEach((t) => t.stop());
      this.#stream = null;
    }
    if (this.#ws && this.#ws.readyState === WebSocket.OPEN) {
      try { this.#ws.send(JSON.stringify({ type: 'stop' })); } catch { /* уже закрыто */ }
      this.#ws.close();
    }
    this.#ws = null;
    this.#sessionId = null;
  }

  phase() {
    if (this.#playing) return 'speak';
    return this.#phase;
  }

  phaseLabel() {
    return PHASE_LABEL[this.phase()] || '';
  }

  async #startMic() {
    this.#stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true },
    });
    this.#ctx = new AudioContext();
    const inputRate = this.#ctx.sampleRate;
    const source = this.#ctx.createMediaStreamSource(this.#stream);
    this.#processor = this.#ctx.createScriptProcessor(4096, 1, 1);
    this.#processor.onaudioprocess = (event) => {
      if (!this.#ws || this.#ws.readyState !== WebSocket.OPEN) return;
      const down = downsample(event.inputBuffer.getChannelData(0), inputRate, SAMPLE_RATE);
      const pcm = floatTo16(down);
      this.#ws.send(pcm.buffer);
    };
    const silence = this.#ctx.createGain();
    silence.gain.value = 0;
    source.connect(this.#processor);
    this.#processor.connect(silence);
    silence.connect(this.#ctx.destination);
  }

  #onMessage(event) {
    if (event.data instanceof ArrayBuffer) {
      if (!this.#expectAudio) return;
      this.#expectAudio = false;
      this.#enqueue(new Blob([event.data], { type: 'audio/wav' }));
      return;
    }
    let msg;
    try { msg = JSON.parse(event.data); } catch { return; }
    if (msg.type === 'state') {
      this.#phase = msg.phase || 'listen';
      this.#handlers?.onPhase?.(this.#phase);
      return;
    }
    if (msg.type === 'audio') {
      this.#expectAudio = true;
      return;
    }
    if (msg.type === 'cancel_audio') {
      this.#stopPlayback();
      return;
    }
    if (msg.type === 'delta') {
      this.#handlers?.onDelta?.(msg.text);
      return;
    }
    if (msg.type === 'transcript') {
      this.#handlers?.onTranscript?.(msg.text);
      return;
    }
    if (msg.type === 'final') {
      this.#handlers?.onFinal?.(msg);
      return;
    }
    if (msg.type === 'error') {
      this.#handlers?.onError?.(msg.message);
    }
  }

  #send(body) {
    if (this.#ws && this.#ws.readyState === WebSocket.OPEN) {
      this.#ws.send(JSON.stringify(body));
    }
  }

  #enqueue(blob) {
    this.#queue.push(blob);
    this.#playNext();
  }

  #playNext() {
    if (this.#playing || !this.#queue.length) return;
    const blob = this.#queue.shift();
    const url = URL.createObjectURL(blob);
    const audio = new Audio(url);
    this.#current = { audio, url };
    this.#playing = true;
    this.#handlers?.onPhase?.('speak');
    audio.addEventListener('ended', () => {
      URL.revokeObjectURL(url);
      this.#playing = false;
      this.#current = null;
      if (!this.#queue.length) this.#handlers?.onPhase?.(this.#phase);
      this.#playNext();
    });
    audio.play().catch(() => {
      this.#playing = false;
      this.#handlers?.onError?.('Браузер не дал воспроизвести звук');
    });
  }

  #stopPlayback() {
    this.#queue = [];
    if (this.#current) {
      this.#current.audio.pause();
      URL.revokeObjectURL(this.#current.url);
      this.#current = null;
    }
    this.#playing = false;
    this.#expectAudio = false;
  }
}

function downsample(buffer, inputRate, outputRate) {
  if (outputRate === inputRate) return buffer;
  const ratio = inputRate / outputRate;
  const length = Math.round(buffer.length / ratio);
  const out = new Float32Array(length);
  for (let i = 0; i < length; i++) {
    const idx = i * ratio;
    const i0 = Math.floor(idx);
    const i1 = Math.min(i0 + 1, buffer.length - 1);
    const frac = idx - i0;
    out[i] = buffer[i0] * (1 - frac) + buffer[i1] * frac;
  }
  return out;
}

function floatTo16(float32) {
  const out = new Int16Array(float32.length);
  for (let i = 0; i < float32.length; i++) {
    const s = Math.max(-1, Math.min(1, float32[i]));
    out[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
  }
  return out;
}
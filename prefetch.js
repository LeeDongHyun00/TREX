/*
 * StoryModels: fetches the story's models from the moment the page starts,
 * before three.js and the story's code have come in, in the order the story
 * needs them and a few at a time, and says how much of them is in.
 *
 *   const models = StoryModels.fetch('models/glb/');  // starts at once
 *   models.progress((share) => …);  // 0 to 1 by bytes, never going back
 *   const buffer = await models.get('dino');  // dino.glb as an ArrayBuffer
 *
 * A model the order leaves out is fetched when it is first asked for.
 */
(function (global) {
  'use strict';

  // The opening's models first, so its scene can fill in first.
  const ORDER = ['dumbbell', 'barbell', 'bottle', 'dino', 'morning', 'sunny', 'cloudy', 'snow', 'rain', 'noon', 'burger',
    'calories', 'carbs', 'protein', 'fat', 'evening', 'night', 'button'];
  // Requests at once: enough to keep the connection busy, few enough that
  // the first models are not slowed by the last.
  const PARALLEL = 6;

  function fetchModels(base, { order = ORDER, parallel = PARALLEL } = {}) {
    const files = new Map(); // name -> { promise, resolve, reject, loaded, total, done }
    const queue = [];
    const listeners = [];
    let running = 0;
    let reported = 0;

    // A model asked for out of order goes to the front of the queue.
    function entry(name, first = false) {
      let file = files.get(name);
      if (!file) {
        file = { loaded: 0, total: 0, done: false };
        file.promise = new Promise((resolve, reject) => Object.assign(file, { resolve, reject }));
        files.set(name, file);
        if (first) queue.unshift(name);
        else queue.push(name);
      }
      return file;
    }

    /*
     * The share that is in, by bytes: a model whose size is not known yet
     * counts as the mean of those that are, and one that is never known
     * (a compressed response's length is not its size) only once it is in.
     */
    function share() {
      const sizes = [...files.values()].map((f) => f.total).filter(Boolean);
      const guess = sizes.length ? sizes.reduce((a, b) => a + b) / sizes.length : 1;
      let have = 0, all = 0;
      for (const f of files.values()) {
        const size = f.total || guess;
        all += size;
        if (f.done) have += size;
        else if (f.total) have += Math.min(f.loaded, f.total);
      }
      return all ? have / all : 0;
    }

    function report() {
      reported = Math.max(reported, share());
      for (const listener of listeners) listener(reported);
    }

    async function download(name, file) {
      const response = await fetch(new URL(`${name}.glb`, new URL(base, document.baseURI)));
      if (!response.ok) throw new Error(`${name}.glb: ${response.status}`);
      file.total = response.headers.get('content-encoding') ? 0 : Number(response.headers.get('content-length')) || 0;
      if (!response.body) return response.arrayBuffer();
      const reader = response.body.getReader();
      const parts = [];
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        parts.push(value);
        file.loaded += value.length;
        report();
      }
      const bytes = new Uint8Array(file.loaded);
      let at = 0;
      for (const part of parts) {
        bytes.set(part, at);
        at += part.length;
      }
      return bytes.buffer;
    }

    function pump() {
      while (running < parallel && queue.length) {
        const name = queue.shift();
        const file = files.get(name);
        running += 1;
        download(name, file)
          .then(file.resolve, file.reject)
          .finally(() => {
            file.done = true;
            report();
            running -= 1;
            pump();
          });
      }
    }

    for (const name of order) entry(name);
    pump();
    return {
      get(name) {
        const file = entry(name, true);
        pump();
        return file.promise;
      },
      progress(listener) {
        listeners.push(listener);
        listener(reported);
      },
    };
  }

  global.StoryModels = { ORDER, fetch: fetchModels };
})(window);

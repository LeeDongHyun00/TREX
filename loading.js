/*
 * PixelLoading: the loading screen. The TREX coach, in pixels, walks a
 * loading bar while the story comes in. The bar is the ground: it fills green
 * behind the coach, which walks as far as the story has loaded, hops over the
 * dumbbell and the barbell in its way, and waits when the loading does. It
 * is not a game; nothing on it is pressed. When the story is ready, the coach
 * hurries to the end of the bar and the screen fades into the first card.
 *
 *   const loading = new PixelLoading(element, { covers: stage });
 *   loading.progress(0.4);  // the share loaded, 0 to 1; it never goes back
 *   loading.done();         // ready, or given up on: finish and fade out
 *
 * The element holds a canvas (.loading__scene), drawn one pixel per art pixel
 * and scaled up by a whole number of device pixels, so the art stays sharp.
 * `covers`, the page under the screen, is kept inert until the screen has
 * gone. Until the first progress() the bar creeps a little on its own, for
 * the scripts that load before the story can count anything, and it keeps
 * its last stretch for done(). After TIMEOUT seconds it lets the page in
 * whatever happens.
 */
(function (global) {
  'use strict';

  const PALETTE = {
    g: '#759848', // the coach, in the app's green
    d: '#557233', // its far leg, brow and back spikes
    b: '#e2e8b8', // belly
    k: '#1f2618', // eye and mouth
    w: '#ffffff', // eye white and teeth
    p: '#3b4147', // weight plates
    q: '#6b747c', // their rims
    s: '#b9c0c6', // steel
    c: '#e4e8eb', // clouds
    n: '#a3abb2', // percent figures
  };

  // The coach facing right, without its legs.
  const COACH = [
    '...........d.ggggggg.......',
    '..........dgggggggggggg....',
    '.........dggggggdddgggggg..',
    '........dggggggwwwgggggggg.',
    '........gggggggwkkggggggggg',
    '........gggggggwkkggggggggg',
    '........ggggggggggggggggggg',
    '........ggggggggggkkkkkkkkk',
    '........gggggggggggwgwgwgw.',
    '.........gggggbbbbbbbbbbb..',
    '........dgggggbbbbbbbbb....',
    '.......dggggggbbbbbbbbgg...',
    'd.....dgggggggbbbbbbbggg...',
    'gd...dggggggggbbbbbbbb.....',
    'ggggggggggggggbbbbbbbb.....',
    '.gggggggggggggbbbbbbbb.....',
    '...ggggggggggggbbbbbb......',
    '.......gggggggggbbbb.......',
    '........gggggggggg.........',
  ];
  // Its legs: both down, or the far (dark) or the near one lifted.
  const LEGS = {
    stand: [
      '........ddd...ggg..........',
      '........ddd...ggg..........',
      '........ddd...ggg..........',
      '........ddddd.ggggg........',
    ],
    near: [
      '........ddd...ggg..........',
      '........ddd...gggg.........',
      '........ddd................',
      '........ddddd..............',
    ],
    far: [
      '........ddd...ggg..........',
      '........dddd..ggg..........',
      '..............ggg..........',
      '..............ggggg........',
    ],
  };
  // The eye closed, drawn over the open one.
  const BLINK = { x: 15, y: 3, rows: ['ggg', 'ggg', 'kkk'] };
  // A dumbbell standing on end, for the small cactus, and a barbell lying
  // along the ground, plates at both ends, for the pair of cacti.
  const DUMBBELL = [
    '.ppppp.',
    'ppppppp',
    'pqqqqqp',
    'ppppppp',
    '..sss..',
    '..sss..',
    '..sss..',
    '..sss..',
    'ppppppp',
    'pqqqqqp',
    'ppppppp',
    '.ppppp.',
  ];
  const BARBELL = [
    '..ppp............ppp..',
    '.pppp............pppp.',
    '.ppppq..........qpppp.',
    '.ppppq..........qpppp.',
    '.ppppq..........qpppp.',
    '.ppppq..........qpppp.',
    'sppppqssssssssssqpppps',
    '.ppppq..........qpppp.',
    '.ppppq..........qpppp.',
    '.ppppq..........qpppp.',
    '.ppppq..........qpppp.',
    '.pppp............pppp.',
    '..ppp............ppp..',
  ];
  const CLOUD = [
    '....cccc.....',
    '..cccccccc...',
    '.ccccccccccc.',
    'ccccccccccccc',
  ];
  const FIGURES = {
    0: ['111', '101', '101', '101', '111'],
    1: ['010', '110', '010', '010', '111'],
    2: ['111', '001', '111', '100', '111'],
    3: ['111', '001', '111', '001', '111'],
    4: ['101', '101', '111', '001', '001'],
    5: ['111', '100', '111', '001', '111'],
    6: ['111', '100', '111', '101', '111'],
    7: ['111', '001', '001', '010', '010'],
    8: ['111', '101', '111', '101', '111'],
    9: ['111', '101', '111', '001', '111'],
    '%': ['101', '001', '010', '100', '101'],
  };

  // Art pixels: the scene is about this wide, whatever the screen.
  const ART_WIDTH = 175;
  const HEIGHT = 56;
  const GROUND = 49; // the bar's top row
  const COACH_WIDTH = COACH[0].length;
  const COACH_HEIGHT = COACH.length + LEGS.stand.length;
  // Steps a second at full pace, and how far before an obstacle a hop
  // starts (art px).
  const CADENCE = 10;
  const LEAD = 5;
  const HOP = { height: 22, time: 560, hurried: 340 };
  // Art px per second: keeping up with the loading, and hurrying at the end.
  const WALK = 55;
  const HURRY = 150;
  // Before the story counts anything, the bar creeps toward this share; all
  // of its downloads take it to LOADED, and the rest waits for it to be ready,
  // as it still sets its scenes up then.
  const CREEP = 0.08;
  const LOADED = 0.92;
  // Seconds after which the screen gives up and lets the page in anyway.
  const TIMEOUT = 45;

  const reducedMotion = global.matchMedia('(prefers-reduced-motion: reduce)');
  const clamp = (n, lo, hi) => Math.min(hi, Math.max(lo, n));

  // A sprite: rows of palette keys drawn once on a canvas of its own.
  function sprite(rows) {
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(...rows.map((row) => row.length));
    canvas.height = rows.length;
    const paint = canvas.getContext('2d');
    rows.forEach((row, y) => {
      for (let x = 0; x < row.length; x++) {
        const color = PALETTE[row[x]];
        if (!color) continue;
        paint.fillStyle = color;
        paint.fillRect(x, y, 1, 1);
      }
    });
    return canvas;
  }

  class PixelLoading {
    constructor(element, { covers = null, timeout = TIMEOUT } = {}) {
      this.element = element;
      this.covers = covers;
      this.canvas = element.querySelector('.loading__scene');
      this.paint = this.canvas.getContext('2d');
      this.sprites = {
        coach: sprite(COACH),
        legs: Object.fromEntries(Object.entries(LEGS).map(([key, rows]) => [key, sprite(rows)])),
        blink: sprite(BLINK.rows),
        dumbbell: sprite(DUMBBELL),
        barbell: sprite(BARBELL),
        cloud: sprite(CLOUD),
      };
      this.width = ART_WIDTH;
      this.start = performance.now();
      this.loaded = 0; // what the story has said, 0 to 1
      this.counted = false;
      this.ready = false;
      this.gone = false;
      this.along = 0; // where the coach is along the bar, 0 to 1
      this.walked = 0; // steps taken
      this.stepping = false;
      this.hop = null; // { from, to, start, time } while in the air
      this.obstacles = [];
      this.cleared = 0; // obstacles behind it
      this.arrived = 0; // when it reached the end, once ready
      this.blinkAt = this.start + 2400;
      this.shown = -1;
      this.time = this.start;
      this.frame = 0;

      this.tick = this.tick.bind(this);
      if (covers) covers.inert = true;
      this.resizeObserver = new ResizeObserver(() => this.measure());
      this.resizeObserver.observe(element);
      this.measure();
      this.giveUp = setTimeout(() => this.done(), timeout * 1000);
      this.frame = requestAnimationFrame(this.tick);
    }

    progress(fraction) {
      this.counted = true;
      this.loaded = Math.max(this.loaded, clamp(fraction, 0, 1));
    }

    done() {
      if (this.ready) return;
      this.ready = true;
      this.loaded = 1;
      clearTimeout(this.giveUp);
    }

    // Sizes the canvas to a whole number of device pixels per art pixel,
    // about ART_WIDTH art pixels across the space it has.
    measure() {
      const room = this.canvas.parentElement.clientWidth * 0.84;
      if (!room) return;
      const ratio = global.devicePixelRatio || 1;
      const device = Math.min(room, 360) * ratio;
      const scale = Math.max(1, Math.round(device / ART_WIDTH));
      this.width = Math.floor(device / scale);
      this.canvas.width = this.width;
      this.canvas.height = HEIGHT;
      this.canvas.style.width = `${(this.width * scale) / ratio}px`;
      this.canvas.style.height = `${(HEIGHT * scale) / ratio}px`;
      // A dumbbell and the barbell, and a second dumbbell if there is room;
      // placed by share of the bar, far enough apart to land between.
      const kinds = this.width < 140 ? ['dumbbell'] : this.width < 215 ? ['dumbbell', 'barbell'] : ['dumbbell', 'barbell', 'dumbbell'];
      const places = { 1: [0.5], 2: [0.33, 0.6], 3: [0.27, 0.5, 0.77] }[kinds.length];
      this.obstacles = kinds.map((kind, i) => {
        const art = this.sprites[kind];
        return { kind, x: Math.round(places[i] * this.width), w: art.width, h: art.height };
      });
      this.draw();
    }

    // The first and last places of the coach's left edge, in art px.
    get first() {
      return 2;
    }

    get last() {
      return this.width - COACH_WIDTH - 2;
    }

    tick(now) {
      this.frame = 0;
      const dt = Math.min(0.1, (now - this.time) / 1000);
      this.time = now;
      this.move(now, dt);
      this.draw(now);
      if (this.ready && this.arrived && now - this.arrived > 280 && !this.gone) this.leave();
      if (!this.gone) this.frame = requestAnimationFrame(this.tick);
    }

    // How far the coach may walk: as far as the story has loaded, or the
    // creep before it counts.
    goal(now) {
      const creep = CREEP * (1 - Math.exp(-(now - this.start) / 1600));
      const share = this.counted ? CREEP + (LOADED - CREEP) * this.loaded : creep;
      return this.ready ? 1 : Math.max(creep, share);
    }

    move(now, dt) {
      const { first, last } = this;
      const span = last - first;
      let x = first + this.along * span;
      if (this.hop) {
        // In the air it goes on whatever the loading does.
        const t = (now - this.hop.start) / this.hop.time;
        if (t >= 1) {
          x = this.hop.to;
          this.hop = null;
          this.cleared += 1;
        } else {
          x = this.hop.from + (this.hop.to - this.hop.from) * t;
        }
      } else {
        const target = first + this.goal(now) * span;
        const speed = this.ready ? HURRY : Math.min(WALK, Math.max(0, target - x) * 3);
        // Never back: after a hop it can be ahead of the loading.
        let next = Math.max(x, Math.min(target, x + speed * dt));
        const obstacle = this.obstacles[this.cleared];
        if (obstacle) {
          // Up to the obstacle, then over it.
          const takeoff = obstacle.x - LEAD - COACH_WIDTH;
          if (next >= takeoff) {
            next = Math.max(x, takeoff);
            this.hop = { from: next, to: obstacle.x + obstacle.w + 2, start: now, time: this.ready ? HOP.hurried : HOP.time };
          }
        }
        // A step per tenth of a second at full pace, slower when slower.
        this.stepping = next - x > 1e-3;
        this.walked += ((next - x) / Math.max(3, (this.ready ? HURRY : WALK) / CADENCE));
        x = next;
        if (this.ready && !this.arrived && x >= last - 0.01) this.arrived = now;
      }
      this.along = clamp((x - first) / span, 0, 1);
      // The bar reads as its percentage too, for assistive technology.
      const shown = Math.round(this.share() * 100);
      if (shown !== this.shown) {
        this.shown = shown;
        this.element.setAttribute('aria-valuenow', String(shown));
      }
    }

    // What the figures say: the loading, not where the coach is.
    share() {
      return this.ready ? 1 : this.goal(this.time);
    }

    draw(now = performance.now()) {
      const { paint, width, sprites } = this;
      const still = reducedMotion.matches;
      paint.clearRect(0, 0, width, HEIGHT);

      // Clouds drift slowly left.
      const drift = still ? 0 : (now - this.start) / 260;
      [[0.28, 8], [0.78, 3]].forEach(([at, y]) => {
        const span = width + sprites.cloud.width;
        const x = Math.round((((at * span - drift) % span) + span) % span) - sprites.cloud.width;
        paint.drawImage(sprites.cloud, x, y);
      });

      // The bar: green as far as the coach has walked, grey on ahead, with
      // a few pebbles under it.
      const x = Math.round(this.first + this.along * (this.last - this.first));
      const fill = x + 14;
      paint.fillStyle = PALETTE.g;
      paint.fillRect(0, GROUND, fill, 2);
      paint.fillStyle = '#d3d8dc';
      paint.fillRect(fill, GROUND, width - fill, 2);
      for (let i = 0; i < width; i += 9) {
        const dx = (i * 7) % 5, dy = (i * 3) % 3;
        paint.fillRect(i + dx, GROUND + 3 + dy, (i % 3) + 1, 1);
      }

      for (const { kind, x: ox, h } of this.obstacles) paint.drawImage(sprites[kind], ox, GROUND - h);

      // The coach, its legs stepping with the ground it covers.
      let lift = 0;
      if (this.hop) {
        const t = clamp((now - this.hop.start) / this.hop.time, 0, 1);
        lift = Math.round(4 * HOP.height * t * (1 - t));
      }
      const top = GROUND - COACH_HEIGHT - lift;
      const stepping = !still && !this.hop && this.stepping;
      const legs = stepping ? (Math.floor(this.walked) % 2 ? sprites.legs.near : sprites.legs.far) : sprites.legs.stand;
      paint.drawImage(sprites.coach, x, top);
      paint.drawImage(legs, x, top + COACH.length);
      if (now > this.blinkAt) {
        paint.drawImage(sprites.blink, x + BLINK.x, top + BLINK.y);
        if (now > this.blinkAt + 130) this.blinkAt = now + 2600 + Math.random() * 1800;
      }

      // The percentage at the top right, in pixel figures.
      const text = `${Math.round(this.share() * 100)}%`;
      let fx = width - 2 - text.length * 4 + 1;
      paint.fillStyle = PALETTE.n;
      for (const ch of text) {
        FIGURES[ch].forEach((row, y) => {
          for (let i = 0; i < 3; i++) if (row[i] === '1') paint.fillRect(fx + i, 2 + y, 1, 1);
        });
        fx += 4;
      }
    }

    leave() {
      this.gone = true;
      this.resizeObserver.disconnect();
      this.element.classList.add('is-done');
      let left = false;
      const remove = () => {
        if (left) return;
        left = true;
        if (this.covers) this.covers.inert = false;
        this.element.remove();
      };
      this.element.addEventListener('transitionend', remove, { once: true });
      // In case no transition runs (reduced motion or a hidden tab).
      setTimeout(remove, 700);
    }
  }

  global.PixelLoading = PixelLoading;
})(window);

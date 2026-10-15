/*
 * Carousel3D: a ring of cards seen from just in front of it.
 *
 * The camera stands outside a large ring at eye level, looking straight
 * ahead. Each card shows its face on the near side of the ring, low and close
 * enough to run off the bottom of the screen, and owns the band of the ring's
 * far wall directly opposite, which fills the background behind it and carries
 * its caption. The bands join into one continuous wall with sharp borders.
 * Dragging turns the ring by exactly as much as the drag and leaves it where
 * the drag ends: the near side carries the cards one way while the far wall
 * and its captions sweep the other way. The drag runs sideways, or up and down
 * like a scroll with axis: 'y'.
 *
 * A card can also hold the ring. With data-hold, the ring rests at that card
 * for that many stage widths of drag before it turns on, and the card's beat
 * (see beat()) runs from 0 to 1 over the rest, so a scene can play out on the
 * card under the finger and run backwards when the drag does. With data-play
 * instead, the beat plays by itself over that many seconds once the ring
 * comes to rest at the card, and the drag only turns the ring. With snap on,
 * a drag or a turn of the wheel that ends between two cards eases on to one
 * of them. With loop off, the ring runs from the first card to the last and
 * stops at both.
 *
 * Markup (see carousel.css):
 *   <div class="carousel" tabindex="0" aria-label="…">
 *     <ol class="carousel__ring">
 *       <li class="carousel__card" data-band="#dff1ff" data-hold="1.2">
 *         <div class="carousel__face">…</div>
 *         <div class="carousel__caption">…</div>   (optional)
 *       </li>
 *     </ol>
 *   </div>
 *
 * Events dispatched on the root element (detail: { angle, index, travel }):
 *   carousel:move    every rendered frame
 *   carousel:settle  when a drag ends or a step to a card finishes
 *   carousel:played  when the card the ring rests at has played its beat (at
 *                    once for a card with nothing to play), and again each
 *                    time the ring comes back to rest there
 */
(function (global) {
  'use strict';

  const DEFAULTS = {
    // Vertical field of view in degrees; sets how far the camera stands from
    // the front card.
    fov: 38,
    // Ring radius in card widths. A large ring pushes the far wall well back,
    // so its middle recedes while its sides come closer.
    radius: 5.4,
    // Stage widths of drag that turn the ring by one card, along either axis.
    swipe: 2.5,
    // How far a front card turns with the ring as it leaves: 0 keeps it facing
    // the camera, 1 turns it fully. Partway keeps it readable and gets it off
    // the screen long before its back could show.
    turn: 0.48,
    // 1: swiping left carries the front card left and the far wall right
    // (clockwise seen from above); swiping right does the reverse. -1 inverts
    // the drag.
    direction: 1,
    // 'x': the drag runs sideways. 'y': it runs up and down like a scroll, so
    // pushing up (or scrolling down) goes on as dragging left would, and the
    // wheel and the up and down keys follow it too.
    axis: 'x',
    // Degrees between neighbouring cards. 0 shares the ring evenly.
    step: 0,
    // true: a drag or a turn of the wheel that ends between two cards eases on
    // to one of them: the next one the way it went if it went far or fast
    // enough, else the nearer one.
    snap: false,
    // false: the ring runs from the first card to the last and stops at both
    // instead of coming round again. Cards are then laid one step apart without
    // wrapping, so more cards than fit once round can follow each other; only
    // those within a quarter turn of the front are drawn, so cards that share
    // a place never show together.
    loop: true,
  };

  // A press must travel this far (px) before it counts as a drag, so taps on a
  // card can still bring it to the front.
  const DRAG_THRESHOLD = 4;
  // How long (ms) an eased step takes: for arrow keys, taps on a card and a
  // snap. It slows to a stop, and stops outright, so a card that plays can
  // start as soon as the ring is there. With reduced motion a step is a jump.
  const STEP_DURATION = 420;
  // Flat strips a caption is bent into along the far wall.
  const CAPTION_STRIPS = 16;
  // Latitude (rad) the wall's borders are traced to, and how finely.
  const WALL_LATITUDE = 1.48;
  const WALL_SAMPLES = 24;
  // Stops closer than this (stage widths) count as the same place.
  const STOP_EPSILON = 1e-6;
  // With snap, a drag that has gone this share of the way to the next card, or
  // flicks the ring this many cards a second, goes on to it. A turn of the
  // wheel goes on however small it was (a notch can be a few pixels, or half
  // that on a page zoomed in), but never past the next card.
  const SNAP_PUSH = 0.18;
  const SNAP_FLICK = 1.5;
  // A card turned away while it plays goes to the end of its beat (on) or the
  // start (back) over this share of a turn.
  const PLAY_BLEND = 0.15;

  const reducedMotion = global.matchMedia('(prefers-reduced-motion: reduce)');

  const mod = (n, m) => ((n % m) + m) % m;
  const clamp = (n, lo, hi) => Math.min(hi, Math.max(lo, n));
  // Shortest signed rotation, folded into [-180, 180].
  const shortest = (deg) => deg - 360 * Math.round(deg / 360);
  const rad = (deg) => (deg * Math.PI) / 180;

  class Carousel3D {
    constructor(root, options = {}) {
      this.root = root;
      this.ring = root.querySelector('.carousel__ring');
      this.cards = Array.from(this.ring.children);
      this.options = Object.assign({}, DEFAULTS, options);
      this.vertical = this.options.axis === 'y';
      this.count = this.cards.length;
      this.step = this.options.step || 360 / this.count;
      // Seconds each card plays its beat for (data-play), 0 for none; a card
      // that plays does not also hold the ring.
      this.plays = this.cards.map((card) => Math.max(0, parseFloat(card.dataset.play) || 0));
      this.holds = this.cards.map((card, i) => (this.plays[i] ? 0 : Math.max(0, parseFloat(card.dataset.hold) || 0)));
      // How far each card has played, the card the ring last came to rest at,
      // and whether its end has been announced (carousel:played).
      this.played = this.cards.map(() => 0);
      this.current = -1;
      this.announced = true;
      this.clock = null;
      this.shown = NaN;

      // Where the ring is along its track, in stage widths of drag from the
      // first card's rest (see layTrack). The angle follows from it.
      this.travel = 0;
      this.angle = 0; // ring rotation in degrees; positive moves the front card right
      this.index = -1; // card currently at the front
      this.width = 0;
      this.height = 0;
      this.radius = 0;
      this.perspective = 0;
      this.frame = 0;
      this.drag = null;
      this.motion = null;
      this.suppressClick = false;
      this.wheelEnd = 0;

      this.tick = this.tick.bind(this);
      this.onPointerDown = this.onPointerDown.bind(this);
      this.onPointerMove = this.onPointerMove.bind(this);
      this.onPointerUp = this.onPointerUp.bind(this);
      this.onPointerCancel = this.onPointerCancel.bind(this);
      this.onPointerLeave = this.onPointerLeave.bind(this);
      this.onClick = this.onClick.bind(this);
      this.onKeyDown = this.onKeyDown.bind(this);
      this.onWheel = this.onWheel.bind(this);

      // The far wall is painted on a canvas behind the 3D scene.
      this.wall = document.createElement('canvas');
      this.wall.className = 'carousel__wall';
      this.wall.setAttribute('aria-hidden', 'true');
      root.insertBefore(this.wall, root.firstChild);
      this.paint = this.wall.getContext('2d');

      root.setAttribute('role', 'region');
      root.setAttribute('aria-roledescription', 'carousel');
      this.bands = this.cards.map((card) => card.dataset.band || 'transparent');
      this.faces = this.cards.map((card) => card.querySelector('.carousel__face'));
      this.captions = this.cards.map((card) => this.bend(card.querySelector('.carousel__caption'), card));
      this.away = this.cards.map(() => null);
      this.cards.forEach((card, i) => {
        card.style.setProperty('--carousel-i', i);
        card.setAttribute('role', 'group');
        card.setAttribute('aria-roledescription', 'slide');
        card.setAttribute('aria-label', `${i + 1} of ${this.count}`);
      });
      this.layTrack();

      root.addEventListener('pointerdown', this.onPointerDown);
      root.addEventListener('pointermove', this.onPointerMove);
      root.addEventListener('pointerup', this.onPointerUp);
      root.addEventListener('pointercancel', this.onPointerCancel);
      root.addEventListener('pointerleave', this.onPointerLeave);
      root.addEventListener('click', this.onClick, true);
      root.addEventListener('keydown', this.onKeyDown);
      root.addEventListener('dragstart', preventDefault);
      if (this.vertical) {
        // Up and down belong to the ring now; sideways pans stay with the page.
        root.style.touchAction = 'pan-x';
        root.addEventListener('wheel', this.onWheel, { passive: false });
      }

      this.resizeObserver = new ResizeObserver(() => this.measure());
      this.resizeObserver.observe(root);
      this.measure();
    }

    // The track a drag moves along, in stage widths: the ring rests at each
    // card for its hold, then turns on to the next card over `swipe`.
    layTrack() {
      const { swipe } = this.options;
      let at = 0;
      this.rests = this.holds.map((hold) => {
        const rest = { start: at, end: at + hold };
        at += hold + swipe;
        return rest;
      });
      // A lap comes back to where the first card's rest begins; a ring that
      // does not loop ends with the last card's rest.
      this.lap = at;
      this.end = at - swipe;
    }

    // Cards turned from the first card (fractional) at a point on the track.
    positionAt(travel) {
      let lap = 0;
      let t = clamp(travel, 0, this.end);
      if (this.options.loop) {
        lap = Math.floor(travel / this.lap);
        t = travel - lap * this.lap;
      }
      let k = 0;
      while (k < this.count - 1 && t >= this.rests[k + 1].start) k++;
      return lap * this.count + k + clamp((t - this.rests[k].end) / this.options.swipe, 0, 1);
    }

    // Card i's rest nearest to where the ring is on its track (on a looping
    // ring each lap has its own).
    restOf(i) {
      const rest = this.rests[i];
      if (!this.options.loop) return rest;
      const shift = this.lap * Math.round((this.travel - (rest.start + rest.end) / 2) / this.lap);
      return { start: rest.start + shift, end: rest.end + shift };
    }

    // The rest of the k-th card along the track (laps and all on a ring that
    // loops; one that does not stops at its first and last card).
    restAt(k) {
      if (!this.options.loop) return this.rests[clamp(k, 0, this.count - 1)];
      const rest = this.rests[mod(k, this.count)];
      const shift = this.lap * Math.floor(k / this.count);
      return { start: rest.start + shift, end: rest.end + shift };
    }

    // How far card i's beat has run: 0 until the ring reaches the card, then
    // rising to 1 over its rest, and 1 once the ring has turned on past it.
    // A card that plays (data-play) shows how far it has played while the
    // ring rests at it, and goes to the end of its beat as the ring turns on
    // from it, or to the start as it turns back.
    beat(i) {
      if (this.plays[i]) {
        let d = this.positionAt(this.travel) - i;
        if (this.options.loop) d -= this.count * Math.round(d / this.count);
        if (i !== this.current) return d > 0 ? 1 : 0;
        const p = this.played[i];
        if (Math.abs(d) < STOP_EPSILON) return p;
        const w = clamp(Math.abs(d) / PLAY_BLEND, 0, 1);
        return p + ((d > 0 ? 1 : 0) - p) * w * w * (3 - 2 * w);
      }
      const { start, end } = this.restOf(i);
      const t = this.options.loop ? this.travel : clamp(this.travel, 0, this.end);
      if (end > start) return clamp((t - start) / (end - start), 0, 1);
      return t >= start - STOP_EPSILON ? 1 : 0;
    }

    // Card i's offset from the front in degrees, as drawn: 0 at the front,
    // positive to the right. Its face turns -(1 - turn) times that about its
    // own upright middle.
    offset(i) {
      const at = i * this.step + this.angle;
      return this.options.loop ? shortest(at) : at;
    }

    // CSS cannot bend an element, so a caption becomes a run of narrow flat
    // strips along the far wall, each showing its own slice of a copy. The
    // original stays in the first strip for assistive technology.
    bend(caption, card) {
      if (!caption) return null;
      for (let j = 0; j < CAPTION_STRIPS; j++) {
        const strip = document.createElement('div');
        strip.className = 'carousel__strip';
        strip.style.setProperty('--carousel-j', j);
        const content = j === 0 ? caption : caption.cloneNode(true);
        if (j > 0) content.setAttribute('aria-hidden', 'true');
        strip.appendChild(content);
        card.appendChild(strip);
      }
      return { el: caption, card };
    }

    measure() {
      const width = this.root.clientWidth;
      const height = this.root.clientHeight;
      const face = this.faces.find(Boolean);
      const cardWidth = face ? face.offsetWidth : 0;
      if (!width || !height || !cardWidth) return;
      this.width = width;
      this.height = height;
      this.perspective = height / 2 / Math.tan(rad(this.options.fov) / 2);
      this.radius = this.options.radius * cardWidth;
      this.root.style.perspective = `${this.perspective}px`;
      this.root.style.setProperty('--carousel-radius', `${this.radius}px`);
      this.root.style.setProperty('--carousel-step', `${this.step}deg`);
      this.root.style.setProperty('--carousel-strips', CAPTION_STRIPS);

      // The far wall stands two radii behind the front card and looks that
      // much smaller, so captions are laid out at the size they should appear,
      // grown by the inverse, and placed so their middle lands at
      // --carousel-caption-top (both custom properties are stage percentages,
      // and a card can set its own).
      const grow = (this.perspective + 2 * this.radius) / this.perspective;
      for (const caption of this.captions) {
        if (!caption) continue;
        const styles = getComputedStyle(caption.card);
        const top = (parseFloat(styles.getPropertyValue('--carousel-caption-top')) || 25) / 100;
        const across = ((parseFloat(styles.getPropertyValue('--carousel-caption-width')) || 86) / 100) * width;
        const strip = (across * grow) / CAPTION_STRIPS;
        const style = caption.card.style;
        style.setProperty('--carousel-caption-w', `${across}px`);
        const h = caption.el.offsetHeight * grow;
        const middle = height / 2 + (top * height - height / 2) * grow;
        style.setProperty('--carousel-strip', `${strip}px`);
        style.setProperty('--carousel-strip-angle', `${(2 * Math.atan(strip / 2 / this.radius) * 180) / Math.PI}deg`);
        style.setProperty('--carousel-caption-h', `${h}px`);
        style.setProperty('--carousel-caption-y', `${middle - h / 2}px`);
        style.setProperty('--carousel-caption-grow', grow);
      }

      const ratio = Math.min(global.devicePixelRatio || 1, 2);
      this.wall.width = Math.round(width * ratio);
      this.wall.height = Math.round(height * ratio);
      this.paint.setTransform(ratio, 0, 0, ratio, 0, 0);
      this.render();
    }

    render() {
      const { step, count } = this;
      this.angle = -this.positionAt(this.travel) * step;
      // Pushing the ring back by its radius leaves the front card on the
      // screen plane and the camera outside the ring, not at its centre.
      this.ring.style.transform = `translateZ(${-this.radius}px) rotateY(${this.angle}deg)`;

      for (let i = 0; i < count; i++) {
        const at = this.offset(i); // 0 at the front
        this.cards[i].style.setProperty('--carousel-turn', `${-(1 - this.options.turn) * at}deg`);
        // Past a quarter turn the face is behind the ring and the caption in
        // front of it; neither should show, so neither is drawn.
        const away = Math.abs(at) > 90;
        if (away !== this.away[i]) {
          this.cards[i].classList.toggle('is-away', away);
          this.away[i] = away;
        }
      }

      const nearest = Math.round(-this.angle / step);
      const index = this.options.loop ? mod(nearest, count) : clamp(nearest, 0, count - 1);
      if (index !== this.index) {
        if (this.index >= 0) {
          this.cards[this.index].classList.remove('is-front');
          this.cards[this.index].removeAttribute('aria-current');
        }
        this.cards[index].classList.add('is-front');
        this.cards[index].setAttribute('aria-current', 'true');
        this.index = index;
      }
      this.paintWall();
      this.shown = this.travel;
      // At rest at a card the ring has come to that card. Away from it, the
      // card it last came to is announced again once the ring rests there.
      const rest = this.restingAt();
      if (rest >= 0) this.arrive(rest);
      else this.announced = false;
      this.emit('carousel:move');
    }

    // The card the ring rests at, or -1 while a drag or a step moves it or it
    // stands between two cards.
    restingAt() {
      if (this.motion || (this.drag && this.drag.active)) return -1;
      const at = this.positionAt(this.travel);
      const card = Math.round(at);
      return Math.abs(at - card) < STOP_EPSILON ? mod(card, this.count) : -1;
    }

    // The ring has come to rest at card i. Coming on to it, the card plays
    // from the start; coming back to it, it shows its end, as it was left; at
    // the card it was already at, it goes on where it was.
    arrive(i) {
      if (i === this.current) return;
      const ahead = this.current < 0 || shortest((i - this.current) * this.step) > 0;
      this.current = i;
      this.played[i] = ahead && this.plays[i] ? 0 : 1;
      this.announced = false;
      this.clock = null;
      this.schedule();
    }

    // Whether the card the ring rests at is playing its beat now.
    playing() {
      const i = this.current;
      return i >= 0 && this.played[i] < 1 && this.restingAt() === i;
    }

    // The far wall is the inside of a sphere around the ring's centre. Its
    // borders are meridians, so they bow as they sweep across, and its middle
    // recedes while its sides come closer.
    paintWall() {
      const { paint, width, height, perspective: p, radius: r, step } = this;
      if (!width) return;
      const distance = p + r;
      // Longitudes either side of the back that are still on the far side.
      const reach = Math.min(75, 180 - (Math.acos(r / distance) * 180) / Math.PI - 2);
      const trace = (offset, upward) => {
        const sin = -Math.sin(rad(offset));
        const cos = -Math.cos(rad(offset));
        for (let k = 0; k <= WALL_SAMPLES; k++) {
          const lat = WALL_LATITUDE * (upward ? 1 - (2 * k) / WALL_SAMPLES : (2 * k) / WALL_SAMPLES - 1);
          const x = r * Math.cos(lat) * sin;
          const y = r * Math.sin(lat);
          const z = r * Math.cos(lat) * cos;
          const scale = p / (distance - z);
          paint.lineTo(width / 2 + x * scale, height / 2 - y * scale);
        }
      };
      paint.fillStyle = this.bands[this.index];
      paint.fillRect(0, 0, width, height);
      for (let i = 0; i < this.count; i++) {
        // Each band sits opposite its card, so its offset from the back of the
        // ring equals the card's offset from the front.
        const middle = this.offset(i);
        if (Math.abs(middle) - step / 2 >= reach) continue;
        paint.beginPath();
        trace(clamp(middle - step / 2, -reach, reach), false);
        trace(clamp(middle + step / 2, -reach, reach), true);
        paint.closePath();
        paint.fillStyle = this.bands[i];
        paint.fill();
      }
    }

    schedule() {
      if (!this.frame) this.frame = requestAnimationFrame(this.tick);
    }

    tick() {
      this.frame = 0;
      let settled = false;
      const now = performance.now();
      if (this.motion) {
        const { from, target, duration, start } = this.motion;
        const u = duration > 0 ? clamp((now - start) / duration, 0, 1) : 1;
        this.travel = from + (target - from) * (1 - (1 - u) ** 3);
        if (u >= 1) {
          this.travel = target;
          this.motion = null;
          settled = true;
        }
      }
      // The card at rest plays on by the time since the last frame (a long
      // gap, as when the page was hidden, counts for a quarter second). Each
      // frame it plays is one of the ring's, from the first (when its clock
      // starts), so a layer drawn on carousel:move and on frames of its own
      // in between still draws once a frame as the play starts.
      const playing = this.playing();
      if (playing) {
        const i = this.current;
        if (this.clock !== null) {
          const share = this.plays[i] ? Math.min(250, now - this.clock) / 1000 / this.plays[i] : 1;
          this.played[i] = Math.min(1, this.played[i] + share);
        }
        this.clock = now;
      } else {
        this.clock = null;
      }
      if (this.travel !== this.shown || settled) this.render();
      else if (playing) this.emit('carousel:move');
      if (this.motion || this.playing()) this.schedule();
      if (settled) this.emit('carousel:settle');
      if (!this.announced && this.played[this.current] >= 1 && this.restingAt() === this.current) {
        this.announced = true;
        this.emit('carousel:played');
      }
    }

    // Eases on from where a drag or the wheel started (at, cards along the
    // track) to a card: the next one the way the ring went if it went far
    // enough or was flicked (velocity, cards a second), else the nearer one.
    snapFrom(from, velocity = 0, push = SNAP_PUSH) {
      const at = this.positionAt(this.travel);
      const moved = at - from;
      let card = Math.round(at);
      const way = Math.abs(moved) >= push ? Math.sign(moved) : Math.abs(velocity) >= SNAP_FLICK && Math.sign(velocity) === Math.sign(moved) ? Math.sign(moved) : 0;
      if (way > 0) card = Math.max(card, Math.floor(from + STOP_EPSILON) + 1);
      if (way < 0) card = Math.min(card, Math.ceil(from - STOP_EPSILON) - 1);
      this.goTo(card);
    }

    // Eases along the track to a point on it (at once if it is already
    // there, so a card that plays is not held up by a step that goes nowhere).
    animateTo(target) {
      if (!this.options.loop) target = clamp(target, 0, this.end);
      const duration = reducedMotion.matches || Math.abs(target - this.travel) < STOP_EPSILON ? 0 : STEP_DURATION;
      this.motion = { from: this.travel, target, duration, start: performance.now() };
      this.schedule();
    }

    // Brings card i to the front, to the nearest point of its rest, so a
    // card whose beat is under way is left as it is.
    goTo(index) {
      const i = this.options.loop ? mod(index, this.count) : clamp(index, 0, this.count - 1);
      const { start, end } = this.restOf(i);
      this.animateTo(clamp(this.travel, start, end));
    }

    // The places a step can stop at: both ends of every rest, in track order.
    stops() {
      const stops = [];
      for (const { start, end } of this.rests) {
        stops.push(start);
        if (end - start > STOP_EPSILON) stops.push(end);
      }
      return stops;
    }

    // Step on to the next stop in the drag direction (positive = as if
    // dragged right, or down on an upright ring): a card, or the start or end
    // of a card's beat. From between two stops, the nearer one that way counts
    // as the first step.
    rotateBy(cards) {
      // Chain from the pending target so repeated key presses add up.
      let target = this.motion ? this.motion.target : this.travel;
      // Dragging right turns the ring back along the track.
      const way = -Math.sign(cards * this.options.direction);
      const stops = this.stops();
      for (let n = 0; n < Math.abs(cards); n++) {
        const lap = this.options.loop ? Math.floor(target / this.lap) * this.lap : 0;
        // Stops of this lap and the next and previous, so a step can cross
        // where a lap starts.
        const near = this.options.loop ? [-1, 0, 1].flatMap((l) => stops.map((s) => s + lap + l * this.lap)) : stops;
        const next = way > 0 ? near.find((s) => s > target + STOP_EPSILON) : [...near].reverse().find((s) => s < target - STOP_EPSILON);
        if (next === undefined) break;
        target = next;
      }
      this.animateTo(target);
    }

    // Moves along the track by a number of stage widths.
    moveBy(widths) {
      const travel = this.travel + widths;
      this.travel = this.options.loop ? travel : clamp(travel, 0, this.end);
    }

    // Where a pointer is along the drag's axis.
    along(event) {
      return this.vertical ? event.clientY : event.clientX;
    }

    onPointerDown(event) {
      if (this.drag || (event.pointerType === 'mouse' && event.button !== 0)) return;
      const wasMoving = Boolean(this.motion);
      // Grabbing the ring mid-step stops it where it is (see onPointerUp for
      // where it goes with snap on).
      const heading = this.motion ? this.motion.target : this.travel;
      this.motion = null;
      this.suppressClick = false;
      this.drag = {
        id: event.pointerId,
        start: this.along(event),
        last: this.along(event),
        active: false,
        wasMoving,
        heading,
        card: event.target.closest('.carousel__card'),
        // Where the ring was along its track, in cards, and lately (for a flick).
        from: this.positionAt(this.travel),
        trail: [],
      };
    }

    onPointerMove(event) {
      const drag = this.drag;
      if (!drag || event.pointerId !== drag.id || !this.width) return;
      const at = this.along(event);
      if (!drag.active) {
        if (Math.abs(at - drag.start) < DRAG_THRESHOLD) return;
        drag.active = true;
        this.root.setPointerCapture(drag.id);
        this.root.classList.add('is-dragging');
      }
      // Moves are added up one by one rather than measured from the start, so
      // a drag that ran past either end of a track that does not loop turns
      // back as soon as the finger does. Left, or up, goes on.
      this.moveBy((-(at - drag.last) / this.width) * this.options.direction);
      drag.last = at;
      drag.trail.push([performance.now(), this.positionAt(this.travel)]);
      if (drag.trail.length > 6) drag.trail.shift();
      this.schedule();
    }

    // How fast a drag was turning the ring as it ended, in cards a second.
    flick(drag) {
      const now = performance.now();
      const recent = drag.trail.filter(([t]) => now - t < 120);
      if (recent.length < 2) return 0;
      const [t0, p0] = recent[0], [t1, p1] = recent[recent.length - 1];
      return t1 > t0 ? ((p1 - p0) * 1000) / (t1 - t0) : 0;
    }

    // An upright ring follows the wheel and trackpad as a page scrolls, and
    // lets the page scroll on once its track has run out that way.
    onWheel(event) {
      if (!this.width || (this.drag && this.drag.active)) return;
      const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? this.height : 1;
      const widths = ((event.deltaY * unit) / this.width) * this.options.direction;
      const ended = !this.options.loop && ((widths < 0 && this.travel <= 0) || (widths > 0 && this.travel >= this.end));
      if (!widths || ended) return;
      event.preventDefault();
      if (!this.wheelEnd) this.wheelFrom = this.positionAt(this.travel);
      this.motion = null;
      this.moveBy(widths);
      if (this.options.snap) {
        // One turn of the wheel reaches the next card at most, so a long
        // trackpad flick stops there rather than flying past a card.
        const from = this.wheelFrom;
        this.travel = clamp(this.travel, this.restAt(Math.ceil(from - STOP_EPSILON) - 1).start, this.restAt(Math.floor(from + STOP_EPSILON) + 1).end);
      }
      this.schedule();
      clearTimeout(this.wheelEnd);
      this.wheelEnd = setTimeout(() => {
        this.wheelEnd = 0;
        if (this.options.snap) this.snapFrom(this.wheelFrom, 0, STOP_EPSILON);
        else this.emit('carousel:settle');
      }, 150);
    }

    onPointerUp(event) {
      const drag = this.drag;
      if (!drag || event.pointerId !== drag.id) return;
      this.drag = null;
      if (drag.active) {
        this.root.classList.remove('is-dragging');
        this.suppressClick = true;
        if (this.options.snap) this.snapFrom(drag.from, this.flick(drag));
        else this.emit('carousel:settle');
      } else if (drag.card && !drag.wasMoving) {
        this.goTo(this.cards.indexOf(drag.card));
      } else if (drag.wasMoving) {
        this.release(drag);
      }
    }

    // A press that caught the ring mid-step and let it go without a drag
    // leaves it where it is, or with snap on, where it would otherwise rest
    // between two cards, lets the step go on to where it was going.
    release(drag) {
      if (this.options.snap) this.animateTo(drag.heading);
      else this.emit('carousel:settle');
    }

    onPointerCancel(event) {
      const drag = this.drag;
      if (!drag || event.pointerId !== drag.id) return;
      this.drag = null;
      this.root.classList.remove('is-dragging');
      if (this.options.snap && drag.active) this.snapFrom(drag.from);
      else if (drag.wasMoving) this.release(drag);
      else this.emit('carousel:settle');
    }

    // A press that wanders off before it becomes a drag is not captured, so
    // its pointerup would never arrive here; drop it rather than leave it pending.
    onPointerLeave(event) {
      const drag = this.drag;
      if (!drag || drag.active || event.pointerId !== drag.id) return;
      this.drag = null;
      if (drag.wasMoving) this.release(drag);
    }

    // A drag that ends over a link or button must not also activate it.
    onClick(event) {
      if (!this.suppressClick) return;
      this.suppressClick = false;
      event.preventDefault();
      event.stopPropagation();
    }

    // The keys along the drag's axis step the way the finger would push: on an
    // upright ring, down goes on as scrolling down does.
    onKeyDown(event) {
      if (this.drag && this.drag.active) return;
      const keys = this.vertical ? { ArrowUp: 1, PageUp: 1, ArrowDown: -1, PageDown: -1 } : { ArrowRight: 1, ArrowLeft: -1 };
      const cards = keys[event.key];
      if (!cards) return;
      event.preventDefault();
      this.rotateBy(cards);
    }

    emit(type) {
      this.root.dispatchEvent(new CustomEvent(type, { detail: { angle: this.angle, index: this.index, travel: this.travel } }));
    }

    destroy() {
      cancelAnimationFrame(this.frame);
      clearTimeout(this.wheelEnd);
      this.resizeObserver.disconnect();
      const root = this.root;
      root.removeEventListener('pointerdown', this.onPointerDown);
      root.removeEventListener('pointermove', this.onPointerMove);
      root.removeEventListener('pointerup', this.onPointerUp);
      root.removeEventListener('pointercancel', this.onPointerCancel);
      root.removeEventListener('pointerleave', this.onPointerLeave);
      root.removeEventListener('click', this.onClick, true);
      root.removeEventListener('keydown', this.onKeyDown);
      root.removeEventListener('dragstart', preventDefault);
      root.removeEventListener('wheel', this.onWheel);
    }
  }

  function preventDefault(event) {
    event.preventDefault();
  }

  global.Carousel3D = Carousel3D;
})(window);

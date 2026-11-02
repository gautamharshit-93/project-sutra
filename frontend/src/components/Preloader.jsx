import { Component, useLayoutEffect, useRef, useState } from 'react';
import gsap from 'gsap';

const SEEN_KEY = 'sutra.preloader.seen';

/** Threads (a "sutra") drawn in from the edges, woven into the hex-nut mark; then the screen parts like a curtain. */
const THREADS = [
  'M-40 120 C 180 60, 300 260, 500 300',
  'M1040 90 C 820 140, 720 240, 500 300',
  'M-40 520 C 200 560, 330 360, 500 300',
  'M1040 540 C 800 500, 690 380, 500 300',
  'M500 -40 C 470 120, 540 180, 500 300',
  'M500 640 C 540 480, 460 420, 500 300',
];

function shouldShow() {
  try {
    if (sessionStorage.getItem(SEEN_KEY)) return false;
  } catch { /* storage blocked: show it */ }
  return !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
}

const SHOW = typeof window !== 'undefined' && shouldShow();
let markDone;
/** Resolves when the preloader has finished (at once when it is not shown). Heavy work waits for it. */
export const preloaderDone = new Promise((resolve) => { markDone = resolve; });
if (!SHOW) markDone();

/** Shown once per browser session, over the app while it boots. */
function PreloaderScreen() {
  const [visible, setVisible] = useState(SHOW);
  const root = useRef(null);

  useLayoutEffect(() => {
    if (!visible) return undefined;
    const counter = { value: 0 };
    const ctx = gsap.context(() => {
      const threads = gsap.utils.toArray('.pl-thread');
      threads.forEach((p) => {
        const length = p.getTotalLength();
        gsap.set(p, { strokeDasharray: length, strokeDashoffset: length });
      });
      const hex = root.current.querySelector('.pl-hex');
      const hexLength = hex.getTotalLength();
      gsap.set(hex, { strokeDasharray: hexLength, strokeDashoffset: hexLength });
      const s = root.current.querySelector('.pl-s');
      const sLength = s.getTotalLength();
      gsap.set(s, { strokeDasharray: sLength, strokeDashoffset: sLength });
      // Held directly: the cleanup's revert replays onUpdate after unmount, when root.current is already null
      const countEl = root.current.querySelector('.pl-count');

      const tl = gsap.timeline({
        defaults: { ease: 'power3.inOut' },
        onComplete: () => {
          try { sessionStorage.setItem(SEEN_KEY, '1'); } catch { /* ignore */ }
          setVisible(false);
          markDone();
        },
      });
      tl.to(threads, { strokeDashoffset: 0, duration: 1.1, stagger: 0.07 })
        .to(counter, {
          value: 100, duration: 1.9, ease: 'power2.inOut',
          onUpdate: () => { countEl.textContent = `${Math.round(counter.value)}`; },
        }, 0)
        .to('.pl-bar-fill', { scaleX: 1, duration: 1.9, ease: 'power2.inOut' }, 0)
        .to(hex, { strokeDashoffset: 0, duration: 0.8 }, 0.75)
        .to('.pl-face', { scale: 1, opacity: 1, duration: 0.5, ease: 'back.out(2)' }, 1.2)
        .to(s, { strokeDashoffset: 0, duration: 0.6, ease: 'power2.out' }, 1.35)
        .to(threads, { opacity: 0, duration: 0.4 }, 1.5)
        .fromTo('.pl-letter', { yPercent: 110 }, { yPercent: 0, duration: 0.6, stagger: 0.05, ease: 'power4.out' }, 1.3)
        .to('.pl-mark', { rotate: 360, duration: 0.9, ease: 'power2.inOut', transformOrigin: '50% 50%' }, 1.6)
        .to('.pl-meta', { opacity: 0, duration: 0.25 }, 2.1)
        .to('.pl-center', { scale: 0.85, opacity: 0, duration: 0.45, ease: 'power2.in' }, 2.35)
        .to('.pl-panel-top', { yPercent: -100, duration: 0.8, ease: 'expo.inOut' }, 2.5)
        .to('.pl-panel-bottom', { yPercent: 100, duration: 0.8, ease: 'expo.inOut' }, 2.5);
    }, root);
    return () => ctx.revert();
  }, [visible]);

  if (!visible) return null;
  return (
    <div className="preloader" ref={root} aria-hidden="true">
      <div className="pl-panel pl-panel-top" />
      <div className="pl-panel pl-panel-bottom" />
      <svg className="pl-threads" viewBox="0 0 1000 600" preserveAspectRatio="xMidYMid slice">
        {THREADS.map((d) => <path key={d} className="pl-thread" d={d} />)}
      </svg>
      <div className="pl-center">
        <svg className="pl-mark" viewBox="0 0 64 64" width="120" height="120">
          <polygon className="pl-hex" points="18,6 46,6 60,32 46,58 18,58 4,32" />
          <circle className="pl-face" cx="32" cy="32" r="18.5" />
          <path className="pl-s" d="M40.5 23.5c-2.4-3.2-14-4.4-15 1.6-1 6.4 16.4 5.2 15.2 12.4-1 6-12.8 5.6-16.2 1.4" />
        </svg>
        <div className="pl-word">
          {'SUTRA'.split('').map((ch, i) => <span key={i} className="pl-letter-wrap"><span className="pl-letter">{ch}</span></span>)}
        </div>
        <div className="pl-meta">
          <span>Weaving vendor threads</span>
          <span><span className="pl-count">0</span>%</span>
        </div>
        <div className="pl-bar pl-meta"><span className="pl-bar-fill" /></div>
      </div>
    </div>
  );
}

/** The preloader is decoration: if it ever fails, drop it and let the app through instead of blanking the page. */
export default class Preloader extends Component {
  constructor(props) {
    super(props);
    this.state = { failed: false };
  }

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch() {
    markDone();
  }

  render() {
    return this.state.failed ? null : <PreloaderScreen />;
  }
}

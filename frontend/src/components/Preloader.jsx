import { Component, useLayoutEffect, useRef, useState } from 'react';
import gsap from 'gsap';

const SEEN_KEY = 'sutra.preloader.seen';

/** What the counter is "doing", shown small at the bottom left while the big percentage runs. */
const STEPS = ['Loading RFQs', 'Reading quotations', 'Checking vendors', 'Ready'];

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

/**
 * Shown once per browser session over the app while it boots: a black screen, the SUTRA mark at the top, and a
 * very large percentage counting up at the bottom right. At 100 the number lifts away and the screen slides up.
 */
function PreloaderScreen() {
  const [visible, setVisible] = useState(SHOW);
  const root = useRef(null);

  useLayoutEffect(() => {
    if (!visible) return undefined;
    const counter = { value: 0 };
    const ctx = gsap.context(() => {
      // Held directly: the cleanup's revert replays onUpdate after unmount, when root.current is already null
      const countEl = root.current.querySelector('.pl-num');
      const stepEl = root.current.querySelector('.pl-step');
      let step = -1;

      const tl = gsap.timeline({
        onComplete: () => {
          try { sessionStorage.setItem(SEEN_KEY, '1'); } catch { /* ignore */ }
          setVisible(false);
          markDone();
        },
      });
      tl.from('.pl-top > *', { y: 24, opacity: 0, duration: 0.6, stagger: 0.08, ease: 'power3.out' })
        .from('.pl-count', { yPercent: 40, opacity: 0, duration: 0.7, ease: 'power3.out' }, 0.1)
        .to(counter, {
          value: 100, duration: 2.1, ease: 'power2.inOut',
          onUpdate: () => {
            const v = Math.round(counter.value);
            countEl.textContent = String(v).padStart(2, '0');
            const next = Math.min(STEPS.length - 1, Math.floor((v / 100) * STEPS.length));
            if (next !== step) { step = next; stepEl.textContent = STEPS[next]; }
          },
        }, 0.15)
        .to('.pl-line-fill', { scaleX: 1, duration: 2.1, ease: 'power2.inOut' }, 0.15)
        .to('.pl-count', { yPercent: -110, opacity: 0, duration: 0.55, ease: 'power3.in' }, '+=0.15')
        .to('.pl-top, .pl-foot', { opacity: 0, duration: 0.35 }, '<')
        .to(root.current, { yPercent: -100, duration: 0.85, ease: 'expo.inOut' }, '-=0.1');
    }, root);
    return () => ctx.revert();
  }, [visible]);

  if (!visible) return null;
  return (
    <div className="preloader" ref={root} aria-hidden="true">
      <div className="pl-top">
        <span className="pl-brand">SUTRA</span>
        <span className="pl-tag">Vendor quotation management</span>
      </div>
      <div className="pl-foot">
        <span className="pl-step">{STEPS[0]}</span>
        <div className="pl-count"><span className="pl-num">00</span><span className="pl-pct">%</span></div>
      </div>
      <div className="pl-line"><span className="pl-line-fill" /></div>
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

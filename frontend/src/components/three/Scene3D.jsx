import { Component, Suspense, lazy, useEffect, useState } from 'react';
import { preloaderDone } from '../Preloader.jsx';

const loadScene = () => import('./NutScene.jsx');
const NutScene = lazy(loadScene);

function webglAvailable() {
  try {
    const canvas = document.createElement('canvas');
    return Boolean(window.WebGLRenderingContext && (canvas.getContext('webgl2') || canvas.getContext('webgl')));
  } catch {
    return false;
  }
}

const SUPPORTED = typeof window !== 'undefined' && webglAvailable();
// Download three.js while the preloader plays, so it is ready the moment the page is revealed
if (SUPPORTED) loadScene().catch(() => {});

/** Waits for the preloader and then for an idle moment, so the page around the scene is usable first. */
function whenIdle() {
  return preloaderDone.then(() => new Promise((resolve) => {
    if (window.requestIdleCallback) window.requestIdleCallback(() => resolve(), { timeout: 600 });
    else setTimeout(resolve, 120);
  }));
}

/** Falls back to the flat decoration when WebGL fails at runtime. */
class SceneBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { failed: false };
  }

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}

/**
 * The 3D hex-nut scene, loaded on demand (three.js stays out of the main bundle) and mounted only once the page is
 * idle, so forms next to it respond immediately. Without WebGL, e.g. in tests or old browsers, a static ring
 * decoration is shown instead.
 */
export default function Scene3D({ compact = false, className = '' }) {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    if (!SUPPORTED) return undefined;
    let alive = true;
    whenIdle().then(() => alive && setReady(true));
    return () => { alive = false; };
  }, []);

  const fallback = <div className="scene-fallback" aria-hidden="true"><span /><span /><span /></div>;
  return (
    <div className={`scene3d ${className}`} aria-hidden="true">
      {ready ? (
        <SceneBoundary fallback={fallback}>
          <Suspense fallback={fallback}><NutScene compact={compact} /></Suspense>
        </SceneBoundary>
      ) : fallback}
    </div>
  );
}

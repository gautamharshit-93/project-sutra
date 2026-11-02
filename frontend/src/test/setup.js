import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(() => {
  cleanup();
  sessionStorage.clear();
});

// jsdom has no WebGL; returning null (instead of its "not implemented" error) lets the 3D scene fall back quietly.
HTMLCanvasElement.prototype.getContext = () => null;

import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// Make the animation library throw, as a broken browser API would
vi.mock('gsap', () => ({ default: { context: () => { throw new Error('animation unavailable'); } } }));

describe('preloader', () => {
  it('never takes the app down when its animation fails', async () => {
    const { default: Preloader, preloaderDone } = await import('./Preloader.jsx');
    vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<><Preloader /><p>App content</p></>);

    expect(screen.getByText('App content')).toBeInTheDocument();
    expect(document.querySelector('.preloader')).toBeNull();
    await expect(preloaderDone).resolves.toBeUndefined();
  });
});

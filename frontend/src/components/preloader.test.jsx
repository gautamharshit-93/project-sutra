import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import Preloader, { preloaderDone } from './Preloader.jsx';

describe('preloader', () => {
  it('never takes the app down when its animation fails', async () => {
    // jsdom has no SVG geometry (getTotalLength), so the animation throws as soon as it starts
    render(<><Preloader /><p>App content</p></>);

    expect(screen.getByText('App content')).toBeInTheDocument();
    expect(document.querySelector('.preloader')).toBeNull();
    await expect(preloaderDone).resolves.toBeUndefined();
  });
});

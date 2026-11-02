import { ROUTER_FUTURE } from '../routerFuture.js';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { tokenStore } from '../api/client.js';
import { AuthProvider } from '../auth/AuthContext.jsx';
import RequireAuth from '../auth/RequireAuth.jsx';
import { ToastProvider } from '../components/Toast.jsx';
import { buyer } from '../test/fixtures.js';
import LoginPage from './LoginPage.jsx';

vi.mock('../api/endpoints.js', () => ({
  authApi: { login: vi.fn(), me: vi.fn() },
}));
const { authApi } = await import('../api/endpoints.js');

function renderApp(initialPath) {
  return render(
    <MemoryRouter future={ROUTER_FUTURE} initialEntries={[initialPath]}>
      <ToastProvider>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/" element={<RequireAuth><p>Dashboard content</p></RequireAuth>} />
            <Route path="/users" element={<RequireAuth roles={['ADMIN']}><p>User admin</p></RequireAuth>} />
          </Routes>
        </AuthProvider>
      </ToastProvider>
    </MemoryRouter>,
  );
}

describe('authentication flow', () => {
  beforeEach(() => {
    vi.mocked(authApi.login).mockReset();
    vi.mocked(authApi.me).mockReset();
  });

  it('redirects signed-out users to the login page', () => {
    renderApp('/');
    expect(screen.getByRole('form', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.queryByText('Dashboard content')).not.toBeInTheDocument();
  });

  it('signs in, stores the token and opens the requested page', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.login).mockResolvedValue({ token: 'jwt-123', user: buyer });
    renderApp('/');

    await user.type(screen.getByLabelText(/Username/), ' buyer ');
    await user.type(screen.getByLabelText(/Password/), 'Passw0rd!');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(authApi.login).toHaveBeenCalledWith('buyer', 'Passw0rd!');
    expect(await screen.findByText('Dashboard content')).toBeInTheDocument();
    expect(tokenStore.get()).toBe('jwt-123');
  });

  it('shows the server message when sign-in fails', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.login).mockRejectedValue({ response: { status: 401, data: { message: 'Invalid username or password' } } });
    renderApp('/login');

    await user.type(screen.getByLabelText(/Username/), 'buyer');
    await user.type(screen.getByLabelText(/Password/), 'wrong');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password');
    expect(tokenStore.get()).toBeNull();
  });

  it('blocks pages the role may not open', async () => {
    tokenStore.set('jwt-123');
    vi.mocked(authApi.me).mockResolvedValue(buyer);
    renderApp('/users');

    expect(await screen.findByText(/Access denied/)).toBeInTheDocument();
    expect(screen.queryByText('User admin')).not.toBeInTheDocument();
  });

  it('drops an expired session', async () => {
    tokenStore.set('expired');
    vi.mocked(authApi.me).mockRejectedValue({ response: { status: 401 } });
    renderApp('/');

    expect(await screen.findByRole('form', { name: 'Sign in' })).toBeInTheDocument();
    expect(tokenStore.get()).toBeNull();
  });
});

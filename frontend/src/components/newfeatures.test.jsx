import { fireEvent, renderHook, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { toRequest } from '../utils/rfqDraft.js';

vi.mock('../api/endpoints.js', () => ({
  authApi: { login: vi.fn(), me: vi.fn(), changePassword: vi.fn() },
  reportingApi: { badges: vi.fn() },
  usersApi: { vendorSignups: vi.fn() },
  vendorPortalApi: { badges: vi.fn() },
}));
const { authApi, reportingApi, usersApi, vendorPortalApi } = await import('../api/endpoints.js');
const { useBadges } = await import('../hooks/useBadges.js');

describe('menu badges', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(reportingApi.badges).mockReset();
    vi.mocked(usersApi.vendorSignups).mockReset();
    vi.mocked(vendorPortalApi.badges).mockReset();
  });

  it('show an administrator RFQs to compare or award and vendor sign-ups to approve', async () => {
    vi.mocked(reportingApi.badges).mockResolvedValue({ rfqsToCompare: 2, awaitingAward: 1 });
    vi.mocked(usersApi.vendorSignups).mockResolvedValue([{ userId: 1 }, { userId: 2 }]);

    const { result } = renderHook(() => useBadges({ id: 1, roles: ['ADMIN'] }, '/'));

    await waitFor(() => expect(result.current).toEqual({ '/purchase-requests': 3, '/users': 2 }));
  });

  it('a buyer gets no sign-up count', async () => {
    vi.mocked(reportingApi.badges).mockResolvedValue({ rfqsToCompare: 1, awaitingAward: 0 });

    const { result } = renderHook(() => useBadges({ id: 2, roles: ['PROCUREMENT'] }, '/'));

    await waitFor(() => expect(result.current).toEqual({ '/purchase-requests': 1 }));
    expect(usersApi.vendorSignups).not.toHaveBeenCalled();
  });

  it('a vendor sees new outcomes until it opens My quotations', async () => {
    vi.mocked(vendorPortalApi.badges).mockResolvedValue({ openToQuote: 4, decidedQuotationIds: [7, 9] });
    const vendor = { id: 3, roles: ['VENDOR'] };

    const first = renderHook(() => useBadges(vendor, '/portal'));
    await waitFor(() => expect(first.result.current).toEqual({ '/portal': 4, '/portal/quotations': 2 }));
    first.unmount();

    const visit = renderHook(() => useBadges(vendor, '/portal/quotations'));
    await waitFor(() => expect(visit.result.current['/portal/quotations']).toBe(0));
  });
});

describe('RFQ request body', () => {
  it('carries the quotation due date', () => {
    const body = toRequest({ title: 'Steel', description: '', entityId: '4', subEntityId: '', customerId: '',
      currency: 'inr', requiredBy: '2026-12-15', quotationsDue: '2026-11-30' }, []);
    expect(body.request).toMatchObject({ currency: 'INR', requiredBy: '2026-12-15', quotationsDue: '2026-11-30' });
  });
});

describe('start-up password', () => {
  it('cannot be dismissed and signs the administrator in again with the new password', async () => {
    const { AuthProvider } = await import('../auth/AuthContext.jsx');
    const { ToastProvider } = await import('./Toast.jsx');
    const { default: ChangePasswordModal } = await import('./ChangePasswordModal.jsx');
    const admin = { id: 1, username: 'admin', fullName: 'Admin', roles: ['ADMIN'], passwordChangeRequired: true };
    vi.mocked(authApi.login).mockResolvedValue({ token: 't', user: { ...admin, passwordChangeRequired: false } });
    vi.mocked(authApi.changePassword).mockResolvedValue(undefined);
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<AuthProvider><ToastProvider><ChangePasswordModal required onClose={onClose} /></ToastProvider></AuthProvider>);

    expect(screen.getByText('Choose a new password')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel' })).toBeNull();
    expect(screen.getByText(/still using the start-up password/)).toBeInTheDocument();

    await user.type(screen.getByLabelText(/Current password/), 'Admin@123');
    await user.type(screen.getByLabelText(/^New password/), 'Chosen#Pass42');
    await user.type(screen.getByLabelText(/Confirm new password/), 'Chosen#Pass42');
    fireEvent.submit(document.getElementById('password-form'));

    await waitFor(() => expect(authApi.changePassword).toHaveBeenCalledWith('Admin@123', 'Chosen#Pass42'));
  });
});

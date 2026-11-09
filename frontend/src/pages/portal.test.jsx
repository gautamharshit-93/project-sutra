import { ROUTER_FUTURE } from '../routerFuture.js';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { parentOf } from '../components/BackButton.jsx';
import { navFor } from '../components/Layout.jsx';
import { ToastProvider } from '../components/Toast.jsx';
import { admin, approver, buyer } from '../test/fixtures.js';
import { homePath } from '../utils/permissions.js';
import { VendorRfqDetailPage, VendorRfqsPage } from './VendorPortalPages.jsx';

vi.mock('../api/endpoints.js', () => ({
  vendorPortalApi: { rfqs: vi.fn(), rfq: vi.fn(), submit: vi.fn(), withdraw: vi.fn(), quotations: vi.fn(), download: vi.fn() },
}));
vi.mock('../auth/AuthContext.jsx', () => ({
  useAuth: () => ({ user: { id: 9, username: 'brakeline', fullName: 'Brakeline Sales', roles: ['VENDOR'], vendorName: 'Brakeline Components' } }),
}));
const { vendorPortalApi } = await import('../api/endpoints.js');

const vendorUser = { id: 9, fullName: 'Brakeline Sales', roles: ['VENDOR'], vendorName: 'Brakeline Components' };
const labels = (user) => navFor(user).filter((i) => i.label).map((i) => i.label);

describe('menus follow the role', () => {
  it('shows each role only what it can use', () => {
    expect(labels(admin)).toEqual(['Dashboard', 'Entities', 'RFQs', 'Vendors', 'Customers', 'Audit log', 'Award register', 'Users & roles']);
    expect(labels(buyer)).toEqual(['Dashboard', 'Entities', 'RFQs', 'Vendors', 'Customers', 'Award register']);
    expect(labels(approver)).toEqual(['Dashboard', 'RFQs', 'Audit log', 'Award register']);
    expect(labels(vendorUser)).toEqual(['Open RFQs', 'My quotations', 'Company profile']);
  });

  it('sends vendors to their portal after sign-in', () => {
    expect(homePath(vendorUser)).toBe('/portal');
    expect(homePath(buyer)).toBe('/');
  });

  it('goes one level up when there is no history to go back to', () => {
    expect(parentOf('/quotations/12')).toBe('/purchase-requests');
    expect(parentOf('/purchase-requests/4')).toBe('/purchase-requests');
    expect(parentOf('/portal/rfqs/3')).toBe('/portal');
  });
});

const wrap = (path, element, route) => render(
  <MemoryRouter future={ROUTER_FUTURE} initialEntries={[path]}>
    <ToastProvider><Routes><Route path={route} element={element} /></Routes></ToastProvider>
  </MemoryRouter>,
);

const rfq = {
  id: 3, rfqNumber: 'PR-2026-00003', title: 'Brake components for Q1', description: 'Front axle', buyer: 'Axiom Motors',
  currency: 'INR', requiredBy: '2026-12-15', openForQuotations: true, publishedAt: '2026-11-01T08:00:00Z',
  items: [{ lineNumber: 1, itemCode: 'AP-001', description: 'Front brake pad set (ceramic)', quantity: 200, unitOfMeasure: 'Set', specifications: 'ECE R90' }],
  myQuotations: [],
};

describe('vendor portal', () => {
  beforeEach(() => Object.values(vendorPortalApi).forEach((fn) => fn.mockReset()));

  it('lists open RFQs and flags the ones still waiting for a quotation', async () => {
    vendorPortalApi.rfqs.mockResolvedValue({ content: [
      { id: 3, rfqNumber: 'PR-2026-00003', title: 'Brake components for Q1', buyer: 'Axiom Motors', currency: 'INR',
        itemCount: 4, openForQuotations: true, myQuotationId: null },
      { id: 1, rfqNumber: 'PR-2026-00001', title: 'Filters', buyer: 'Axiom Motors', currency: 'INR', itemCount: 2,
        openForQuotations: false, myQuotationId: 7, myQuotationStage: 'ACCEPTED_FOR_COMPARISON', outcome: 'AWARDED' },
    ] });
    wrap('/portal', <VendorRfqsPage />, '/portal');

    expect(await screen.findByRole('heading', { name: 'Welcome, Brakeline Components' })).toBeInTheDocument();
    const open = screen.getByText('Brake components for Q1').closest('a');
    expect(within(open).getByText('Quotation not sent yet')).toBeInTheDocument();
    const closed = screen.getByText('Filters').closest('a');
    expect(within(closed).getByText('Awarded to you')).toBeInTheDocument();
  });

  it('shows the requested items and sends the quotation files', async () => {
    vendorPortalApi.rfq.mockResolvedValue(rfq);
    vendorPortalApi.submit.mockResolvedValue({ id: 7 });
    wrap('/portal/rfqs/3', <VendorRfqDetailPage />, '/portal/rfqs/:id');

    expect(await screen.findByText('Front brake pad set (ceramic)')).toBeInTheDocument();
    const file = new File(['%PDF-1.7'], 'brakeline-quotation.pdf', { type: 'application/pdf' });
    await userEvent.upload(screen.getByLabelText(/Quotation files/, { selector: 'input' }), file);
    await userEvent.click(screen.getByRole('button', { name: 'Send quotation' }));

    expect(vendorPortalApi.submit).toHaveBeenCalledWith(3, [file], expect.any(Function));
  });

  it('shows the submitted quotation instead of the form, with withdraw while allowed', async () => {
    vendorPortalApi.rfq.mockResolvedValue({ ...rfq, myQuotations: [{
      id: 7, stage: 'UNDER_REVIEW', outcome: 'PENDING', submittedAt: '2026-11-02T09:00:00Z', submittedByVendor: true,
      grandTotal: null, currency: 'INR', canWithdraw: true,
      files: [{ id: 1, fileName: 'brakeline-quotation.pdf', fileFormat: 'PDF', sizeBytes: 4096 }] }] });
    wrap('/portal/rfqs/3', <VendorRfqDetailPage />, '/portal/rfqs/:id');

    expect(await screen.findByText('With the buyer')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Send quotation' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Withdraw quotation' })).toBeInTheDocument();
  });
});

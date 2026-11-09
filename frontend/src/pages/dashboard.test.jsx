import { ROUTER_FUTURE } from '../routerFuture.js';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { admin } from '../test/fixtures.js';
import DashboardPage from './DashboardPage.jsx';

vi.mock('../api/endpoints.js', () => ({
  reportingApi: { dashboard: vi.fn(), services: vi.fn(), auditLogs: vi.fn() },
  purchaseRequestsApi: { list: vi.fn(), quotations: vi.fn() },
  extractionApi: { engines: vi.fn() },
}));
vi.mock('../auth/AuthContext.jsx', () => ({ useAuth: () => ({ user: admin }) }));
const { reportingApi, purchaseRequestsApi, extractionApi } = await import('../api/endpoints.js');

/** A local calendar date (YYYY-MM-DD) offsetDays from today; the dashboard counts days in local time, not UTC. */
const isoDay = (offsetDays) => {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

describe('dashboard', () => {
  it('shows live counts, expiring offers, outstanding decisions and risks', async () => {
    vi.mocked(reportingApi.dashboard).mockResolvedValue({
      entities: 1, customers: 2, vendors: 7,
      purchaseRequestsByStatus: { DRAFT: 1, OPEN: 2, UNDER_REVIEW: 1, APPROVED: 3, REJECTED: 0, CANCELLED: 0 },
      quotationsByStatus: { UPLOADED: 0, PROCESSING: 0, NEEDS_REVIEW: 2, READY_FOR_COMPARISON: 4, FAILED: 1 },
      extractionEngine: 'RULES',
    });
    vi.mocked(purchaseRequestsApi.list).mockResolvedValue({ content: [
      { id: 1, requestNumber: 'PR-1', title: 'Brake pads', status: 'OPEN', requiredBy: isoDay(-3), requestedBy: 'asha', createdAt: '2026-10-01T00:00:00Z' },
      { id: 2, requestNumber: 'PR-2', title: 'Bearings', status: 'UNDER_REVIEW', requiredBy: isoDay(20), requestedBy: 'asha', createdAt: '2026-10-02T00:00:00Z' },
    ] });
    vi.mocked(purchaseRequestsApi.quotations).mockImplementation(async (id) => (id === 1
      ? [{ id: 11, vendorName: 'Shakti Metals', requestNumber: 'PR-1', status: 'NEEDS_REVIEW', validUntil: isoDay(5) }]
      : [{ id: 12, vendorName: 'Vertex Auto', requestNumber: 'PR-2', status: 'READY_FOR_COMPARISON', validUntil: isoDay(60) }]));
    vi.mocked(extractionApi.engines).mockResolvedValue({ engines: [{ engine: 'GROQ', available: false }, { engine: 'RULES', available: true, models: ['rules v1'] }] });
    vi.mocked(reportingApi.services).mockResolvedValue([{ service: 'gateway', up: true, responseMs: 4 }]);
    vi.mocked(reportingApi.auditLogs).mockResolvedValue({ content: [
      { id: 5, action: 'QUOTATION_UPLOADED', entityType: 'QUOTATION', entityId: 11, performedBy: 'shakti', performedAt: '2026-11-01T10:00:00Z' },
    ] });

    render(<MemoryRouter future={ROUTER_FUTURE}><DashboardPage /></MemoryRouter>);

    expect(await screen.findByText('Welcome back, System')).toBeInTheDocument();
    expect(screen.getByText('Total vendors')).toBeInTheDocument();
    expect(screen.getByText('2 quotations to review · 1 awaiting award')).toBeInTheDocument();

    const expiring = screen.getByRole('heading', { name: 'Expiring quotation validity' }).closest('section');
    expect(await within(expiring).findByText('Shakti Metals')).toBeInTheDocument();
    expect(within(expiring).getByText('5d left')).toBeInTheDocument();
    expect(within(expiring).queryByText('Vertex Auto')).not.toBeInTheDocument();

    const decisions = screen.getByRole('heading', { name: 'Outstanding decisions' }).closest('section');
    expect(within(decisions).getByText('3d overdue')).toBeInTheDocument();
    expect(within(decisions).getByText('awaiting award')).toBeInTheDocument();

    expect(screen.getByText('1 extraction(s) failed')).toBeInTheDocument();
    expect(screen.getByText('1 RFQ(s) past required-by date')).toBeInTheDocument();
    expect(await screen.findByText('AI extraction not configured')).toBeInTheDocument();
    expect(await screen.findByText('Quotation uploaded')).toBeInTheDocument();
  });
});

import { ROUTER_FUTURE } from '../routerFuture.js';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { comparisonResult } from '../test/fixtures.js';
import ApprovalPanel from './ApprovalPanel.jsx';
import ComparisonMatrix from './ComparisonMatrix.jsx';
import { validateItem } from './QuotationItemForm.jsx';
import { ToastProvider } from './Toast.jsx';
import { StatusBadge } from './ui.jsx';

vi.mock('../api/endpoints.js', () => ({
  purchaseRequestsApi: { decide: vi.fn() },
}));
const { purchaseRequestsApi } = await import('../api/endpoints.js');

const wrap = (ui) => render(<MemoryRouter future={ROUTER_FUTURE}><ToastProvider>{ui}</ToastProvider></MemoryRouter>);

describe('StatusBadge', () => {
  it('shows a readable label with a tone', () => {
    render(<StatusBadge status="NEEDS_REVIEW" />);
    const badge = screen.getByText('Needs review');
    expect(badge).toHaveClass('badge-warning');
  });
});

describe('ComparisonMatrix', () => {
  it('shows vendors side by side with the disclaimer and lowest complete quotation', () => {
    wrap(<ComparisonMatrix result={comparisonResult} />);

    expect(screen.getByRole('note')).toHaveTextContent('never selects or approves');
    const bravoHeader = screen.getByRole('columnheader', { name: /Bravo Systems/ });
    expect(within(bravoHeader).getByText('Lowest complete')).toBeInTheDocument();
    expect(within(screen.getByRole('columnheader', { name: /Cheap Traders/ })).getByText('Incomplete')).toBeInTheDocument();
  });

  it('marks missing items and lists unmatched extra lines', () => {
    wrap(<ComparisonMatrix result={comparisonResult} />);

    expect(screen.getByText('Not quoted')).toHaveClass('cell-missing');
    expect(screen.getByText(/Installation charges/)).toBeInTheDocument();
    expect(screen.getByText('1 quotation(s) not ready for comparison were excluded')).toBeInTheDocument();
  });

  it('highlights the cheapest offer per item', () => {
    wrap(<ComparisonMatrix result={comparisonResult} />);
    const laptopRow = screen.getByRole('rowheader', { name: /Laptop Dell Latitude 5440/ }).closest('tr');
    const best = laptopRow.querySelectorAll('td.cell-best');
    expect(best).toHaveLength(1);
    expect(best[0]).toHaveTextContent('₹7,89,420.00');
  });
});

describe('ApprovalPanel', () => {
  const request = { id: 9, status: 'UNDER_REVIEW', requestedById: 2 };
  const latest = { id: 70, reportVersion: 1, lowestCompleteQuotationId: 11, result: comparisonResult };

  beforeEach(() => vi.mocked(purchaseRequestsApi.decide).mockReset());

  it('never preselects a vendor', () => {
    wrap(<ApprovalPanel request={request} latest={latest} approvals={[]} canDecide onDecided={() => {}} />);
    expect(screen.getByLabelText(/Selected quotation/)).toHaveValue('');
    expect(screen.getByRole('button', { name: 'Award' })).toBeDisabled();
  });

  it('requires a reason when the chosen quotation is not the lowest', async () => {
    const user = userEvent.setup();
    wrap(<ApprovalPanel request={request} latest={latest} approvals={[]} canDecide onDecided={() => {}} />);

    await user.selectOptions(screen.getByLabelText(/Selected quotation/), '10'); // Alpha, not the lowest
    expect(screen.getByRole('button', { name: 'Award' })).toBeDisabled();

    await user.type(screen.getByLabelText(/Reason/), 'Better warranty');
    expect(screen.getByRole('button', { name: 'Award' })).toBeEnabled();
  });

  it('records the award to the selected quotation', async () => {
    const user = userEvent.setup();
    const onDecided = vi.fn();
    vi.mocked(purchaseRequestsApi.decide).mockResolvedValue({ id: 1 });
    wrap(<ApprovalPanel request={request} latest={latest} approvals={[]} canDecide onDecided={onDecided} />);

    await user.selectOptions(screen.getByLabelText(/Selected quotation/), '11');
    await user.click(screen.getByRole('button', { name: 'Award' }));

    expect(purchaseRequestsApi.decide).toHaveBeenCalledWith(9, {
      comparisonReportId: 70, decision: 'APPROVED', selectedQuotationId: 11, reason: null,
    });
    expect(onDecided).toHaveBeenCalled();
  });

  it('hides the form from users who may not decide', () => {
    wrap(<ApprovalPanel request={request} latest={latest} approvals={[]} canDecide={false} onDecided={() => {}} />);
    expect(screen.queryByRole('button', { name: 'Award' })).not.toBeInTheDocument();
    expect(screen.getByText(/Only a manager/)).toBeInTheDocument();
  });
});

describe('quoted line validation', () => {
  it('accepts a complete line', () => {
    expect(validateItem({ description: 'Laptop', quantity: '10', unitPrice: '68500', taxPercent: '18' })).toEqual({});
  });

  it('rejects missing description, negatives, zero quantity and percentages above 100', () => {
    const errors = validateItem({ description: ' ', quantity: '0', unitPrice: '-1', discountPercent: '150', taxAmount: 'abc' });
    expect(errors).toEqual({
      description: 'Description is required',
      quantity: 'Must be greater than 0',
      unitPrice: 'Cannot be negative',
      discountPercent: 'Must be between 0 and 100',
      taxAmount: 'Must be a number',
    });
  });
});

describe('FilePicker', () => {
  it('collects several files of different types, skips duplicates and lets you remove one', async () => {
    const { default: FilePicker } = await import('./FilePicker.jsx');
    const user = userEvent.setup();
    let files = [];
    const onChange = (next) => { files = next; rerender(<FilePicker files={files} onChange={onChange} />); };
    const { rerender } = render(<FilePicker files={files} onChange={onChange} />);

    const pdf = new File(['%PDF-'], 'Northstar.pdf', { type: 'application/pdf' });
    const xlsx = new File(['PK'], 'Northstar.xlsx');
    const docx = new File(['PK'], 'Northstar.docx');
    await user.upload(screen.getByLabelText('Add files'), [pdf, xlsx]);
    await user.upload(screen.getByLabelText('Add files'), [docx, pdf]); // pdf again is ignored

    expect(files.map((f) => f.name)).toEqual(['Northstar.pdf', 'Northstar.xlsx', 'Northstar.docx']);
    expect(screen.getByText('XLSX')).toHaveClass('file-type-xlsx');

    await user.click(screen.getByRole('button', { name: 'Remove Northstar.xlsx' }));
    expect(files.map((f) => f.name)).toEqual(['Northstar.pdf', 'Northstar.docx']);
  });
});

describe('HistoryTimeline', () => {
  it('lists audit entries newest first with readable actions and details', async () => {
    const { default: HistoryTimeline } = await import('./HistoryTimeline.jsx');
    const load = vi.fn().mockResolvedValue({
      content: [
        { id: 2, entityType: 'QUOTATION', entityId: 7, action: 'MARKED_READY', performedBy: 'asha.buyer',
          performedAt: '2026-11-01T10:00:00Z', details: '{"acknowledgedWarnings":1}' },
        { id: 1, entityType: 'PURCHASE_REQUEST', entityId: 3, action: 'CREATED', performedBy: 'asha.buyer',
          performedAt: '2026-11-01T09:00:00Z', details: null },
      ],
      totalElements: 2,
    });
    wrap(<HistoryTimeline load={load} showRecord />);
    expect(await screen.findByText('Marked ready')).toBeInTheDocument();
    expect(screen.getByText('Quotation #7')).toBeInTheDocument();
    expect(screen.getByText('Acknowledged warnings')).toBeInTheDocument();
    expect(screen.getAllByRole('row')).toHaveLength(3);
  });

  it('shows an empty state', async () => {
    const { default: HistoryTimeline } = await import('./HistoryTimeline.jsx');
    wrap(<HistoryTimeline load={() => Promise.resolve({ content: [], totalElements: 0 })} />);
    expect(await screen.findByText('No history yet')).toBeInTheDocument();
  });
});

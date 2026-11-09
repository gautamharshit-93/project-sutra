import { ROUTER_FUTURE } from '../routerFuture.js';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { comparisonResult } from '../test/fixtures.js';
import { comparisonToCsv } from '../utils/comparisonCsv.js';
import ComparisonMatrix from './ComparisonMatrix.jsx';
import { GradeDetails } from './Grade.jsx';
import { ToastProvider } from './Toast.jsx';

vi.mock('../api/endpoints.js', () => ({
  quotationsApi: { crossCheck: vi.fn() },
  extractionApi: { engines: vi.fn() },
}));
const { quotationsApi, extractionApi } = await import('../api/endpoints.js');

const wrap = (ui) => render(<MemoryRouter future={ROUTER_FUTURE}><ToastProvider>{ui}</ToastProvider></MemoryRouter>);

function report(score, grade, rules = [
  { code: 'PRICE', label: 'Price', points: 40, maxPoints: 40, detail: 'Lowest complete quotation' },
  { code: 'WARRANTY', label: 'Warranty', points: 5, maxPoints: 10, detail: 'Partly unspecified: UPS warranty not specified.' },
]) {
  return { score, grade, rules };
}

describe('GradeDetails', () => {
  it('shows the letter and score, and explains it rule by rule on request', async () => {
    render(<GradeDetails report={report(90, 'A')} />);

    expect(screen.getByText('A')).toHaveClass('badge-success');
    expect(screen.queryByText('Warranty')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'How is this graded?' }));

    const warranty = screen.getByText('Warranty').closest('tr');
    expect(warranty).toHaveClass('rule-short');
    expect(warranty).toHaveTextContent('5 / 10');
    expect(warranty).toHaveTextContent('UPS warranty not specified');
  });

  it('renders nothing without a report', () => {
    const { container } = render(<GradeDetails report={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('ComparisonMatrix advisory grade', () => {
  const graded = {
    ...comparisonResult,
    quotations: comparisonResult.quotations.map((q, i) => ({ ...q, grade: [report(72, 'B'), report(91, 'A'), report(35, 'F')][i] })),
  };

  it('adds a grade row that is labelled as advice, with the rules per vendor', async () => {
    wrap(<ComparisonMatrix result={graded} />);

    const row = screen.getByRole('rowheader', { name: /Advisory grade/ }).closest('tr');
    expect(row).toHaveTextContent('not a selection');
    expect(within(row).getByText('F')).toHaveClass('badge-danger');
    await userEvent.click(within(row).getAllByRole('button', { name: 'Rules' })[1]);
    expect(screen.getByRole('heading', { name: /Bravo Systems: advisory grade/ })).toBeInTheDocument();
  });

  it('exports the grade to CSV', () => {
    const csv = comparisonToCsv(graded, { requestNumber: 'PR-1', reportVersion: 1 });
    expect(csv).toContain('Advisory grade (not a selection),,,B (72/100),A (91/100),F (35/100)');
  });
});


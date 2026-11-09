import { ROUTER_FUTURE } from '../routerFuture.js';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { comparisonResult } from '../test/fixtures.js';
import { comparisonToCsv } from '../utils/comparisonCsv.js';
import ComparisonMatrix from './ComparisonMatrix.jsx';
import CrossCheckPanel from './CrossCheckPanel.jsx';
import EnginePicker, { parseChoice } from './EnginePicker.jsx';
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

describe('EnginePicker', () => {
  beforeEach(() => vi.mocked(extractionApi.engines).mockResolvedValue({
    autoOrder: ['GROQ', 'RULES'],
    engines: [
      { engine: 'GROQ', label: 'Groq AI', model: 'openai/gpt-oss-120b > openai/gpt-oss-20b',
        models: ['openai/gpt-oss-120b', 'openai/gpt-oss-20b'], available: true, inAutoOrder: true },
      { engine: 'RULES', label: 'Rule-based reader (no AI)', model: 'rules v1', models: ['rules v1'], available: false, inAutoOrder: true },
    ],
  }));

  it('offers automatic, each free Groq model and the rule-based reader', async () => {
    const onChange = vi.fn();
    render(<EnginePicker value="AUTO" onChange={onChange} />);

    expect(await screen.findByRole('option', { name: 'Groq · openai/gpt-oss-120b (default)' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Automatic: Groq AI, then rule-based' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /Rule-based reader \(no AI\) \(document service down\)/ })).toBeDisabled();
    await userEvent.selectOptions(screen.getByLabelText('Extraction engine'), 'GROQ:openai/gpt-oss-20b');
    expect(onChange).toHaveBeenCalledWith('GROQ:openai/gpt-oss-20b');
  });

  it('turns a choice into API parameters', () => {
    expect(parseChoice('GROQ:openai/gpt-oss-20b')).toEqual({ engine: 'GROQ', model: 'openai/gpt-oss-20b' });
    expect(parseChoice('RULES')).toEqual({ engine: 'RULES', model: null });
    expect(parseChoice(undefined)).toEqual({ engine: 'AUTO', model: null });
  });
});

describe('CrossCheckPanel', () => {
  const quotation = {
    id: 5, status: 'NEEDS_REVIEW', currency: 'INR', extractionSource: 'GROQ', grandTotal: '1000.00',
    statedGrandTotal: '1000.00', quotationNumber: 'Q-1', validUntil: '2026-12-01', files: [{ id: 1 }],
    items: [{ id: 1, requestItemId: 100, requestItemLineNumber: 1, itemCode: 'IT-001', description: 'Laptop', lineTotal: '1000.00' }],
    flags: [], extractionQuality: report(95, 'A'),
  };
  const run = (engine, total, grade) => ({
    engine, model: `${engine.toLowerCase()} model`, ok: true, error: null, durationMs: 1500, itemCount: 1, matchedItems: 1,
    requestedItems: 1, quotationNumber: 'Q-1', currency: 'INR', validUntil: '2026-12-01', statedGrandTotal: '1000.00',
    calculatedGrandTotal: total, openFlags: 0, flags: [],
    lines: [{ itemCode: 'IT-001', requestLine: 1, description: 'Laptop', quantity: 1, unitPrice: total, lineTotal: total }],
    quality: grade,
  });

  beforeEach(() => vi.mocked(quotationsApi.crossCheck).mockReset());

  it('reads with every engine, shows the readings side by side and offers to use one', async () => {
    vi.mocked(quotationsApi.crossCheck).mockResolvedValue({
      quotationId: 5, currentSource: 'GROQ', currentGrandTotal: '1000.00', totalsAgree: false, bestEngine: 'GROQ',
      summary: 'The engines disagree on the grand total: check the lines that differ against the original file. Highest quality score: GROQ.',
      runs: [
        run('GROQ', '1000.00', report(95, 'A')),
        run('RULES', '900.00', report(60, 'C')),
      ],
    });
    const onUseEngine = vi.fn();
    wrap(<CrossCheckPanel quotation={quotation} editable onUseEngine={onUseEngine} />);

    await userEvent.click(screen.getByRole('button', { name: 'Cross-check' }));

    expect(quotationsApi.crossCheck).toHaveBeenCalledWith(5);
    expect(await screen.findByText(/The engines disagree/)).toBeInTheDocument();
    expect(within(screen.getByRole('columnheader', { name: /^Groq AI/ })).getByText('Highest quality')).toBeInTheDocument();
    const lineRow = screen.getByRole('rowheader', { name: 'Request line 1' }).closest('tr');
    expect(lineRow).toHaveTextContent('₹900.00');
    await userEvent.click(screen.getByRole('button', { name: 'Use Rule-based' }));
    expect(onUseEngine).toHaveBeenCalledWith('RULES');
  });

  it('shows an engine that could not read the file', async () => {
    vi.mocked(quotationsApi.crossCheck).mockResolvedValue({
      quotationId: 5, currentSource: 'GROQ', currentGrandTotal: '1000.00', totalsAgree: false, bestEngine: 'RULES',
      summary: 'Only RULES produced a reading (1 failed); there is nothing to cross-check it against.',
      runs: [
        { engine: 'GROQ', model: 'openai/gpt-oss-120b', ok: false, error: 'All free Groq models are rate-limited', durationMs: 10, lines: [], flags: [] },
        run('RULES', '1000.00', report(80, 'B')),
      ],
    });
    wrap(<CrossCheckPanel quotation={quotation} editable onUseEngine={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Cross-check' }));

    expect(await screen.findByText('All free Groq models are rate-limited')).toBeInTheDocument();
  });

  it('shows the error when the cross-check cannot run', async () => {
    vi.mocked(quotationsApi.crossCheck).mockImplementation(async () => {
      throw Object.assign(new Error('Request failed'), { response: { data: { message: 'No extraction engine is configured' } } });
    });
    wrap(<CrossCheckPanel quotation={quotation} editable onUseEngine={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Cross-check' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('No extraction engine is configured');
    // Vitest 3.2 reports a rejected result still recorded on a spy as a test error, even when the UI handled it
    vi.mocked(quotationsApi.crossCheck).mockReset();
  });
});

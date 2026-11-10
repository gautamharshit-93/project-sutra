import { describe, expect, it } from 'vitest';
import { errorMessage, fieldErrors } from '../api/client.js';
import { admin, approver, buyer, viewer } from '../test/fixtures.js';
import {
  emptyToNull, formatBytes, formatConfidence, formatDate, formatMoney, formatPercent, humanize,
} from './format.js';
import { can, hasRole } from './permissions.js';

describe('format', () => {
  it('formats rupees with Indian digit grouping and two decimals', () => {
    expect(formatMoney('959930', 'INR')).toBe('₹9,59,930.00');
    expect(formatMoney(1650.5, 'USD')).toBe('$1,650.50');
  });

  it('shows a dash for missing values instead of zero', () => {
    expect(formatMoney(null)).toBe('—');
    expect(formatMoney('')).toBe('—');
    expect(formatDate(null)).toBe('—');
    expect(formatConfidence(undefined)).toBe('—');
  });

  it('formats percentages, confidence, sizes and enum names', () => {
    expect(formatPercent(1.012)).toBe('+1.01%');
    expect(formatPercent(-3)).toBe('-3.00%');
    expect(formatConfidence('0.875')).toBe('88%');
    expect(formatBytes(2048)).toBe('2.0 KB');
    expect(humanize('READY_FOR_COMPARISON')).toBe('Ready for comparison');
  });

  it('reads ISO dates without shifting the day', () => {
    expect(formatDate('2026-11-30')).toContain('30');
  });

  it('turns blank strings into null for the API', () => {
    expect(emptyToNull({ a: ' ', b: 'x', c: 0, d: null })).toEqual({ a: null, b: 'x', c: 0, d: null });
  });
});

describe('permissions', () => {
  const ownRequest = { requestedById: buyer.id };
  const otherRequest = { requestedById: 99 };

  it('buyers manage master data; only admins manage users', () => {
    expect(can.manageMasterData(admin)).toBe(true);
    expect(can.manageMasterData(buyer)).toBe(true);
    expect(can.manageMasterData(approver)).toBe(false);
    expect(can.manageUsers(buyer)).toBe(false);
    expect(can.manageUsers(approver)).toBe(false);
    expect(can.viewAudit(approver)).toBe(true);
    expect(can.viewAudit(viewer)).toBe(false);
  });

  it('procurement users edit only their own requests', () => {
    expect(can.editRequest(buyer, ownRequest)).toBe(true);
    expect(can.editRequest(buyer, otherRequest)).toBe(false);
    expect(can.editRequest(admin, otherRequest)).toBe(true);
    expect(can.editRequest(viewer, ownRequest)).toBe(false);
  });

  it('approvers decide, but not on requests they raised', () => {
    expect(can.approve(approver, otherRequest)).toBe(true);
    expect(can.approve(approver, { requestedById: approver.id })).toBe(false);
    expect(can.approve(buyer, otherRequest)).toBe(false);
    expect(hasRole(null, 'ADMIN')).toBe(false);
  });
});

describe('api error handling', () => {
  it('prefers field errors, then the API message, then a network hint', () => {
    const validation = { response: { data: { message: 'invalid', fieldErrors: [{ field: 'code', message: 'too short' }] } } };
    expect(errorMessage(validation)).toBe('code: too short');
    expect(fieldErrors(validation)).toEqual({ code: 'too short' });
    expect(errorMessage({ response: { data: { message: 'Vendor with id 4 was not found' } } }))
      .toBe('Vendor with id 4 was not found');
    expect(errorMessage({ code: 'ERR_NETWORK' })).toMatch(/Cannot reach the server/);
  });
});

describe('comparisonToCsv', () => {
  it('writes one column per vendor with plain-number totals, a BOM and escaped text', async () => {
    const { comparisonToCsv } = await import('./comparisonCsv.js');
    const { comparisonResult } = await import('../test/fixtures.js');
    const csv = comparisonToCsv(comparisonResult, { requestNumber: 'PR-2026-00001', reportVersion: 2 });
    const lines = csv.slice(1).split('\r\n');
    expect(csv.charCodeAt(0)).toBe(0xfeff);
    expect(lines[0]).toBe('Comparison PR-2026-00001 version 2');
    expect(lines[3]).toContain('Alpha Computers');
    expect(lines.find((l) => l.includes('Laptop Dell Latitude 5440'))).toMatch(/^1,,Laptop Dell Latitude 5440,10,Nos,/);
    expect(lines.some((l) => l.startsWith(',,Comparable total (requested qty)'))).toBe(true);
    expect(csv).toContain('Installation charges');
  });

  it('neutralises spreadsheet formulas and quotes commas', async () => {
    const { comparisonToCsv } = await import('./comparisonCsv.js');
    const { comparisonResult } = await import('../test/fixtures.js');
    const result = {
      ...comparisonResult,
      quotations: comparisonResult.quotations.map((q, i) => (i === 0 ? { ...q, paymentTerms: '=HYPERLINK("x")', deliveryTerms: '2 weeks, FOR site' } : q)),
    };
    const csv = comparisonToCsv(result);
    expect(csv).toContain(`"'=HYPERLINK(""x"")"`);
    expect(csv).toContain('"2 weeks, FOR site"');
  });
});

describe('errorMessage with a request reference', () => {
  it('adds the reference only to server errors', () => {
    const server = { response: { status: 502, data: { message: 'Groq is temporarily unavailable', correlationId: 'abc123def4567890' } } };
    expect(errorMessage(server)).toBe('Groq is temporarily unavailable (reference abc123def4567890)');
    const client = { response: { status: 422, data: { message: 'Reason required', correlationId: 'abc123def4567890' } } };
    expect(errorMessage(client)).toBe('Reason required');
  });
});

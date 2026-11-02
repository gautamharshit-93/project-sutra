/**
 * Builds a CSV of a comparison report for Excel: one column per quotation, one row per requested item, then
 * totals and commercial terms. Amounts are plain numbers (no currency symbols) so they can be summed in Excel.
 */
export function comparisonToCsv(result, { requestNumber, reportVersion } = {}) {
  const { quotations, rows, unmatchedItems } = result;
  const lines = [];
  const add = (...cells) => lines.push(cells.map(csvCell).join(','));
  const perQuote = (fn) => quotations.map(fn);

  add(`Comparison ${requestNumber || ''} version ${reportVersion ?? ''}`.trim());
  add(result.disclaimer);
  add();
  add('Line', 'Item code', 'Requested item', 'Qty', 'Unit', ...perQuote((q) => q.vendorName));
  rows.forEach((row) => {
    add(row.lineNumber, row.itemCode, row.description, row.quantity, row.unitOfMeasure,
      ...row.cells.map((c) => (c.status === 'MISSING' ? 'Not quoted' : c.comparableLineTotal)));
  });
  add();
  const summary = (label, fn) => add('', '', label, '', '', ...perQuote(fn));
  summary('Comparable total (requested qty)', (q) => q.comparableTotal);
  summary('Price rank', (q) => q.priceRank ?? 'Not ranked');
  summary('Difference from lowest', (q) => q.differenceFromLowest);
  summary('Difference %', (q) => q.differencePercent);
  summary('Quotation grand total', (q) => q.grandTotal);
  summary('Tax', (q) => q.taxTotal);
  summary('Currency', (q) => q.currency);
  summary('Items quoted', (q) => `${q.itemsQuoted} of ${q.itemsQuoted + q.itemsMissing}`);
  summary('Valid until', (q) => (q.validUntil ? `${q.validUntil}${q.validityExpired ? ' (expired)' : ''}` : ''));
  summary('Delivery terms', (q) => q.deliveryTerms);
  summary('Payment terms', (q) => q.paymentTerms);
  summary('Warranty', (q) => q.warrantyTerms);
  summary('Missing information', (q) => q.missingInformation.join('; ') || 'None');
  summary('Advisory grade (not a selection)', (q) => (q.grade ? `${q.grade.grade} (${q.grade.score}/100)` : ''));
  if (unmatchedItems.length) {
    add();
    add('Quoted lines that match no requested item');
    add('Vendor', 'Description', 'Line total');
    unmatchedItems.forEach((u) => add(u.vendorName, u.description, u.lineTotal));
  }
  // BOM so Excel reads UTF-8 (₹, accented vendor names) correctly
  return `﻿${lines.join('\r\n')}\r\n`;
}

function csvCell(value) {
  if (value == null) return '';
  const text = String(value);
  // Neutralise spreadsheet formulas in text taken from vendor documents
  const safe = /^[=+\-@\t\r]/.test(text) && Number.isNaN(Number(text)) ? `'${text}` : text;
  return /[",\r\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
}

/** Saves text as a file through the browser. */
export function downloadText(fileName, text, type = 'text/csv;charset=utf-8') {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

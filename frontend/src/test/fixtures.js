export const buyer = { id: 2, username: 'buyer', fullName: 'Asha Buyer', roles: ['PROCUREMENT'] };
export const approver = { id: 3, username: 'manager', fullName: 'Ravi Manager', roles: ['APPROVER'] };
export const admin = { id: 1, username: 'admin', fullName: 'System Administrator', roles: ['ADMIN'] };
export const viewer = { id: 4, username: 'viewer', fullName: 'Vic Viewer', roles: ['VIEWER'] };

/** A comparison of two complete quotations plus one incomplete one. Bravo (11) is the lowest complete. */
export const comparisonResult = {
  purchaseRequestId: 9,
  requestCurrency: 'INR',
  lowestCompleteQuotationId: 11,
  disclaimer: 'This comparison is informational. The system never selects or approves a vendor; an authorised approver must record the decision.',
  warnings: ['1 quotation(s) not ready for comparison were excluded'],
  quotations: [
    column(10, 'Alpha Computers', '940460.00', 2, 9440, 1.01, true),
    column(11, 'Bravo Systems', '931020.00', 1, 0, 0, true),
    { ...column(12, 'Cheap Traders', '590000.00', null, null, null, false), itemsQuoted: 1, itemsMissing: 1,
      missingInformation: ['1 requested item(s) not quoted'] },
  ],
  rows: [
    {
      requestItemId: 100, lineNumber: 1, description: 'Laptop Dell Latitude 5440', quantity: 10, unitOfMeasure: 'Nos',
      lowestEffectiveUnitPrice: 78942, highestEffectiveUnitPrice: 80830, priceSpreadPercent: 2.39,
      cells: [
        cell(10, '808300.00', '80830.0000', false, 2.39),
        cell(11, '789420.00', '78942.0000', true, 0),
        cell(12, '590000.00', '59000.0000', false, null),
      ],
    },
    {
      requestItemId: 101, lineNumber: 2, description: '24 inch LED Monitor', quantity: 10, unitOfMeasure: 'Nos',
      lowestEffectiveUnitPrice: 13216, highestEffectiveUnitPrice: 14160, priceSpreadPercent: 7.14,
      cells: [
        cell(10, '132160.00', '13216.0000', true, 0),
        cell(11, '141600.00', '14160.0000', false, 7.14),
        { quotationId: 12, status: 'MISSING', lowest: false, quantityMismatch: false },
      ],
    },
  ],
  unmatchedItems: [{ quotationId: 10, vendorName: 'Alpha Computers', quotationItemId: 900, description: 'Installation charges', lineTotal: '5000.00' }],
};

function column(id, vendorName, comparableTotal, rank, diff, diffPct, complete) {
  return {
    quotationId: id, quotationVersion: 3, vendorId: id, vendorName, quotationNumber: `Q-${id}`,
    status: 'READY_FOR_COMPARISON', currency: 'INR', subtotal: null, discountTotal: '0.00', taxTotal: '100.00',
    grandTotal: comparableTotal, comparableTotal, validUntil: '2026-12-01', validityExpired: false,
    deliveryTerms: '2 weeks', paymentTerms: '30 days', warrantyTerms: null, itemsQuoted: 2, itemsMissing: 0, complete,
    priceRank: rank, differenceFromLowest: diff, differencePercent: diffPct, openFlags: 0, missingInformation: [],
  };
}

function cell(quotationId, comparable, effective, lowest, pct) {
  return {
    quotationId, status: 'MATCHED', quotationItemId: quotationId * 10, quotedDescription: 'Quoted item',
    quotedQuantity: 10, unitPrice: effective, lineTotal: comparable, effectiveUnitPrice: effective,
    comparableLineTotal: comparable, matchConfidence: 0.92, quantityMismatch: false, lowest,
    differenceFromLowest: null, differencePercent: pct,
  };
}

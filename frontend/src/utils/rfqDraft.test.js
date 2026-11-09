import { describe, expect, it } from 'vitest';
import { draftToForm, itemsComplete, matchByName, toRequest } from './rfqDraft.js';

const entities = [
  { id: 1, code: 'AXIOM', name: 'Axiom Auto Components', active: true },
  { id: 2, code: 'NORTH', name: 'Northline Cars', active: true },
  { id: 3, code: 'OLD', name: 'Old Plant', active: false },
];

describe('RFQ drafts', () => {
  it('matches the buyer named in the RFQ to an entity, or takes the only one', () => {
    expect(matchByName(entities, 'AXIOM AUTO COMPONENTS PVT LTD')?.id).toBe(1);
    expect(matchByName(entities, 'northline')?.id).toBe(2);
    expect(matchByName(entities, 'Old Plant')).toBeNull();
    expect(matchByName([entities[0]], 'anyone')?.id).toBe(1);
    expect(matchByName(entities, null)).toBeNull();
  });

  it('turns a draft into a form and back into one create request', () => {
    const draft = {
      title: 'Production materials', description: null, requiredBy: '2026-12-15', currency: 'INR',
      buyerName: 'Axiom Auto', customerName: null,
      items: [
        { itemCode: 'MT-101', description: 'CRCA sheet', quantity: 12, unitOfMeasure: 'Tonne', specifications: null },
        { itemCode: null, description: 'Hex bolt M8', quantity: null, unitOfMeasure: 'Nos', specifications: 'grade 8.8' },
      ],
    };
    const { values, items } = draftToForm(draft, entities, []);
    expect(values.entityId).toBe('1');
    expect(itemsComplete(items)).toBe(false); // the bolt has no quantity yet

    items[1].quantity = '10000';
    expect(itemsComplete(items)).toBe(true);
    const body = toRequest(values, items);
    expect(body.request).toMatchObject({ title: 'Production materials', entityId: 1, currency: 'INR', requiredBy: '2026-12-15' });
    expect(body.items).toEqual([
      { itemCode: 'MT-101', description: 'CRCA sheet', quantity: 12, unitOfMeasure: 'Tonne', specifications: null },
      { itemCode: null, description: 'Hex bolt M8', quantity: 10000, unitOfMeasure: 'Nos', specifications: 'grade 8.8' },
    ]);
  });
});

import { ROUTER_FUTURE } from '../routerFuture.js';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import RequireAuth from '../auth/RequireAuth.jsx';
import { AssistantButton, AssistantProvider } from './Assistant.jsx';

vi.mock('../api/endpoints.js', () => ({
  assistantApi: { status: vi.fn(), chat: vi.fn() },
  authApi: { login: vi.fn(), me: vi.fn() },
  entitiesApi: { list: vi.fn() },
  customersApi: { list: vi.fn() },
  purchaseRequestsApi: { createWithItems: vi.fn(), draftFromFiles: vi.fn(), draftFromText: vi.fn() },
}));
const { assistantApi, entitiesApi, purchaseRequestsApi } = await import('../api/endpoints.js');

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>;
}

function renderAssistant(path = '/quotations/11', canDraft = false) {
  return render(
    <MemoryRouter future={ROUTER_FUTURE} initialEntries={[path]}>
      <AssistantProvider enabled canDraft={canDraft}>
        <AssistantButton />
        <Routes><Route path="*" element={<Where />} /></Routes>
      </AssistantProvider>
    </MemoryRouter>,
  );
}

describe('SUTRA Sahayak', () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.mocked(assistantApi.status).mockReset().mockResolvedValue({ enabled: true, model: 'openai/gpt-oss-120b' });
    vi.mocked(assistantApi.chat).mockReset();
  });

  it('sends the question with the record on screen and links to the records in the answer', async () => {
    vi.mocked(assistantApi.chat).mockResolvedValue({
      reply: 'इस कोटेशन पर **2 खुले फ़्लैग** हैं:\n- Total mismatch\n- Warranty missing',
      links: [{ type: 'PURCHASE_REQUEST', id: 7, label: 'PR-2026-00007' }],
    });
    const user = userEvent.setup();
    renderAssistant();

    await user.click(screen.getByRole('button', { name: /Ask Sahayak/ }));
    expect(screen.getByText('Looking at Quotation #11')).toBeInTheDocument();
    await user.type(screen.getByRole('textbox', { name: 'Ask anything…' }), 'is quotation pe flag kyun hai?{Enter}');

    expect(assistantApi.chat).toHaveBeenCalledWith({
      message: 'is quotation pe flag kyun hai?', history: [], purchaseRequestId: null, quotationId: 11,
    });
    expect((await screen.findByText('2 खुले फ़्लैग')).tagName).toBe('STRONG');
    expect(screen.getByText('Warranty missing').tagName).toBe('LI');

    await user.click(screen.getByRole('button', { name: /PR-2026-00007/ }));
    expect(screen.getByTestId('where')).toHaveTextContent('/purchase-requests/7');
  });

  it('switches to Hindi and shows the error from the server instead of an answer', async () => {
    vi.mocked(assistantApi.chat).mockRejectedValue({ response: { status: 502, data: { message: 'Groq free-tier limit reached' } } });
    const user = userEvent.setup();
    renderAssistant('/');

    await user.click(screen.getByRole('button', { name: /Ask Sahayak/ }));
    await user.click(screen.getByRole('button', { name: 'हिं' }));
    expect(screen.getByRole('textbox', { name: 'कुछ भी पूछें…' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'किन कोटेशन को रिव्यू चाहिए?' }));

    expect(await screen.findByText(/Groq free-tier limit reached/)).toBeInTheDocument();
  });

  it('turns a pasted RFQ into a draft card that creates the RFQ with one click', async () => {
    vi.mocked(entitiesApi.list).mockResolvedValue({ content: [{ id: 4, code: 'AXIOM', name: 'Axiom Auto', active: true }] });
    vi.mocked(assistantApi.chat).mockResolvedValue({
      reply: 'Draft ready, please check.', links: [],
      rfqDraft: {
        title: 'Steel for Q1', description: null, requiredBy: '2026-12-15', currency: 'INR', buyerName: 'Axiom',
        customerName: null, warnings: [], source: 'pasted text',
        items: [{ itemCode: 'MT-101', description: 'CRCA sheet 1.2 mm', quantity: 12, unitOfMeasure: 'Tonne', specifications: null }],
      },
    });
    vi.mocked(purchaseRequestsApi.createWithItems).mockResolvedValue({ id: 31, requestNumber: 'PR-2026-00031', items: [{}] });
    const user = userEvent.setup();
    renderAssistant('/', true);

    await user.click(screen.getByRole('button', { name: /Ask Sahayak/ }));
    expect(screen.getByRole('button', { name: 'Attach an RFQ file' })).toBeInTheDocument();
    await user.type(screen.getByRole('textbox', { name: 'Ask anything…' }), 'Need 12 tonne CRCA sheet MT-101 by 15 Dec{Enter}');

    expect(await screen.findByText('Steel for Q1')).toBeInTheDocument();
    expect(screen.getByText('12 Tonne')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Create RFQ' }));

    expect(purchaseRequestsApi.createWithItems).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Steel for Q1', entityId: 4, requiredBy: '2026-12-15' }),
      [{ itemCode: 'MT-101', description: 'CRCA sheet 1.2 mm', quantity: 12, unitOfMeasure: 'Tonne', specifications: null }],
      false,
    );
    expect(screen.getByTestId('where')).toHaveTextContent('/purchase-requests/31');
  });

  it('explains why it is off when Groq is not configured', async () => {
    vi.mocked(assistantApi.status).mockResolvedValue({ enabled: false, reason: 'The assistant needs Groq: set GROQ_API_KEY' });
    const user = userEvent.setup();
    renderAssistant('/');

    await user.click(screen.getByRole('button', { name: /Ask Sahayak/ }));

    expect(await screen.findByText(/set GROQ_API_KEY/)).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Ask anything…' })).toBeDisabled();
  });
});

describe('landing redirect', () => {
  it('sends signed-out visitors at the root to the landing page, elsewhere to sign in', async () => {
    const { AuthProvider } = await import('../auth/AuthContext.jsx');
    const app = (path) => render(
      <MemoryRouter future={ROUTER_FUTURE} initialEntries={[path]}>
        <AuthProvider>
          <Routes>
            <Route path="/welcome" element={<p>Landing</p>} />
            <Route path="/login" element={<p>Sign in page</p>} />
            <Route path="*" element={<RequireAuth landing="/welcome"><p>App</p></RequireAuth>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    const root = app('/');
    expect(await screen.findByText('Landing')).toBeInTheDocument();
    root.unmount();
    app('/vendors');
    expect(await screen.findByText('Sign in page')).toBeInTheDocument();
  });
});

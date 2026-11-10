import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { matchPath, useLocation, useNavigate } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { assistantApi, entitiesApi, purchaseRequestsApi } from '../api/endpoints.js';
import { errorMessage } from '../api/client.js';
import { useApi } from '../hooks/useApi.js';
import { formatDate } from '../utils/format.js';
import { draftToForm, itemsComplete, matchByName, toRequest } from '../utils/rfqDraft.js';
import { ACCEPTED_FILES } from './FilePicker.jsx';
import Icon from './Icon.jsx';
import NewRfqModal from './NewRfqModal.jsx';

/**
 * SUTRA Sahayak: a read-only assistant that answers questions about RFQs and quotations in Hindi, Hinglish or
 * English. It slides in from the right and knows which request or quotation is on screen. The conversation lives in
 * this browser tab only (sessionStorage); the server keeps no chat history.
 */
const AssistantContext = createContext({ open: false, setOpen: () => {}, available: false });
const useAssistant = () => useContext(AssistantContext);

const STORE_KEY = 'sutra.assistant.chat';
const MAX_HISTORY = 12;

const COPY = {
  en: {
    title: 'SUTRA Sahayak',
    subtitle: 'Ask about RFQs, quotations, flags and comparisons',
    placeholder: 'Ask anything…',
    hello: 'Hi! Ask me what is happening with your RFQs and quotations: what needs review, why a quotation was flagged, which complete quotation is lowest. To create an RFQ, paste it here or attach the file with the clip: I fill in everything and you confirm with one click.',
    thinking: 'Reading the latest data…',
    clear: 'New chat',
    listen: 'Speak (English)',
    suggestions: [
      'Which quotations need review?',
      'Summarise this request',
      'Why is this quotation flagged?',
      'Which vendor is lowest so far?',
    ],
  },
  hi: {
    title: 'SUTRA सहायक',
    subtitle: 'RFQ, कोटेशन, फ़्लैग और तुलना के बारे में पूछें',
    placeholder: 'कुछ भी पूछें…',
    hello: 'नमस्ते! RFQ और कोटेशन के बारे में पूछिए: किसे रिव्यू चाहिए, फ़्लैग क्यों है, सबसे सस्ता पूरा कोटेशन कौन-सा है। नया RFQ बनाना हो तो उसे यहाँ पेस्ट करें या क्लिप से फ़ाइल जोड़ें: मैं सब भर दूँगा, आप एक क्लिक में पक्का करें।',
    thinking: 'ताज़ा डेटा पढ़ रहा हूँ…',
    clear: 'नई बातचीत',
    listen: 'बोलें (हिंदी)',
    suggestions: [
      'किन कोटेशन को रिव्यू चाहिए?',
      'इस रिक्वेस्ट का सारांश बताओ',
      'इस कोटेशन पर फ़्लैग क्यों है?',
      'अब तक सबसे सस्ता वेंडर कौन है?',
    ],
  },
};

function loadChat() {
  try {
    const saved = JSON.parse(sessionStorage.getItem(STORE_KEY) || 'null');
    return saved && Array.isArray(saved.messages) ? saved : null;
  } catch {
    return null;
  }
}

function saveChat(state) {
  try {
    sessionStorage.setItem(STORE_KEY, JSON.stringify(state));
  } catch {
    /* storage unavailable: the chat simply won't survive a reload */
  }
}

/** The record on screen, so "is quotation ko check karo" needs no number. */
function useScreenContext() {
  const { pathname } = useLocation();
  return useMemo(() => {
    const q = matchPath('/quotations/:id', pathname);
    if (q) return { quotationId: Number(q.params.id), label: `Quotation #${q.params.id}` };
    const pr = matchPath('/purchase-requests/:id', pathname);
    if (pr) return { purchaseRequestId: Number(pr.params.id), label: `Request #${pr.params.id}` };
    return null;
  }, [pathname]);
}

/** Renders the reply's light markdown: **bold**, "- " bullets and line breaks. */
function Rich({ text }) {
  const blocks = [];
  let list = null;
  text.split('\n').forEach((raw, i) => {
    const line = raw.trimEnd();
    const bullet = /^\s*(?:[-*•]|\d+[.)])\s+(.*)$/.exec(line);
    if (bullet) {
      if (!list) { list = []; blocks.push({ list }); }
      list.push(bullet[1]);
      return;
    }
    list = null;
    if (line.trim()) blocks.push({ p: line, key: i });
  });
  const inline = (s) => s.split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
    part.startsWith('**') && part.endsWith('**') ? <strong key={i}>{part.slice(2, -2)}</strong> : part);
  return blocks.map((b, i) => b.list
    ? <ul key={i}>{b.list.map((item, j) => <li key={j}>{inline(item)}</li>)}</ul>
    : <p key={i}>{inline(b.p)}</p>);
}

const SpeechRecognition = typeof window !== 'undefined' && (window.SpeechRecognition || window.webkitSpeechRecognition);

/**
 * An RFQ Sahayak read from pasted text or an attached file. One click creates it (header and all items) as a Draft;
 * "Edit first" opens the full New RFQ window with everything filled in. Nothing is saved before the click.
 */
function RfqDraftCard({ draft, created, onCreated, onEdit }) {
  const navigate = useNavigate();
  const entities = useApi(() => entitiesApi.list({ size: 200 }), []);
  const list = (entities.data?.content || []).filter((e) => e.active);
  const [entityId, setEntityId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const { values, items } = draftToForm(draft, [], []);
  const chosen = entityId || String(matchByName(list, draft.buyerName)?.id || '');
  const complete = itemsComplete(items);

  async function create() {
    setBusy(true);
    setError('');
    try {
      const { request, items: lines } = toRequest({ ...values, entityId: chosen }, items);
      const pr = await purchaseRequestsApi.createWithItems(request, lines, false);
      onCreated(pr);
      navigate(`/purchase-requests/${pr.id}`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="draft-card">
      <div className="draft-card-head">
        <span className="badge badge-info">RFQ draft</span>
        <strong>{draft.title}</strong>
        <span className="muted small">
          {draft.items.length} item(s){draft.requiredBy ? ` · needed by ${formatDate(draft.requiredBy)}` : ''} · from {draft.source}
        </span>
      </div>
      <ul className="draft-card-items">
        {draft.items.slice(0, 6).map((it, i) => (
          <li key={i}>
            <span>{it.itemCode ? <code>{it.itemCode}</code> : null} {it.description}</span>
            <span className={it.quantity == null ? 'draft-missing' : ''}>{it.quantity ?? '?'} {it.unitOfMeasure}</span>
          </li>
        ))}
        {draft.items.length > 6 && <li className="muted">and {draft.items.length - 6} more</li>}
      </ul>
      {draft.warnings?.length > 0 && (
        <p className="draft-card-warn"><Icon name="alert" size={14} /> {draft.warnings.length} thing(s) to check: {draft.warnings[0]}</p>
      )}
      {created ? (
        <p className="draft-card-done"><Icon name="check" size={14} /> Created {created.requestNumber} as a draft.</p>
      ) : (
        <>
          <label className="draft-card-entity">
            <span>Entity</span>
            <select value={chosen} onChange={(e) => setEntityId(e.target.value)}>
              <option value="">Select the company / plant buying</option>
              {list.map((e) => <option key={e.id} value={e.id}>{e.name}</option>)}
            </select>
          </label>
          {error && <p className="draft-card-error">{error}</p>}
          <div className="draft-card-actions">
            <button type="button" className="btn btn-small" onClick={() => onEdit(draft)}>Edit first</button>
            <button type="button" className="btn btn-primary btn-small" disabled={!chosen || !complete || busy} onClick={create}
                    title={!complete ? 'Some lines have no quantity: use Edit first' : undefined}>
              {busy ? 'Creating…' : 'Create RFQ'}
            </button>
          </div>
        </>
      )}
    </div>
  );
}

function Panel({ onClose, status, onEditDraft, canDraft }) {
  const navigate = useNavigate();
  const fileRef = useRef(null);
  const screen = useScreenContext();
  const [saved] = useState(loadChat);
  const [lang, setLang] = useState(saved?.lang || 'en');
  const [messages, setMessages] = useState(saved?.messages || []);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [listening, setListening] = useState(false);
  const listRef = useRef(null);
  const inputRef = useRef(null);
  const recognition = useRef(null);
  const t = COPY[lang];

  useEffect(() => saveChat({ lang, messages }), [lang, messages]);
  useEffect(() => {
    const el = listRef.current;
    if (!el) return;
    if (typeof el.scrollTo === 'function') el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
    else el.scrollTop = el.scrollHeight;
  }, [messages, busy]);
  useEffect(() => { inputRef.current?.focus(); }, []);
  useEffect(() => () => recognition.current?.abort(), []);

  const send = useCallback(async (text) => {
    const message = text.trim();
    if (!message || busy) return;
    const history = messages.filter((m) => !m.error).slice(-MAX_HISTORY).map(({ role, text: t2 }) => ({ role, text: t2 }));
    setMessages((m) => [...m, { role: 'user', text: message }]);
    setDraft('');
    setBusy(true);
    try {
      const answer = await assistantApi.chat({
        message, history,
        purchaseRequestId: screen?.purchaseRequestId ?? null,
        quotationId: screen?.quotationId ?? null,
      });
      setMessages((m) => [...m, { role: 'assistant', text: answer.reply, links: answer.links || [], rfqDraft: answer.rfqDraft || null }]);
    } catch (err) {
      setMessages((m) => [...m, { role: 'assistant', text: errorMessage(err), error: true }]);
    } finally {
      setBusy(false);
    }
  }, [busy, messages, screen]);

  /** An RFQ file attached with the clip: read into a draft card, nothing saved. */
  async function attach(fileList) {
    const files = Array.from(fileList || []);
    if (fileRef.current) fileRef.current.value = '';
    if (!files.length || busy) return;
    setMessages((m) => [...m, { role: 'user', text: `Attached: ${files.map((f) => f.name).join(', ')}` }]);
    setBusy(true);
    try {
      const rfqDraft = await purchaseRequestsApi.draftFromFiles(files);
      const text = lang === 'hi'
        ? 'मैंने RFQ पढ़ लिया है। नीचे जाँचें और "Create RFQ" दबाएँ।'
        : 'I read the RFQ. Check the draft below and click "Create RFQ".';
      setMessages((m) => [...m, { role: 'assistant', text, rfqDraft }]);
    } catch (err) {
      setMessages((m) => [...m, { role: 'assistant', text: errorMessage(err), error: true }]);
    } finally {
      setBusy(false);
    }
  }

  function markCreated(index, pr) {
    setMessages((m) => m.map((msg, i) => (i === index ? { ...msg, created: { id: pr.id, requestNumber: pr.requestNumber } } : msg)));
  }

  function toggleListening() {
    if (listening) { recognition.current?.stop(); return; }
    const r = new SpeechRecognition();
    r.lang = lang === 'hi' ? 'hi-IN' : 'en-IN';
    r.interimResults = true;
    r.onresult = (e) => setDraft(Array.from(e.results).map((res) => res[0].transcript).join(''));
    r.onend = () => setListening(false);
    r.onerror = () => setListening(false);
    recognition.current = r;
    setListening(true);
    r.start();
  }

  function openLink(link) {
    navigate(link.type === 'QUOTATION' ? `/quotations/${link.id}` : `/purchase-requests/${link.id}`);
  }

  const disabled = status && !status.enabled;

  return (
    <motion.aside className="assistant" role="dialog" aria-label={t.title}
                  initial={{ x: '105%' }} animate={{ x: 0 }} exit={{ x: '105%' }}
                  transition={{ type: 'spring', stiffness: 320, damping: 34 }}>
      <header className="assistant-head">
        <span className="assistant-mark"><Icon name="sparkle" size={18} /></span>
        <div className="assistant-title">
          <strong>{t.title}</strong>
          <span>{t.subtitle}</span>
        </div>
        <div className="lang-toggle" role="group" aria-label="Language">
          <button type="button" className={lang === 'en' ? 'on' : ''} onClick={() => setLang('en')}>EN</button>
          <button type="button" className={lang === 'hi' ? 'on' : ''} onClick={() => setLang('hi')}>हिं</button>
        </div>
        <button type="button" className="assistant-icon-btn" onClick={onClose} aria-label="Close assistant">
          <Icon name="close" size={18} />
        </button>
      </header>

      {screen && <div className="assistant-context"><Icon name="file" size={14} /> Looking at {screen.label}</div>}

      <div className="assistant-body" ref={listRef}>
        <div className="msg msg-bot">
          <span className="msg-avatar msg-avatar-bot"><Icon name="sparkle" size={14} /></span>
          <div className="msg-bubble"><p>{t.hello}</p></div>
        </div>
        {disabled && (
          <div className="notice notice-warning assistant-off" role="status">
            <span>{status.reason}</span>
          </div>
        )}
        {messages.map((m, i) => (
          <div key={i} className={`msg ${m.role === 'user' ? 'msg-user' : 'msg-bot'}${m.error ? ' msg-error' : ''}`}>
            {m.role !== 'user' && <span className="msg-avatar msg-avatar-bot"><Icon name="sparkle" size={14} /></span>}
            <div className="msg-bubble">
              <Rich text={m.text} />
              {m.rfqDraft && (
                <RfqDraftCard draft={m.rfqDraft} created={m.created} onCreated={(pr) => markCreated(i, pr)}
                              onEdit={(d) => onEditDraft(d, (pr) => markCreated(i, pr))} />
              )}
              {m.links?.length > 0 && (
                <div className="msg-links">
                  {m.links.map((l) => (
                    <button key={l.type + l.id} type="button" className="chip" onClick={() => openLink(l)}>
                      <Icon name={l.type === 'QUOTATION' ? 'file' : 'contracts'} size={14} /> {l.label}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>
        ))}
        {busy && (
          <div className="msg msg-bot">
            <span className="msg-avatar msg-avatar-bot"><Icon name="sparkle" size={14} /></span>
            <div className="msg-bubble msg-typing"><span /><span /><span /> <em>{t.thinking}</em></div>
          </div>
        )}
        {messages.length === 0 && !disabled && (
          <div className="assistant-suggest">
            {t.suggestions.map((s) => (
              <button key={s} type="button" className="chip" onClick={() => send(s)}>{s}</button>
            ))}
          </div>
        )}
      </div>

      <form className="assistant-input" onSubmit={(e) => { e.preventDefault(); send(draft); }}>
        {messages.length > 0 && (
          <button type="button" className="assistant-icon-btn" onClick={() => setMessages([])} title={t.clear} aria-label={t.clear}>
            <Icon name="trash" size={17} />
          </button>
        )}
        {canDraft && (
          <>
            <input ref={fileRef} type="file" multiple hidden accept={ACCEPTED_FILES} aria-hidden="true"
                   onChange={(e) => attach(e.target.files)} />
            <button type="button" className="assistant-icon-btn" onClick={() => fileRef.current?.click()}
                    disabled={disabled || busy} title="Attach an RFQ file" aria-label="Attach an RFQ file">
              <Icon name="file" size={17} />
            </button>
          </>
        )}
        <textarea ref={inputRef} rows={1} value={draft} placeholder={t.placeholder} disabled={disabled}
                  maxLength={20000} aria-label={t.placeholder} onChange={(e) => setDraft(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(draft); } }} />
        {SpeechRecognition && (
          <button type="button" className={`assistant-icon-btn${listening ? ' listening' : ''}`} onClick={toggleListening}
                  disabled={disabled} title={t.listen} aria-label={t.listen} aria-pressed={listening}>
            <Icon name="mic" size={18} />
          </button>
        )}
        <button type="submit" className="assistant-send" disabled={disabled || busy || !draft.trim()} aria-label="Send">
          <Icon name="send" size={17} />
        </button>
      </form>
    </motion.aside>
  );
}

/** `canDraft`: the user may create RFQs, so the chat offers the clip and RFQ draft cards. */
export function AssistantProvider({ enabled, canDraft = false, children }) {
  const [open, setOpen] = useState(false);
  const [status, setStatus] = useState(null);
  const [editing, setEditing] = useState(null); // { draft, onCreated } while the full New RFQ window is open
  const navigate = useNavigate();

  useEffect(() => {
    if (!enabled) return undefined;
    let live = true;
    assistantApi.status().then((s) => live && setStatus(s)).catch(() => live && setStatus(null));
    return () => { live = false; };
  }, [enabled]);

  useEffect(() => {
    if (!open) return undefined;
    const esc = (e) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('keydown', esc);
    return () => document.removeEventListener('keydown', esc);
  }, [open]);

  const value = useMemo(() => ({ open, setOpen, available: enabled }), [open, enabled]);
  const editDraft = useCallback((draft, onCreated) => { setOpen(false); setEditing({ draft, onCreated }); }, []);
  return (
    <AssistantContext.Provider value={value}>
      {children}
      <AnimatePresence>
        {enabled && open && (
          <>
            <motion.div key="scrim" className="assistant-scrim" onClick={() => setOpen(false)}
                        initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} />
            <Panel key="panel" status={status} onClose={() => setOpen(false)} onEditDraft={editDraft} canDraft={canDraft} />
          </>
        )}
      </AnimatePresence>
      {editing && (
        <NewRfqModal initialDraft={editing.draft} onClose={() => setEditing(null)} onCreated={(pr) => {
          editing.onCreated(pr);
          setEditing(null);
          navigate(`/purchase-requests/${pr.id}`);
        }} />
      )}
    </AssistantContext.Provider>
  );
}

/** The topbar button. */
export function AssistantButton() {
  const { setOpen, available } = useAssistant();
  if (!available) return null;
  return (
    <button type="button" className="ask-btn" onClick={() => setOpen(true)}>
      <Icon name="sparkle" size={16} /> <span>Ask Sahayak</span>
    </button>
  );
}

/** The sidebar help card, after example 1's "Need any help?" card. */
export function AssistantHelpCard() {
  const { setOpen, available } = useAssistant();
  if (!available) return null;
  return (
    <div className="help-card">
      <span className="help-kicker">Hindi · English</span>
      <strong>Need any help?</strong>
      <span className="help-sub">पूछिए, किसे रिव्यू चाहिए</span>
      <button type="button" onClick={() => setOpen(true)}><Icon name="chat" size={14} /> Chat</button>
      <span className="help-orb" aria-hidden="true"><Icon name="sparkle" size={34} strokeWidth={1.4} /></span>
    </div>
  );
}

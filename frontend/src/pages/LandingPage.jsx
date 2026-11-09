import { useState } from 'react';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useAuth } from '../auth/AuthContext.jsx';
import Icon from '../components/Icon.jsx';
import Logo from '../components/Logo.jsx';
import Scene3D from '../components/three/Scene3D.jsx';
import { homePath } from '../utils/permissions.js';
import './landing.css';

/**
 * Public landing page. Layout patterns come from two reference designs (a travel planner with a photo banner,
 * pill tabs, a dashed-card timeline and a chat column; a risk dashboard with stat tiles, a gradient score tile,
 * count pills and a dark highlight card), rendered in SUTRA's red, black and white. The product previews use the
 * sample RFQ from ai/test-data and are labelled as samples.
 */

const ease = [0.22, 1, 0.36, 1];
const reveal = {
  initial: { opacity: 0, y: 28 },
  whileInView: { opacity: 1, y: 0 },
  viewport: { once: true, margin: '-60px' },
  transition: { duration: 0.7, ease },
};

const STEPS = [
  { icon: 'contracts', title: 'Raise the RFQ', text: 'Purchase request with items, quantities, units and specifications.' },
  { icon: 'upload', title: 'Collect quotations', text: 'Upload PDF, Excel or Word files, or let vendors submit through their portal.' },
  { icon: 'sparkle', title: 'Extract and flag', text: 'Groq AI (with local OCR for scans) or the rule-based reader extracts the lines; anything uncertain is flagged.' },
  { icon: 'check', title: 'Review', text: 'Correct values, accept warnings, cross-check engines, mark ready.' },
  { icon: 'reports', title: 'Compare like for like', text: 'Side-by-side matrix, recalculated totals, advisory vendor grade.' },
  { icon: 'award', title: 'Award with a reason', text: 'An authorised manager decides. Who, when and why go into the audit log.' },
];

const FEATURES = [
  { icon: 'file', title: 'Any quotation format', text: 'PDF, Excel and Word. Scanned PDFs are read on your own server with Tesseract OCR. Several files can form one quotation; conflicting figures are flagged.' },
  { icon: 'shield', title: 'Totals you can trust', text: 'The backend recalculates every line and total and flags each difference from the printed figures.' },
  { icon: 'layers', title: 'Two extraction engines', text: 'Groq AI when configured, a rule-based reader otherwise. Cross-check shows both readings side by side.' },
  { icon: 'globe', title: 'Hindi + English assistant', text: 'Ask “किन कोटेशन को रिव्यू चाहिए?” or “which vendor is lowest?” and get an answer from live data.' },
  { icon: 'compliance', title: 'Nothing auto-approved', text: 'AI assists; people decide. A reason is required when the award is not the lowest complete quotation.' },
  { icon: 'vendors', title: 'Vendor portal', text: 'Suppliers see open RFQs and only their own quotations, and can submit or withdraw them.' },
];

const ROLES = [
  { role: 'Buyer', text: 'Raises RFQs, uploads and reviews quotations, generates comparisons.' },
  { role: 'Manager', text: 'Reads everything and records the award; never on their own request.' },
  { role: 'Auditor', text: 'Award register and the full audit trail, read-only.' },
  { role: 'Administrator', text: 'Entities, customers, vendors, users and roles.' },
];

const CHART = {
  '12 months': [8, 10, 9, 14, 12, 18, 15, 13, 20, 24, 22, 28],
  '30 days': [3, 4, 4, 7, 5, 2, 2, 5, 6, 9, 10, 12],
  '1 week': [2, 3, 2, 5, 4, 6, 7],
};

function Sparkline({ values }) {
  const w = 520;
  const h = 150;
  const max = Math.max(...values) * 1.15;
  const pts = values.map((v, i) => [(i / (values.length - 1)) * (w - 20) + 10, h - (v / max) * (h - 20) - 10]);
  const d = pts.map(([x, y], i) => {
    if (i === 0) return `M${x},${y}`;
    const [px, py] = pts[i - 1];
    const cx = (px + x) / 2;
    return `C${cx},${py} ${cx},${y} ${x},${y}`;
  }).join(' ');
  const [lx, ly] = pts[pts.length - 1];
  return (
    <svg className="lp-spark" viewBox={`0 0 ${w} ${h}`} role="img" aria-label="Sample chart of quotations received">
      <defs>
        <linearGradient id="lp-spark-stroke" x1="0" x2="1">
          <stop offset="0" stopColor="#000000" />
          <stop offset="1" stopColor="#ff0000" />
        </linearGradient>
        <linearGradient id="lp-spark-fill" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor="#ff0000" stopOpacity=".16" />
          <stop offset="1" stopColor="#ff0000" stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`${d} L${lx},${h} L10,${h} Z`} fill="url(#lp-spark-fill)" />
      <motion.path key={values.join()} d={d} fill="none" stroke="url(#lp-spark-stroke)" strokeWidth="4" strokeLinecap="round"
                   initial={{ pathLength: 0 }} animate={{ pathLength: 1 }} transition={{ duration: 1.1, ease }} />
      <circle cx={lx} cy={ly} r="7" fill="#fff" stroke="#ff0000" strokeWidth="3.5" />
    </svg>
  );
}

/** Example 1's app window: banner, title, pill tabs, dashed timeline cards and the chat column. */
function RfqWindow() {
  return (
    <div className="lp-window" aria-label="Sample RFQ in SUTRA">
      <div className="lp-window-bar"><span /><span /><span /><em>sutra.local/purchase-requests/PR-2026-00001</em></div>
      <div className="lp-window-body">
        <div className="lp-window-main">
          <div className="lp-banner">
            <svg className="lp-banner-threads" viewBox="0 0 400 120" preserveAspectRatio="none" aria-hidden="true">
              {[0, 1, 2, 3, 4].map((i) => (
                <path key={i} d={`M0 ${30 + i * 18} C 120 ${-10 + i * 25}, 260 ${130 - i * 20}, 400 ${40 + i * 14}`} />
              ))}
            </svg>
            <span className="lp-banner-pill"><Icon name="vendors" size={13} /> 3 vendors invited</span>
            <span className="lp-banner-pill"><Icon name="arrow" size={13} /> Share</span>
          </div>
          <div className="lp-rfq-title">Production materials <span className="lp-edit">✎</span></div>
          <div className="lp-tabs">
            <span className="on">Quotations</span><span>Items</span><span>Comparison</span><span>Award</span>
          </div>
          {[
            { vendor: 'Shakti Metals', files: 'PDF · Excel', status: 'Needs review', hot: true },
            { vendor: 'Vertex Auto', files: 'PDF · Excel', status: 'Ready' },
          ].map((q) => (
            <div key={q.vendor} className="lp-tl-row">
              <span className="lp-tl-dot" />
              <div className={`lp-tl-card${q.hot ? ' hot' : ''}`}>
                <span className="lp-tl-thumb"><Icon name="file" size={16} /></span>
                <div>
                  <strong>{q.vendor}</strong>
                  <small><Icon name="clock" size={12} /> {q.files}</small>
                </div>
                <span className={`lp-tl-status${q.hot ? ' hot' : ''}`}>{q.status}</span>
              </div>
            </div>
          ))}
          <div className="lp-chips">
            <span><Icon name="upload" size={13} /> Upload</span><span><Icon name="layers" size={13} /> Cross-check</span>
            <span><Icon name="reports" size={13} /> Compare</span>
          </div>
        </div>
        <div className="lp-window-chat">
          <div className="lp-bubble user"><span className="lp-av">P</span>Shakti Metals वाले कोटेशन पर फ़्लैग क्यों है?</div>
          <div className="lp-bubble bot">
            <span className="lp-av bot"><Icon name="sparkle" size={12} /></span>
            <div>
              <p>इस कोटेशन पर <b>2 खुले फ़्लैग</b> हैं:</p>
              <p>• एक लाइन का टोटल छपे हुए आँकड़े से मेल नहीं खाता।</p>
              <p>• वारंटी की शर्त नहीं दी गई है।</p>
              <p>Quotation पेज पर फ़्लैग देखकर सुधारें या स्वीकार करें।</p>
            </div>
          </div>
          <div className="lp-ask"><Icon name="chat" size={13} /> कुछ भी पूछें… <span><Icon name="mic" size={13} /></span><b><Icon name="send" size={12} /></b></div>
        </div>
      </div>
    </div>
  );
}

/** Example 2's detail card: count pills, tiles, the gradient score tile, a dark card and a line chart. */
function QuotationPreview() {
  const [range, setRange] = useState('30 days');
  return (
    <div className="lp-preview">
      <div className="lp-preview-head">
        <div className="lp-crumbs">RFQs <i>/</i> PR-2026-00001 <i>/</i> Quotations <i>/</i> <b>Details</b></div>
        <div className="lp-countpills"><span>Flags <b>2</b></span><span>Files <b>2</b></span><span><Icon name="bell" size={14} /> <b>1</b></span></div>
      </div>
      <h3 className="lp-preview-title">Q-114: Shakti Metals, Production Materials</h3>
      <div className="lp-stats">
        <div className="lp-stat"><small>Formats</small><strong>PDF · Excel</strong></div>
        <div className="lp-stat"><small>Received</small><strong>Nov 3, 2026</strong></div>
        <div className="lp-stat"><small>Status</small><strong><span className="lp-dot" /> Needs review</strong></div>
        <div className="lp-stat lp-stat-grad"><small>Extraction grade</small><strong>A · 92 / 100</strong></div>
      </div>
      <div className="lp-preview-grid">
        <div className="lp-card">
          <div className="lp-pilltabs">
            <span className="on">Items</span><span>Flags <b>2</b></span><span>Files <b>2</b></span><span>History</span>
            <em className="lp-risk">Needs review</em>
          </div>
          <p className="lp-desc">
            Every line is matched to the requested item by code or description. Totals are recalculated by the
            backend and compared with the printed figures; the reviewer accepts or fixes each flag.
          </p>
          <div className="lp-tiles">
            <div className="lp-tile"><small>Lines matched</small><strong>12 / 12</strong></div>
            <div className="lp-tile soft"><small>Open flags</small><strong>2</strong><i /></div>
            <div className="lp-tile pink"><small>Total difference</small><strong>₹ 1,180.00</strong><i /></div>
            <div className="lp-tile"><small>Calculated by</small><strong>Backend, not AI</strong></div>
          </div>
        </div>
        <div className="lp-side">
          <div className="lp-dark">
            <small>Lowest complete quotation</small>
            <strong>Vertex Auto</strong>
            <div><span>Price rank</span><b>1</b></div>
          </div>
          <div className="lp-light"><span>Vendors compared</span><b>3</b></div>
        </div>
      </div>
      <div className="lp-card lp-chart">
        <div className="lp-chart-head">
          <h4>Quotations received</h4>
          <div className="lp-seg" role="tablist">
            {Object.keys(CHART).map((k) => (
              <button key={k} type="button" role="tab" aria-selected={range === k} className={range === k ? 'on' : ''}
                      onClick={() => setRange(k)}>{k}</button>
            ))}
          </div>
        </div>
        <Sparkline values={CHART[range]} />
      </div>
      <p className="lp-sample">Sample data from the automotive test pack</p>
    </div>
  );
}

export default function LandingPage() {
  const { user } = useAuth();
  const cta = user
    ? { to: homePath(user), label: 'Open SUTRA' }
    : { to: '/login', label: 'Sign in' };

  return (
    <div className="lp">
      <header className="lp-nav">
        <Link to="/welcome" className="lp-logo"><Logo size={34} wordmark dark={false} /></Link>
        <nav>
          <a href="#how">How it works</a>
          <a href="#features">Features</a>
          <a href="#assistant">Assistant</a>
          <a href="#roles">Roles</a>
        </nav>
        <Link to={cta.to} className="lp-btn lp-btn-primary">{cta.label} <Icon name="arrow" size={16} /></Link>
      </header>

      <section className="lp-hero">
        <motion.div className="lp-hero-copy" initial={{ opacity: 0, y: 30 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.8, ease }}>
          <span className="lp-eyebrow"><Icon name="sparkle" size={14} /> Vendor quotation management</span>
          <h1>Every vendor quotation, <span>read, checked and compared</span> in one place.</h1>
          <p>
            Upload quotations as PDF, Excel or Word. SUTRA extracts the details, recalculates every total, flags
            what is uncertain and lines vendors up side by side, so your team can award with confidence and a full
            audit trail.
          </p>
          <div className="lp-hero-ctas">
            <Link to={cta.to} className="lp-btn lp-btn-primary lp-btn-lg">{cta.label} <Icon name="arrow" size={18} /></Link>
            <a href="#how" className="lp-btn lp-btn-ghost lp-btn-lg">See how it works</a>
          </div>
          <ul className="lp-trust">
            <li><Icon name="check" size={15} /> Totals by the backend, never by AI</li>
            <li><Icon name="check" size={15} /> Nothing approved automatically</li>
            <li><Icon name="check" size={15} /> हिंदी + English assistant</li>
          </ul>
        </motion.div>
        <motion.div className="lp-hero-visual" initial={{ opacity: 0, y: 40, rotateX: 8 }} animate={{ opacity: 1, y: 0, rotateX: 0 }}
                    transition={{ duration: 1, ease, delay: 0.15 }}>
          <div className="lp-hero-scene"><Scene3D compact /></div>
          <RfqWindow />
        </motion.div>
      </section>

      <motion.section className="lp-strip" {...reveal}>
        <div><small>Formats</small><strong>PDF · Excel · Word</strong></div>
        <div><small>Extraction</small><strong>Groq AI + OCR</strong></div>
        <div><small>Status</small><strong><span className="lp-dot" /> 5 tracked stages</strong></div>
        <div className="lp-strip-grad"><small>Audit trail</small><strong>Every change, every award</strong></div>
      </motion.section>

      <section id="how" className="lp-section">
        <motion.div className="lp-section-head" {...reveal}>
          <span className="lp-eyebrow">How it works</span>
          <h2>From RFQ to award in six steps</h2>
          <p>The same workflow your team already follows, with the reading and arithmetic done for you.</p>
        </motion.div>
        <ol className="lp-steps">
          {STEPS.map((s, i) => (
            <motion.li key={s.title} {...reveal} transition={{ ...reveal.transition, delay: i * 0.06 }}>
              <span className="lp-step-icon"><Icon name={s.icon} size={20} /></span>
              <span className="lp-step-no">{String(i + 1).padStart(2, '0')}</span>
              <h3>{s.title}</h3>
              <p>{s.text}</p>
            </motion.li>
          ))}
        </ol>
      </section>

      <section className="lp-section lp-section-tint">
        <motion.div className="lp-section-head" {...reveal}>
          <span className="lp-eyebrow">Review screen</span>
          <h2>See what needs a human, at a glance</h2>
          <p>Extraction quality, open flags and the lowest complete quotation, without opening a single file.</p>
        </motion.div>
        <motion.div {...reveal}><QuotationPreview /></motion.div>
      </section>

      <section id="features" className="lp-section">
        <motion.div className="lp-section-head" {...reveal}>
          <span className="lp-eyebrow">Features</span>
          <h2>Built for procurement teams who need to show their working</h2>
        </motion.div>
        <div className="lp-features">
          {FEATURES.map((f, i) => (
            <motion.article key={f.title} className="lp-feature" {...reveal} transition={{ ...reveal.transition, delay: (i % 3) * 0.08 }}>
              <span className="lp-feature-icon"><Icon name={f.icon} size={20} /></span>
              <h3>{f.title}</h3>
              <p>{f.text}</p>
            </motion.article>
          ))}
        </div>
      </section>

      <section id="assistant" className="lp-section lp-assistant">
        <motion.div className="lp-assistant-copy" {...reveal}>
          <span className="lp-eyebrow"><Icon name="globe" size={14} /> SUTRA Sahayak</span>
          <h2>Ask in Hindi or English. Get answers from live data.</h2>
          <p>
            The assistant reads the requests and quotations you can already see and answers in the language you
            write: Hindi, Hinglish or English. You can also speak your question. It points you to the exact record
            and screen, and it is read-only: it never changes or approves anything.
          </p>
          <ul className="lp-trust lp-trust-col">
            <li><Icon name="check" size={15} /> Knows which RFQ or quotation is on screen</li>
            <li><Icon name="check" size={15} /> Quotes backend totals, never its own arithmetic</li>
            <li><Icon name="check" size={15} /> Voice input in hi-IN and en-IN</li>
          </ul>
        </motion.div>
        <motion.div className="lp-chatdemo" {...reveal}>
          <div className="lp-bubble user"><span className="lp-av">A</span>Which quotations need review today?</div>
          <div className="lp-bubble bot">
            <span className="lp-av bot"><Icon name="sparkle" size={12} /></span>
            <div>
              <p><b>2 quotations</b> need review:</p>
              <p>• Shakti Metals (PR-2026-00001): 2 open flags</p>
              <p>• Pioneer Industrial (PR-2026-00001): 1 open flag</p>
              <div className="lp-linkchips"><span><Icon name="file" size={12} /> Shakti Metals</span><span><Icon name="file" size={12} /> Pioneer Industrial</span></div>
            </div>
          </div>
          <div className="lp-bubble user"><span className="lp-av">A</span>sabse sasta complete quotation kiska hai?</div>
          <div className="lp-bubble bot">
            <span className="lp-av bot"><Icon name="sparkle" size={12} /></span>
            <div><p>Latest comparison ke hisaab se <b>Vertex Auto</b> rank 1 par hai. Final award manager hi karenge.</p></div>
          </div>
          <div className="lp-ask"><Icon name="chat" size={13} /> Ask anything… <span><Icon name="mic" size={13} /></span><b><Icon name="send" size={12} /></b></div>
          <p className="lp-sample">Illustrative conversation</p>
        </motion.div>
      </section>

      <section id="roles" className="lp-section">
        <motion.div className="lp-section-head" {...reveal}>
          <span className="lp-eyebrow">Roles</span>
          <h2>Everyone sees exactly what they need</h2>
          <p>Enforced by every backend service, not just hidden in the interface.</p>
        </motion.div>
        <div className="lp-roles">
          {ROLES.map((r, i) => (
            <motion.div key={r.role} className="lp-role" {...reveal} transition={{ ...reveal.transition, delay: i * 0.06 }}>
              <strong>{r.role}</strong><p>{r.text}</p>
            </motion.div>
          ))}
        </div>
      </section>

      <motion.section className="lp-cta" {...reveal}>
        <div>
          <h2>Ready to compare your next RFQ?</h2>
          <p>Sign in with your SUTRA account. Suppliers use the same page to reach the vendor portal.</p>
        </div>
        <Link to={cta.to} className="lp-btn lp-btn-white lp-btn-lg">{cta.label} <Icon name="arrow" size={18} /></Link>
      </motion.section>

      <footer className="lp-foot">
        <Logo size={26} wordmark dark={false} />
        <span>Vendor quotation management · AI assists; people decide.</span>
      </footer>
    </div>
  );
}

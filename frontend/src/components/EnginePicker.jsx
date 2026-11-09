import { extractionApi } from '../api/endpoints.js';
import { useApi } from '../hooks/useApi.js';

/**
 * Chooses how a quotation is read: Automatic (Groq, then the rule-based reader if Groq fails or is rate-limited),
 * one particular free Groq model, or the rule-based reader (no AI). Scanned PDFs are read by local OCR (Tesseract)
 * before Groq sees them. The value is a string such as "AUTO", "RULES" or "GROQ:openai/gpt-oss-20b";
 * {@link parseChoice} turns it into API parameters.
 */
export default function EnginePicker({ value, onChange, id = 'engine-picker' }) {
  const engines = useApi(() => extractionApi.engines(), []);
  const list = engines.data?.engines || [];
  const groq = list.find((e) => e.engine === 'GROQ');
  const rules = list.find((e) => e.engine === 'RULES');

  return (
    <select id={id} value={value} onChange={(e) => onChange(e.target.value)} aria-label="Extraction engine">
      <option value="AUTO">Automatic: Groq AI, then rule-based</option>
      {groq && (
        <optgroup label={groq.available ? 'Groq AI (free models, OCR for scans)' : 'Groq AI (not configured)'}>
          {(groq.models || []).map((m, i) => (
            <option key={m} value={i === 0 ? 'GROQ' : `GROQ:${m}`} disabled={!groq.available}>
              Groq · {m}{i === 0 ? ' (default)' : ''}
            </option>
          ))}
        </optgroup>
      )}
      {rules && (
        <option value="RULES" disabled={!rules.available}>
          Rule-based reader (no AI){rules.available ? '' : ' (document service down)'}
        </option>
      )}
    </select>
  );
}

/** "GROQ:openai/gpt-oss-20b" -> { engine: 'GROQ', model: 'openai/gpt-oss-20b' }. */
export function parseChoice(value) {
  const [engine, ...model] = String(value || 'AUTO').split(':');
  return { engine, model: model.length ? model.join(':') : null };
}

export function engineName(engine) {
  return { GROQ: 'Groq AI', GEMINI: 'Gemini (legacy)', RULES: 'Rule-based', AUTO: 'Automatic' }[parseChoice(engine).engine] || engine;
}

import { extractionApi } from '../api/endpoints.js';
import { useApi } from '../hooks/useApi.js';

/**
 * Chooses how a quotation is read: Automatic (Gemini, then the rule-based reader if Gemini fails or is
 * rate-limited), one particular free Gemini model, or the rule-based reader (no AI). The value is a string such as
 * "AUTO", "RULES" or "GEMINI:gemini-3.5-flash-lite"; {@link parseChoice} turns it into API parameters.
 */
export default function EnginePicker({ value, onChange, id = 'engine-picker' }) {
  const engines = useApi(() => extractionApi.engines(), []);
  const list = engines.data?.engines || [];
  const gemini = list.find((e) => e.engine === 'GEMINI');
  const rules = list.find((e) => e.engine === 'RULES');

  return (
    <select id={id} value={value} onChange={(e) => onChange(e.target.value)} aria-label="Extraction engine">
      <option value="AUTO">Automatic: Gemini, then rule-based</option>
      {gemini && (
        <optgroup label={gemini.available ? 'Google Gemini (free models)' : 'Google Gemini (not configured)'}>
          {(gemini.models || []).map((m, i) => (
            <option key={m} value={i === 0 ? 'GEMINI' : `GEMINI:${m}`} disabled={!gemini.available}>
              Gemini · {m}{i === 0 ? ' (default)' : ''}
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

/** "GEMINI:gemini-3.5-flash-lite" -> { engine: 'GEMINI', geminiModel: 'gemini-3.5-flash-lite' }. */
export function parseChoice(value) {
  const [engine, ...model] = String(value || 'AUTO').split(':');
  return { engine, geminiModel: model.length ? model.join(':') : null };
}

export function engineName(engine) {
  return { GEMINI: 'Gemini', RULES: 'Rule-based', AUTO: 'Automatic' }[parseChoice(engine).engine] || engine;
}

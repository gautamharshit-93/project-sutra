import { useRef } from 'react';
import { formatBytes } from '../utils/format.js';

export const ACCEPTED_FILES = '.pdf,.xlsx,.xls,.docx';
const MAX_FILES = 10;

/**
 * Picks one or more quotation files (PDF, Excel, Word). Files can be added in several rounds and removed again;
 * the same file is not added twice.
 */
export default function FilePicker({ files, onChange, max = MAX_FILES, label = 'Add files' }) {
  const inputRef = useRef(null);

  function add(list) {
    const next = [...files];
    for (const f of list) {
      if (!next.some((x) => x.name === f.name && x.size === f.size) && next.length < max) next.push(f);
    }
    onChange(next);
    if (inputRef.current) inputRef.current.value = '';
  }

  return (
    <div className="file-picker">
      <input ref={inputRef} type="file" multiple accept={ACCEPTED_FILES} aria-label={label}
             onChange={(e) => add(Array.from(e.target.files || []))} />
      {files.length > 0 && (
        <ul className="file-list">
          {files.map((f) => (
            <li key={`${f.name}-${f.size}`}>
              <span className={`file-type file-type-${f.name.split('.').pop().toLowerCase()}`}>
                {f.name.split('.').pop().toUpperCase()}
              </span>
              <span className="file-name">{f.name}</span>
              <span className="muted small">{formatBytes(f.size)}</span>
              <button type="button" className="icon-btn" aria-label={`Remove ${f.name}`}
                      onClick={() => onChange(files.filter((x) => x !== f))}>×</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

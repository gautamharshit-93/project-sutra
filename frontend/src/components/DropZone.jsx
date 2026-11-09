import { useRef, useState } from 'react';
import Icon from './Icon.jsx';
import { ACCEPTED_FILES } from './FilePicker.jsx';

/**
 * One place to drop documents: drag files onto it, or click to browse. Calls onFiles(File[]) with the accepted
 * types only (PDF, scanned PDF, Word, Excel).
 */
export default function DropZone({ onFiles, busy, title, hint, compact = false, max = 5 }) {
  const inputRef = useRef(null);
  const [over, setOver] = useState(false);
  const accepted = ACCEPTED_FILES.split(',');

  function take(list) {
    const files = Array.from(list || []).filter((f) => accepted.some((ext) => f.name.toLowerCase().endsWith(ext)));
    if (inputRef.current) inputRef.current.value = '';
    if (files.length) onFiles(files.slice(0, max));
  }

  return (
    <div
      className={`dropzone${over ? ' dropzone-over' : ''}${busy ? ' dropzone-busy' : ''}${compact ? ' dropzone-compact' : ''}`}
      role="button" tabIndex={0} aria-label={title} aria-busy={busy || undefined}
      onClick={() => !busy && inputRef.current?.click()}
      onKeyDown={(e) => { if ((e.key === 'Enter' || e.key === ' ') && !busy) { e.preventDefault(); inputRef.current?.click(); } }}
      onDragOver={(e) => { e.preventDefault(); if (!busy) setOver(true); }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => { e.preventDefault(); setOver(false); if (!busy) take(e.dataTransfer.files); }}
    >
      <input ref={inputRef} type="file" multiple accept={ACCEPTED_FILES} hidden aria-hidden="true"
             onChange={(e) => take(e.target.files)} />
      <span className="dropzone-icon">{busy ? <span className="dropzone-spin" /> : <Icon name="upload" size={compact ? 18 : 26} />}</span>
      <span className="dropzone-text">
        <strong>{title}</strong>
        {hint && <span>{hint}</span>}
      </span>
    </div>
  );
}

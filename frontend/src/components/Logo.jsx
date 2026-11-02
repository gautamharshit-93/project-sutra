/**
 * The SUTRA mark: a light-red hex nut (automotive hardware) with a red "S" on its white face. Pass `wordmark` to add
 * the name beside it.
 */
export default function Logo({ size = 38, wordmark = false, subtitle, dark = true }) {
  const mark = (
    <svg className="brand-logo" width={size} height={size} viewBox="0 0 64 64" role="img" aria-label="SUTRA logo">
      <polygon points="18,6 46,6 60,32 46,58 18,58 4,32" fill="#f87171" stroke={dark ? '#fda4af' : '#e11d48'} strokeWidth="2" />
      <circle cx="32" cy="32" r="18.5" fill="#fff" />
      <circle cx="32" cy="32" r="18.5" fill="none" stroke="#e11d48" strokeWidth="1.5" strokeDasharray="2 3" opacity=".6" />
      <path d="M40.5 23.5c-2.4-3.2-14-4.4-15 1.6-1 6.4 16.4 5.2 15.2 12.4-1 6-12.8 5.6-16.2 1.4"
            fill="none" stroke="#e11d48" strokeWidth="5.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
  if (!wordmark) return mark;
  return (
    <div className="brand">
      {mark}
      <div>
        <div className="brand-name">SUTRA</div>
        {subtitle && <div className="brand-sub">{subtitle}</div>}
      </div>
    </div>
  );
}

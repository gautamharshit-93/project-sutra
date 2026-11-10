import { formatNumber } from '../utils/format.js';

const GRADE_TONES = { A: 'success', B: 'success', C: 'warning', D: 'warning', F: 'danger' };

/** A letter grade with its score, e.g. "B 78/100". `report` is a GradeReport from the backend. */
export function GradeBadge({ report, title }) {
  if (!report) return null;
  return (
    <span className={`badge badge-${GRADE_TONES[report.grade] || 'neutral'} grade-badge`} title={title}>
      {report.grade} <span className="grade-score">{report.score}/100</span>
    </span>
  );
}

/** Every rule of a GradeReport: points earned, maximum and why, so the grade can always be explained. */
export function GradeRules({ report }) {
  if (!report) return null;
  return (
    <table className="table table-compact grade-rules">
      <thead><tr><th>Rule</th><th className="num">Points</th><th>Why</th></tr></thead>
      <tbody>
        {report.rules.map((r) => (
          <tr key={r.code} className={Number(r.points) < r.maxPoints ? 'rule-short' : ''}>
            <td>{r.label}</td>
            <td className="num">{formatNumber(r.points, 1)} / {r.maxPoints}</td>
            <td className="small">{r.detail}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}


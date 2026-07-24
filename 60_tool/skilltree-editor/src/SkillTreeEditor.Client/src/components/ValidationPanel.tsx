import type { ValidationReport } from '../types/editor'

interface ValidationPanelProps {
  report: ValidationReport | null
  validating: boolean
  onValidate: () => void
  onValidateAll: () => void
}

export function ValidationPanel({ report, validating, onValidate, onValidateAll }: ValidationPanelProps) {
  const errors = report?.issues.filter((issue) => issue.severity === 'error').length ?? 0
  const warnings = report?.issues.filter((issue) => issue.severity === 'warning').length ?? 0
  return (
    <section className="validation-panel panel-section">
      <div className="section-title">
        <h2>検証</h2>
        <span className={errors ? 'badge error' : 'badge ok'}>{errors} error</span>
        <span className={warnings ? 'badge warning' : 'badge'}>{warnings} warning</span>
      </div>
      <div className="button-row">
        <button className="button" onClick={onValidate} disabled={validating}>編集中を検証</button>
        <button className="button subtle" onClick={onValidateAll} disabled={validating}>保存済み全体</button>
      </div>
      {validating && <p className="muted">検証中…</p>}
      {!validating && report?.issues.length === 0 && <p className="success-message">問題はありません。</p>}
      <ol className="issue-list">
        {report?.issues.map((issue, index) => (
          <li key={`${issue.code}-${issue.path}-${index}`} className={issue.severity}>
            <strong>{issue.code}</strong>
            <span>{issue.message}</span>
            {(issue.file || issue.path) && <small>{[issue.file, issue.path].filter(Boolean).join(' ')}</small>}
          </li>
        ))}
      </ol>
    </section>
  )
}

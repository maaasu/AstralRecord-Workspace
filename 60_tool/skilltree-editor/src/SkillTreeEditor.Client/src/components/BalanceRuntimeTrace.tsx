import type { BalanceTrial } from '../data/balanceLab'

const names: Record<string, string> = { GUARD: 'GUARD', RIPOSTE: '剣気', SPIRIT_MARK: '霊印', PHOENIX: '不死鳥力', PHOENIX_ACTIVE: '不死鳥活動中', WRAITH_READY: '幻影矢の準備', BOUND_UNTIL: '拘束終了tick' }
export function BalanceRuntimeTrace({ trial }: { trial: BalanceTrial }) {
  const points = trial.resources ?? []
  const keys = [...new Set(points.flatMap(point => Object.keys(point.custom ?? {})))].filter(key => !key.endsWith('_UNTIL'))
  if (!keys.length) return null
  const maxTick = Math.max(1, ...points.map(point => point.tick))
  return <details className="balance-runtime-trace" open><summary>専用リソースの推移</summary>
    <div className="balance-runtime-charts">{keys.map(key => { const max = Math.max(1, ...points.map(point => point.custom?.[key] ?? 0)); return <figure key={key}><figcaption>{names[key] ?? key}（縦軸 0〜{max}）</figcaption><svg role="img" aria-label={`${names[key] ?? key}の推移`} viewBox="0 0 300 90"><polyline stroke="#91c9ef" strokeWidth="2" fill="none" points={points.map(point => `${10 + point.tick / maxTick * 280},${80 - (point.custom?.[key] ?? 0) / max * 70}`).join(' ')} /></svg></figure> })}</div>
    <div className="balance-scroll"><table><caption>1秒ごとの記録と最終状態</caption><thead><tr><th>tick</th>{keys.map(key => <th key={key}>{names[key] ?? key}</th>)}<th>自分の座標</th></tr></thead><tbody>{points.map(point => <tr key={point.tick}><td>{point.tick}</td>{keys.map(key => <td key={key}>{point.custom?.[key] ?? 0}</td>)}<td>{point.playerPosition?.map(value => Number(value.toFixed(2))).join(', ') ?? '—'}</td></tr>)}</tbody></table></div>
  </details>
}

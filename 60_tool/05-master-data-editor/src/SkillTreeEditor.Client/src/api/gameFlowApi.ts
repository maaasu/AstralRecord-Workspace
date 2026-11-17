import { ApiError } from './editorApi'
import type { GameFlowContext } from '../data/gameFlow'

export const gameFlowApi = {
  async context(reload = false): Promise<GameFlowContext> {
    let response: Response
    try { response = await fetch(`/api/master-data/game-flow/context${reload ? '?reload=true' : ''}`) }
    catch { throw new ApiError('エディタのサーバーに接続できません。サーバーの起動と接続を確認して再試行してください。', 0) }
    const result = await response.json().catch(() => ({}))
    if (!response.ok) throw new ApiError(result.message ?? result.title ?? 'ショップの商品を取得できませんでした。', response.status, result)
    return result
  },
}

import { useEffect, useRef, useState } from 'react'
import { aiAssistApi } from '../api/aiAssistApi'
import type { AiSettings } from '../types/aiAssist'

export function MasterAiSettings({ onSettings }: { onSettings: (settings: AiSettings) => void }) {
  const [settings, setSettings] = useState<AiSettings | null>(null)
  const [apiKey, setApiKey] = useState('')
  const [model, setModel] = useState('gpt-6.1-terra')
  const [editing, setEditing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const controller = useRef<AbortController | null>(null)
  const receive = (value: AiSettings) => { setSettings(value); setModel(value.model); onSettings(value) }
  const load = () => {
    controller.current?.abort()
    const request = new AbortController(); controller.current = request
    setBusy(true); setError('')
    void aiAssistApi.settings(request.signal).then(value => {
      if (!request.signal.aborted) receive(value)
    }).catch(reason => { if (!request.signal.aborted) setError(reason instanceof Error ? reason.message : String(reason)) })
      .finally(() => { if (!request.signal.aborted) setBusy(false) })
  }
  useEffect(() => { load(); return () => controller.current?.abort() }, [])
  const save = async (clearApiKey = false) => {
    if (busy || !model.trim()) return
    const request = new AbortController(); controller.current = request
    setBusy(true); setError('')
    const submittedKey = apiKey.trim(); setApiKey('')
    try {
      const value = await aiAssistApi.configure({ model: model.trim(), ...(submittedKey && !clearApiKey ? { apiKey: submittedKey } : {}), ...(clearApiKey ? { clearApiKey: true } : {}) }, request.signal)
      if (!request.signal.aborted) { receive(value); setEditing(false) }
    } catch (reason) { if (!request.signal.aborted) setError(reason instanceof Error ? reason.message : String(reason)) }
    finally { if (!request.signal.aborted) setBusy(false) }
  }
  return <section className="master-ai-settings" aria-label="AI接続設定">
    <div className="button-row"><strong>{settings?.configured ? 'APIキー設定済み' : busy ? '接続設定を確認中…' : 'APIキー未設定'}</strong>{settings && <span className="master-muted">{settings.model}</span>}<button type="button" className="button compact" disabled={busy} onClick={() => { setApiKey(''); setEditing(value => !value) }}>{editing ? '設定を閉じる' : '接続設定'}</button></div>
    {settings?.reason && <p className="master-muted">{settings.reason}</p>}
    {(editing || settings && !settings.configured) && <div className="master-ai-settings-fields">
      <p>ここで入力したAPIキーとモデル設定はローカルのエディタサーバーの起動中のみ保持します。キーは再表示しません。生成時は選択したマスターデータと指示をOpenAIへ送信します。</p>
      {settings && <p className="master-muted">キーの参照元: {({ session: 'この起動中の設定', environment: '環境変数', configuration: 'サーバー設定', none: '未設定' } as Record<string, string>)[settings.keySource] ?? settings.keySource}。起動中のキーを解除すると、環境変数またはサーバー設定のキーへ戻ります。</p>}
      <label>OpenAI APIキー<input type="password" autoComplete="off" aria-label="OpenAI APIキー" value={apiKey} disabled={busy} placeholder={settings?.configured ? '変更する場合のみ入力' : 'APIキーを入力'} onChange={event => setApiKey(event.target.value)} /></label>
      <label>モデル<input aria-label="AIモデル" value={model} disabled={busy} onChange={event => setModel(event.target.value)} /></label>
      <div className="button-row"><button type="button" className="button" disabled={busy || !model.trim()} onClick={() => void save()}>接続設定を保存</button><button type="button" className="button subtle" disabled={busy || settings?.keySource !== 'session'} onClick={() => void save(true)}>この起動中のキーを解除</button></div>
    </div>}
    {error && <p role="alert" className="error-message">{error}<button type="button" className="button compact" disabled={busy} onClick={load}>設定を再読込</button></p>}
  </section>
}

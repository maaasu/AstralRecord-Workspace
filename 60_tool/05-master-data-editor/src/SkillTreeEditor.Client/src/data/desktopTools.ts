import type { ToolId } from '../api/toolJobApi'

export const toolSpecs = {
  '03': {
    label: 'マスタ反映',
    description: '保存済みの Filebase を同期し、マスタを再読込してパッチを公開します。',
    action: '実行',
    steps: ['対象環境と停止・稼働条件を確認', 'Filebase の同期とマスタ投入', '再読込または Dev 起動・新世代確認', 'パッチ公開'],
  },
  '01': {
    label: 'Dev更新',
    description: '配布設定に従って API・Web・Plugin・Filebase を更新します。',
    action: '実行',
    steps: ['配布設定と Dev の状態を確認', 'ビルドと検証', '設定された成果物の配布・データ更新', 'Dev 起動確認とパッチ公開'],
  },
  '09': {
    label: 'AstralArchitect',
    description: 'AI 建築用プラグインをテスト・ビルドし、設定済みのサーバーへ配置できます。',
    action: '実行',
    steps: ['JAR の出力先と配置先を確認', 'Maven でテストとビルドを実行', '配置を選んだ場合は JAR を更新'],
  },
  '12': {
    label: 'ネットワークビルド',
    description: 'Lobby・Velocity Proxy・Geyser Extension の JAR をビルドします。',
    action: '実行',
    steps: ['対象とビルド環境を確認', 'Maven で対象 Plugin をビルド', 'JAR を出力先へコピー'],
  },
  '16': {
    label: 'メンテナンス配布',
    description: 'Dev / Build の成果物を配布設定で指定されたサーバーへ配布します。',
    action: '配布を開始',
    steps: ['配布経路と停止条件を確認', 'JAR・配置データなどを配布', '対象サーバーの起動・新世代を確認', 'パッチ公開'],
  },
} satisfies Record<ToolId, { label: string; description: string; action: string; steps: string[] }>

export const toolIds: readonly ToolId[] = ['03', '01', '09', '12', '16']

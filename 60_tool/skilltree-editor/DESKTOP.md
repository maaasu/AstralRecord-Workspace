# AstralRecord マスタデータエディタ

## 起動

1. ZIP 全体を任意のフォルダーへ展開します。
2. `AstralRecordMasterEditor.exe` を起動します。
3. 初回は `40_filebase`・`10_plugin`・`60_tool` のある AstralRecord ワークスペースを選びます。

次回からは前回のワークスペースを開きます。画面のワークスペース選択から変更できます。
EXE と同じフォルダーの DLL・`wwwroot`・`tool-host` も必要です。EXE だけを移動しないでください。
ZIP にマスターデータや接続先設定は含まれていません。選んだワークスペース内のファイルを編集します。

Windows x64 向けで、.NET は同梱しています。`webview2` フォルダーのある配布物は画面用ランタイムも同梱し、アプリ起動のための追加インストールは不要です。Node.js や .NET SDK もマスタ編集には不要です。ローカルディスクへ展開してください。
`webview2` を省いた軽量ビルドは、PC に Microsoft Edge WebView2 Runtime が必要です。未導入の場合は [Microsoft の配布ページ](https://developer.microsoft.com/microsoft-edge/webview2/)を参照してください。
同梱ランタイムは ZIP の更新時に更新します。Windows 10 では起動時に同梱ランタイムのフォルダーへ、Microsoft が要求するアプリパッケージ向け読み取り権限を設定します。

## マスタ編集

固定のグレー配色です。表示名・レアリティなど意味のある色とアイコンは保持し、カラーコードは同じ入力欄で確認できます。
マスターデータ、スキルツリー、装備・入手経路、既存の分析・配置機能を利用できます。
保存前の検証とバックアップは従来と同じです。未保存の変更がある場合、アプリ終了・ワークスペース変更時に確認します。

## 計画・実行・結果

左側からツールを選び、条件を指定して計画を作成し、内容を確認して実行します。

| ツール | 処理 |
| --- | --- |
| 03 マスタ反映 | 既存の MasterDataOnly フローでマスタを反映 |
| 01 開発環境更新 | ビルド・テスト・配備・既存の更新フロー |
| 12 ネットワークプラグイン | Lobby・Proxy・Extension のビルドと JAR の収集 |
| 16 メンテナンス | ワールド・ネットワークプラグインの選択と既存のメンテナンスフロー |

計画の作成だけではサーバー停止、コピー、DB 更新を実行しません。
12 はビルドと出力先の確認で、サーバーへの配布は 16 で扱います。
実行ログと結果はアプリ内の実行履歴に保存されます。アプリを閉じても開始済みの処理は継続し、再起動して履歴から確認できます。
サーバーの手動停止・起動など、既存のツールが要求する運用手順は実行画面の計画とログに従ってください。
失敗時は結果と既存の復旧状態を確認し、復旧方式を指定して新しい計画を作成します。

実行には、従来の BAT と同じ PowerShell 7、Java/Maven、.NET SDK、接続先設定、サーバー権限などが必要です。
ワークスペースの `60_tool/CONFIGURATION.md` と各ツールの README を参照してください。
ワークスペースの `*.local.json` を引き継ぎます。計画では設定元と反映元・反映先を確認してください。

## 設定とデータ

- アプリ設定・WebView2 キャッシュ・起動エラー: `%LOCALAPPDATA%/AstralRecord/MasterEditor/`
- 保存前バックアップ: ワークスペースの `60_tool/skilltree-editor/.backups/`
- ツール実行履歴: ワークスペースの `60_tool/skilltree-editor/.cache/tool-jobs/`
- 引数: `AstralRecordMasterEditor.exe --workspace "C:\AstralRecord-Workspace"`
- ワークスペースを選び直して起動: `AstralRecordMasterEditor.exe --select-workspace`

アプリ削除は展開フォルダーを削除します。ワークスペースのマスターデータ・バックアップ・実行履歴は別に保持されます。

## 同梱ソフトウェア

WebView2 のライセンス・通知は `licenses/` と同梱ランタイム内の通知を参照してください。[Microsoft の WebView2 配布条件](https://developer.microsoft.com/en-us/microsoft-edge/webview2/#download)が適用されます。
WebView2 は SmartScreen 等の機能に伴い Microsoft へ診断・セキュリティ情報を送信することがあります。詳細は [WebView2 のデータとプライバシー](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/data-privacy)を参照してください。

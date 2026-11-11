# ビルダースキーマティック API

AstralRecord Web で受け付けた `.schem` を Dev Minecraft サーバーで検証し、承認済みのファイルを本人の `/builder load` に提供する。

- 実装は `20_api/AstralRecordApi/AstralRecordApi/` の `Controllers/BuilderSchematicsController.cs`、`Models/BuilderSchematicModels.cs`、`Repositories/BuilderSchematicRepository.cs`。
- 対応 Plugin 機能は [[38_0-概要|ビルダーGUI]]。
- API 内の依存は共通 API キー認証。保存は専用 SQLite ファイルで行う。

## ドキュメント

1. [[44_1.00-モデル定義]]
2. [[44_3.00-エンドポイント仕様]]

## 配置と保存

- `BuilderSchematics__RootPath` に、API のサービスアカウントが書き込める永続ローカルディスク上の絶対パスを指定する。Web ルートとその配下は指定できない。
- `BuilderSchematics__DevKey` に専用キーを設定する。Dev Minecraft サーバーだけに同じキーを配布する。Web サーバーは既存の共通 API キーを使う。
- どちらかが未設定の場合、機能は無効。配置専用の `WorldPlacements:Standalone` ホストではこの API を提供しない。
- 初回アクセス時に `builder-schematics.db` を作成する。アップロードの内容と受付状態、チケットのハッシュを同じ DB に保存する。API 再起動とアプリ更新をまたいでディレクトリを維持する。
- 最大 1 MiB のファイルを受け付ける。保留はユーザーあたり 4 件、全体で 128 件。保留と承認済みを合わせてユーザーあたり 32 件、全体で 1,024 件。拒否履歴を含む受付件数はユーザーあたり 64 件、全体で 2,048 件。
- 拒否時にファイル本体を削除する。結果照会は拒否から 24 時間まで可能で、期限切れの履歴は後続の受付処理で削除する。

DTO と HTTP 契約、設定値、保存上限を変更した場合は、対応する実装とこの設計書を合わせて更新する。

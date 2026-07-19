# 17 World システム

WorldMasterData を Plugin 側で参照し、管理者が `/world` から定義確認とロード済みワールドへの移動を行うための土台です。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/world/*`

## ドキュメント一覧（推奨順）

1. [[17_0.00-概要]]
2. [[17_1.00-モデル定義]]
3. [[17_3.00-サービス]]
4. [[17_3.01-コマンド]]

## 依存 feature

- `player`: `AstPlayer`、参加時spawn、帰還処理
- `menu`: オーバーワールド転送GUI、メニュー帰還導線
- `player-interaction`: `SNEAK`の競合調停。world側はbase / overworld spawn action候補を提供する
- API / filebase: `WorldMasterData`の取得と定義

## 更新ルール（変更時に必ず更新する章）

- `WorldMasterData`、world type、spawn解決の変更:
  - [[17_0.00-概要]]
  - [[17_1.00-モデル定義]]
  - [[17_3.00-サービス]]
- `/world` commandの変更:
  - [[17_3.01-コマンド]]
- base / overworld spawn地点のSNEAK候補、距離、priority、claim方針の変更:
  - [[17_0.00-概要]]
  - [[17_3.00-サービス]]
  - [[28_3.01-イベント]]
  - [[28_4.00-統合フロー]]

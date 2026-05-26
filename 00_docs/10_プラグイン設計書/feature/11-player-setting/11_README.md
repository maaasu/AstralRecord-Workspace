# 11_README

このディレクトリは `feature/player-setting` の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/playersetting/event/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/playersetting/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/playersetting/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/playersetting/repository/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/playersetting/command/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/playersetting/gui/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/playersetting/cache/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/playersetting/*`

## ドキュメント一覧（推奨順）

1. [[11_0.00-概要]]
2. [[11_1.00-モデル定義]]
3. [[11_2.00-ユースケース]]
4. [[11_3.00-索引]]
5. [[11_4.00-統合フロー]]
6. [[11_5.00-例外・ログ・運用]]
7. [[11_9.00-未決事項]]（必要時）

## 依存 feature

- `user`
  - [[01_3.02-サービス]].ユーザ取得 で取得済みの [[01_1.00-モデル定義]].ユーザモデル を、[[11_1.00-モデル定義]].プレイヤー設定スナップショット の所有者キーとして使用する。
- `player`
  - [[03_3.02-サービス]].プレイヤー参加反映 完了後に [[11_3.02-サービス]].プレイヤー設定読込 を呼び出してキャッシュを構築する。
  - [[03_3.02-サービス]].プレイヤー退出処理 で [[11_3.04-キャッシュ]].プレイヤー設定キャッシュ削除 を呼び出す。
- `menu`
  - [[11_3.07-GUI・View]].プレイヤー設定 GUI を menu feature の開閉導線から呼び出す。

## 関連 feature（参照される側）

- `hud`（ダメージログ・ドロップログ表示）
  - 表示判定で [[11_3.02-サービス]].プレイヤー設定参照 を呼び出す。
- `shared/effect`（ParticleDisplayService）
  - 表示密度の決定で [[11_3.02-サービス]].プレイヤー設定参照 を呼び出す。

## 関連外部設計書

- API: `00_docs/20_API設計書/feature/03-player-setting/`
- DB: `00_docs/40_Database設計書/table-definitions/AstralRecord/dbo.user_setting.md`

## 更新ルール（変更時に必ず更新する章）

- 設定キー追加・削除・型変更:
  - [[11_1.00-モデル定義]]
  - [[11_3.02-サービス]]
  - [[11_3.03-コマンド]]
  - [[11_3.07-GUI・View]]
- 設定読込・保存契機変更:
  - [[11_3.02-サービス]]
  - [[11_4.00-統合フロー]]
- GUI 構成変更:
  - [[11_3.07-GUI・View]]
- ログ ID・メッセージ ID 追加:
  - [[11_5.00-例外・ログ・運用]]
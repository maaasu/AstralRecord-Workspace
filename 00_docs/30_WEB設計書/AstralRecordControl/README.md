# AstralRecordControl 設計書

- 設計状態: 採用済み。P0の台帳・認証・通信基盤とP1のRunner・Java基本運転・共通gateを実装。P2のファイル操作・転送・停止バックアップ・journal復元を実装。P3以降は未実装。
- 初版: 2026-10-18。v1の対象OSはWindows、実装基盤は.NET 10。
- 目的: Craftyを使わず、単一の管理Webから複数PC上のMinecraftを運用する。
- 配置: Devは開発PC、チャンネルはMC用PC。管理Webはそれらと独立したWeb管理用PCに置く。
- 利用者: 管理者1アカウント。利用者別権限・ロール・貸出・課金・マルチテナントは作らない。

## 読む順序と正本

| 文書 | 正本にする内容 |
|---|---|
| [01-構成と責務.md](01-構成と責務.md) | プロジェクト配置、サービス境界、起動依存、保存場所 |
| [02-認証と通信仕様.md](02-認証と通信仕様.md) | 認証、HTTP/SignalR/IPC、DTO、エラー、再接続、転送 |
| [03-データモデル.md](03-データモデル.md) | ID、永続データ、状態、操作種別とpayload |
| [04-操作と運用.md](04-操作と運用.md) | 起動停止、共通排他、ファイル、復旧、配布、予約 |
| [05-画面と機能.md](05-画面と機能.md) | 画面、機能範囲、Craftyとの比較境界 |
| [06-実装計画と受入条件.md](06-実装計画と受入条件.md) | 段階、着手条件、成果物、受入検証、導入移行 |

通信のフィールドを追加するときは02と03、状態遷移を変えるときは03と04、機能追加時は05と06を同じ変更で整合させる。文書名の番号は実装順を意味しない。

## 決定済みの基本契約

1. Craftyの実行・API・DBには依存しない。比較対象はMinecraft運用機能であり、他ゲーム・ホスティング事業者向け機能は対象外。
2. Web、Agent、Runnerは別プロセス。各MC実行PCにAgentとRunnerを1サービスずつ導入し、Runnerが複数Minecraftプロセスを所有する。
3. Agentから管理Webへ外向きのHTTPS/WSS接続を張る。管理のために各PCの待受ポートやSMB共有を外部公開しない。
4. 管理者のパスワード＋TOTP認証と、PCごとのAgentトークン認証を分離する。Minecraftコード・公開WebのCookie・ゲームDBに認証を依存させない。
5. Web/Agentの再起動ではJavaとコンソールを維持する。Runner自身の障害後に匿名標準入出力を復元できるとは扱わない。
6. 指示の受付と完了を分け、操作IDを末端まで保持する。通信断による結果不明を成功・失敗へ推測せず、コンソール命令を無条件に再送しない。
7. Web・予約・ローカルCLI・既存配布BATを共通の操作予約へ参加させる。未完了保守は切断・時間切れだけで解除しない。
8. 標準バックアップは正常停止後の取得。Minecraftファイルの復元と、SQL/API/公開世代を含むAstralRecord全体の復旧を区別する。
9. v1の管理Webは単一インスタンス・ローカルSQLite。クラスタ、HA、分散DB、独立IdPは導入しない。

## 用語

| 用語 | 意味 |
|---|---|
| Host | Agent/Runnerを置くPC。例: `dev-pc`、`mc-pc` |
| Server | 1つのMinecraft/Proxy実行定義。例: `dev`、`channel1` |
| Session | Runnerが1回起動したゲームプロセスの識別子。Webログインとは別 |
| Operation | 1つのPCへ送る単位操作。受付、実行、結果を永続化する |
| Workflow | 複数のOperationや既存配布処理を束ねる保守作業 |
| Reservation | 操作の競合を防ぐ永続予約。Runnerの保守holdも含む |
| Environment | API/マスター/公開状態を共有する範囲。PCやDev/本番の表示名から推測しない |

## 既存契約との接続

- [60_toolの入口一覧](../../../60_tool/README.md): 01/02/03/16/17の役割を維持する。
- [Dev更新](../../../60_tool/deploy-debug/README.md)、[メンテナンス配布](../../../60_tool/maintenance/README.md)、[プレイヤー一括更新](../../../60_tool/player-patch-migration/README.md): 既存run/journal・ready・公開の正本。
- [スキルツリーの運用契約](../../20_API設計書/feature/20-skilltree/20_5.00-安全編集の導入と検証.md): boot・世代・公開・一括更新を同じ意味で使う。
- [公開Webの認証](../feature/01-web-auth/01_README.md): 既存認証の説明資料であり、Controlのログイン処理には再利用しない。

上記はControl導入前の既存契約である。共通gate導入など今後必要な改修は06に明記し、すでに連携済みと記載しない。

## 環境値と変更境界

固定DNS名、TLS証明書、PCの実パス、Javaのインストール先、既存runtimeServerIdとの対応、バックアップ実保存先は導入時の設定値。実サーバーへ接続できたことや既存設定の正確な版は前提にしない。これらは実装上の未決仕様ではなく、登録時に検証する必須入力である。

実装入口は [Control README](../../../30_web/AstralRecordControl/README.md)、導入入口は [control-deploy](../../../60_tool/control-deploy/README.md)、既存ツールとの対応付けは [control-gate](../../../60_tool/control-gate/README.md)。P1の変更操作は既定無効で、全Hostの入口改修・導入marker・能力確認を経て有効化する。本設計の完成形のAPI/機能をすべて実装済みとは扱わない。P2のファイル操作・バックアップは追加のEnableFileControlと能力確認で有効化する。P3以降の予約実行等は未提供。

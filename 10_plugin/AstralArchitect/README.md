# AstralArchitect

AstralArchitectは、FastAsyncWorldEditで選択した局所的な範囲をチケットとして保存し、Codexが作成した候補を差分適用するPaperプラグインです。建築デザインはCodexが担当し、プラグインは範囲取得、検証、配置、ロールバック、チケット管理だけを担当します。

## 対象環境

- Minecraft Java Edition 1.21.11
- Java 21
- Paper/Purpur 1.21.11
- FastAsyncWorldEdit-Paper 2.15.2（必須）
- Python 3.10以上（Codex用CLI）
- Codex workspace skill `$astralarchitect-builder`

## 安全境界

- プレイヤーはクリエイティブモードかつ`astralarchitect.use`権限を持つ場合だけ操作できます。
- 通常プレイヤーは自分のチケットだけを操作できます。
- `astralarchitect.admin`権限を持つクリエイティブモードのプレイヤーは全チケットを操作できます。
- コンソールは`help`、`list`、`info`、`delete`、`restore`、`reload`だけを使用できます。
- `source.schem`は作成時の不変な正本です。Codexは変更してはいけません。
- AI候補は`candidate.schem`だけへ書き込みます。
- 適用は`source.schem`と検証済み`candidate.schem`、ロールバックは`source.schem`と固定済み`applied.schem`の差分だけを変更します。
- Block Entity、Entity、Biomeの変更は初期版では拒否します。
- 選択範囲外は変更しません。

## 基本フロー

1. WorldEdit/FAWEでPos1とPos2を指定します。
2. 建築の基準にしたいブロックへ照準を合わせます。
3. `/architect ticket create <名前>`を実行します。
4. 表示されたチケットIDを使い、Codexへ`$astralarchitect-builder`で建築を依頼します。
5. Codexの完了後、`/architect ticket validate <ID>`を実行します。
6. 検証成功後、`/architect ticket apply <ID>`を実行します。
7. 問題があれば`/architect ticket rollback <ID>`で元に戻します。
8. 問題がなければ`/architect ticket delete <ID>`でチケットをtrashへ移動します。

例:

```text
//wand
//pos1
//pos2
/architect ticket create river-bridge
```

Codexへの依頼例:

```text
$astralarchitect-builder を使って、<サーバーのplugins/AstralArchitect/tickets/チケットID> に、
基準ブロックを西岸側の入口として苔むした石造りのアーチ橋を作成してください。
```

## コマンド

| コマンド | 説明 |
|:--|:--|
| `/architect help` | 使い方と基本フローを表示します。 |
| `/architect ticket create <名前>` | 現在のWorldEdit選択と照準先ブロックからチケットを作成します。 |
| `/architect ticket list` | 操作可能なチケットを一覧表示します。 |
| `/architect ticket info <ID>` | チケットの範囲、基準座標、状態を表示します。 |
| `/architect ticket validate <ID>` | AI候補の形式、範囲、危険な変更を検査します。 |
| `/architect ticket apply <ID>` | 検証済み候補の差分をワールドへ適用します。 |
| `/architect ticket rollback <ID>` | 適用した差分を元データへ戻します。 |
| `/architect ticket delete <ID>` | チケットをtrashへ移動します。 |
| `/architect ticket restore <ID>` | trashにあるチケットを復元します。 |
| `/architect reload` | 設定を再読込します。管理者専用です。 |

## チケットの保存場所

実行中のデータはJAR内の`src/main/resources`ではなく、サーバーのデータフォルダへ保存します。

```text
plugins/AstralArchitect/
├─ config.yml
├─ .locks/
│  ├─ .worker.lock
│  └─ <ticket-id>.lock
├─ tickets/
│  └─ <ticket-id>/
│     ├─ ticket.json
│     ├─ source.schem
│     ├─ candidate.schem
│     ├─ applied.schem（適用後のみ）
│     └─ attachments/
└─ trash/
```

- `ticket.json`: 所有者、ワールド、選択範囲、基準座標、状態、SHA-256を保存します。
- `.locks/`: プラグイン再読込をまたぐworker処理と、CLI/プラグイン間の候補操作を排他制御します。AIは変更しません。
- `source.schem`: チケット作成時のSponge Schematic v3です。
- `candidate.schem`: Codexが専用ツール経由で編集する候補です。
- `applied.schem`: 実際に適用した候補を固定したロールバック用データです。
- `attachments/`: プレイヤーが必要な場合だけ参考画像を置きます。プラグインは画像を生成しません。
- `trash/`: 削除されたチケットを既定で7日間保持します。

## AIがファイルを読む仕組み

`.schem`は人間向けテキストではなく、gzip圧縮されたSponge Schematic v3のバイナリです。Codexはこのバイナリを直接推測して書き換えません。`$astralarchitect-builder`の安全なラッパーから、ワークスペースで管理される`10_plugin/AstralArchitect/tools/ticket_cli.py`を呼び出します。CLIは指定されたサーバー上のチケットを1ブロック単位のJSONへ変換し、編集時は`candidate.schem`だけを一時ファイル経由で原子的に置換します。

サーバーの`plugins/AstralArchitect`配下へ実行可能なCLIは配置しません。チケットに隣接するプログラムは信頼せず、必ずworkspace-local skillのラッパーを使います。

主な読取例:

```text
cd E:\AstralRecord-Workspace\.codex\skills\astralarchitect-builder
python scripts/invoke_ticket_cli.py --ticket <チケットの絶対パス> -- info
python scripts/invoke_ticket_cli.py --ticket <チケットの絶対パス> -- palette
python scripts/invoke_ticket_cli.py --ticket <チケットの絶対パス> -- get-block <X> <Y> <Z>
python scripts/invoke_ticket_cli.py --ticket <チケットの絶対パス> -- slice --y <Y> --x-min <X> --x-max <X> --z-min <Z> --z-max <Z>
python scripts/invoke_ticket_cli.py --ticket <チケットの絶対パス> -- surface --x-min <X> --x-max <X> --z-min <Z> --z-max <Z>
python scripts/invoke_ticket_cli.py --ticket <チケットの絶対パス> -- diff
```

編集は`set`、`fill`、`line`、`replace`操作をJSON/NDJSONで指定します。詳細は`tools/README.md`と`$astralarchitect-builder`を参照してください。CLIにはワールド適用、ロールバック、チケット削除機能を持たせていません。

`slice`と`surface`はCodexへ巨大なJSONを返さないよう、1回につきX/Z平面で16,384セルまでです。広い範囲は座標窓を分けて順番に読みます。編集操作も全操作を合計した展開数に上限があり、異常に大きい操作ファイルは書込み前に拒否します。

CLI初期版にはメモリ枯渇防止の絶対安全上限として1チケット2,000万ブロックがあります。これは縦横高さの固定ではなく総量上限です。既定の262,144ブロックを超える運用は`config.yml`の変更と十分なメモリ検証を行ったうえで段階的に増やしてください。

## 状態遷移

```text
CREATED -> READY -> APPLYING -> APPLIED -> ROLLING_BACK -> ROLLED_BACK
             ^                                              |
             +----------------------------------------------+

任意の状態 -> TRASHED -> 復元前の状態
```

Codexが候補を編集しただけでは`READY`になりません。必ずMinecraft内で`validate`を実行してください。

サーバーが適用途中で停止した場合は`APPLYING`、ロールバック途中なら`ROLLING_BACK`が残ります。同じ`apply`または`rollback`コマンドを再実行すると、元状態と完成状態のブロックを判定して処理を安全に完了します。中間状態のチケットは、復旧が終わるまで削除できません。

`delete`または`restore`のディレクトリ移動直前に停止した場合も、同じコマンドを再実行すると移動前の状態を保持したまま完了します。

## ビルドと配置

ワークスペース共通の実行入口からビルド・配置します。どのカレントディレクトリからでも実行できます。

```text
E:\AstralRecord-Workspace\60_tool\09-astralarchitect-build-deploy.bat
```

既定ではMavenビルド後、生成JARを現在の開発サーバーの`plugins/AstralArchitect.jar`へ配置します。ビルドだけを行う場合:

```text
E:\AstralRecord-Workspace\60_tool\09-astralarchitect-build-deploy.bat -BuildOnly
```

別のサーバーへ配置する場合:

```text
E:\AstralRecord-Workspace\60_tool\09-astralarchitect-build-deploy.bat -PluginsDirectory "D:\minecraft\plugins"
```

既定の配置先は`60_tool/astralarchitect-deploy/astralarchitect-deploy.config.json`で管理します。配置するのは`AstralArchitect.jar`だけで、既存の`plugins/AstralArchitect`データフォルダ、チケット、trash、画像は変更しません。配置スクリプトはサーバーを停止・再起動しないため、更新したJARは次回のサーバー再起動時に読み込まれます。

## 開発上の注意

- `source.schem`のSHA-256不一致は改ざんとして拒否します。
- 適用前に、変更対象の現在ブロックが`source.schem`と一致することを確認します。
- ロールバック前に、変更対象の現在ブロックが適用済み候補と一致することを確認します。
- サイズ上限は`config.yml`の総ブロック数で変更できます。縦横高さは固定しません。
- 200×200×200などの範囲を扱う分割処理は初期版の対象外です。

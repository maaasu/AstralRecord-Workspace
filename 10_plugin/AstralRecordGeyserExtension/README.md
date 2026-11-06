# AstralRecord Geyser Extension

Velocity上のGeyserへ、AstralRecordのカスタムプレイヤーヘッド、カスタムアイテム、Bedrock用のダメージ表示・アクションリングを登録するJava 21 / Mavenモジュール。Geyser API 2.11.2を使用する。

## 登録元

- `BuiltinHeadTextures` のJava定数: RPGの `GuiItems` と同じメインメニュー、カレンシー、上下左右矢印・無効ボタンの7テクスチャ。
- 認証付き `GET /api/geyser/heads` の `textures`: item / mail / mob / skill / classマスターの `icon: PLAYER_HEAD` と `iconTexture`（Base64のtexturesプロパティ）。
- 同レスポンスの `playerUuids`: 削除されていない登録ユーザーのMinecraft UUID。オンライン一覧やアカウントIDではない。

固定・マスターのテクスチャは `PROFILE`、ユーザーUUIDから解決したテクスチャは `SKIN_HASH` として `GeyserDefineCustomSkullsEvent` 内で登録する。Geyserが生成するBedrockパックをクライアントが適用すると、RPGから送られた対応するヘッドの見た目が変換される。クリックやスクロールの処理はRPGが引き続き担当する。

マスターの指定例（値は使用するスキンのBase64に置換する）:

```yaml
icon: PLAYER_HEAD
iconTexture: "<texturesプロパティのBase64値>"
```

`iconTexture` は省略可能で、通常素材には適用しない。Mobの `skin.texture` はNPC本体の外観用で、アイコンには流用しない。

## ビルド・導入

1. ワークスペースの `60_tool/12-build-network-plugins.bat` を実行する。Extension単体は `-Target Extension`。
2. `60_tool/network-plugin-build/output/AstralRecordGeyserExtension.jar` をVelocityの `plugins/Geyser-Velocity/extensions/` へ配置する。
3. Geyserの `gameplay.enable-custom-content: true` と `gameplay.force-resource-packs: true` を設定する。
4. 初回起動でExtensionデータフォルダに作成される `config.yml` の `api.baseUrl` と認証を設定する。フォルダは通常 `plugins/Geyser-Velocity/extensions/astralrecordgeyser/`。事前に配置してもよい。
5. `api.apiKey` を設定するか、既定の `ASTRALRECORD_API_KEY` 環境変数をVelocityプロセスへ渡す。キーはGitへコミットしない。通常はTLS証明書をJVMから信頼できるものにする。APIが信頼できるローカル・閉域環境からのみ到達可能な開発環境では、`api.allowInsecureTls: true` により証明書とホスト名の検証を無効化できる。本番では必ず `false` にする。
6. 更新済みAPIを起動し、マスターをseedした後にProxyを再起動する。Bedrockクライアントで生成パックを適用して再接続する。

RPG本体や `AstralRecordProxy.jar` をextensionsフォルダに入れない。このJARはGeyserが読み込む。

## Bedrockパックの配布とダメージ表示

拡張 `1.2.0` 以降はダメージ表示とアクションリングに対応します。差し替え後は Proxy を起動し、`Bedrock damage resource pack ready:` がログに出てから再接続します。

起動時と以降約1分ごとに認証付き `GET /api/resource-packs/manifest` を取得する。SHA-256が変わったときだけ、同APIの `GET /api/resource-packs/bedrock/{sha256}.mcpack` から更新パックを取得する。SHA-256とアーカイブのmanifest header UUIDを検証し、新規接続へ配布する。同一のProxy起動中に取得や検証に失敗した場合は最後に検証済みのパックを維持する。Proxy起動時に取得できなければ従来の文字表示を使い、取得が復旧した後の新規接続から専用モデルを使う。パックの更新は次回のBedrock接続時に適用される。

拡張 `1.4.0` 以降は、必要な描画ファイルが揃ったパック `1.1.0` 以降を登録してログインまで進んだ接続で、RPG が送るダメージ数値を `astralrecord:damage_number` に置き換えます。対応パックを使えない接続では、元の TextDisplay を Geyser が文字表示に変換します。Geyser の公開 API で確認できるのはパックの登録とログイン完了までです。ダウンロードには Geyser の強制パック設定を使います。

## Bedrock のアイテムツールチップ

拡張は `item-tooltips-version.txt` が互換版 `1` を示し、`font/glyph_E6.png` と `textures/ui/purpleBorder.png` を含むパックを配布対象にした接続を確認します。パック交渉を経てログインした後、Velocity の AstralRecordProxy が RPG に通知します。RPG は通知を受けた接続向けにアイテムのツールチップへ専用字形を追加します。更新後はパックを受け取るために再接続してください。配布には Geyser の `gameplay.force-resource-packs: true` を使います。

## Bedrockのアクションリング

接続時に一体型アセットを含むパックを登録した接続では、輪郭用 TextDisplay を `astralrecord:action_ring_panel` に置き換えます。一枚の両面モデルを輪郭に使い、後続の状態通知でスプライト、選択マーカー、ゲージ、向きと縮尺を更新します。一体型表示に含まれるアイコンとゲージの個別表示を抑止し、パックにないアイコンは元のアイテムで表示します。操作案内と視線を合わせた枠の短い状態表示には Geyser の TextDisplay 変換を使います。発動判定は RPG 側で行います。

一体型アセットを利用できない接続では、RPG が送る8方向の色付きの目印、個別アイコン、操作時間バーを表示します。パック配布には `gameplay.enable-custom-content: true` と `gameplay.force-resource-packs: true` を使います。更新後のパックは再接続時に配布します。

## Bedrockの討伐リザルト

拡張 1.6.0 以降は、必要なアセットを登録した接続で、RPG が印を付けた個人リザルトの TextDisplay に `astralrecord:combat_result` モデルを割り当てます。Geyser の本文変換で報酬を表示し、クライアントが紋章のアニメーションを再生します。対応アセットが揃わない接続では元の TextDisplay 変換を使います。

## Bedrockの武器通常攻撃

拡張 `1.7.0` 以降は、通常攻撃の描画ファイルを含むパックを登録してログインまで進んだ接続で、RPG が送る演出用 TextDisplay を `astralrecord:weapon_attack` に置き換えます。武器に応じて剣・ハンマー・槍・弓・ワンド・杖の外観を切り替えます。対応パックを使えない接続では演出用 TextDisplay のみを取り消し、従来の腕振り・攻撃音・弾体を使います。

拡張 `1.14.0` 以降は、`weapon-attack-projectiles-version.txt` が互換版 `1` を示し、弓・ワンド・スタッフの軌跡・着弾テクスチャ6点が揃ったパックを接続単位で確認します。VelocityのAstralRecordProxyが対応状況をRPGへ通知します。通知が届くまでRPGは従来の飛翔粒子と魔法命中粒子を送ります。拡張を入れ替えた後はProxyを再起動し、統合版クライアントを再接続してください。

## Bedrockの陽冠のサンバード

拡張 `1.11.0` 以降は、サンバード本体と技の描画ファイルを揃えたパックを登録してログインまで進んだ接続で、RPG が印を付けたオウムを `astralrecord:sunbird` として表示します。名前や当たり判定には元のエンティティの情報を使います。技は RPG が送る演出用 TextDisplay を `astralrecord:sunbird_skill` に変換し、種類・幅・奥行き・高さに応じて表示します。パックが揃わない接続には元のオウムと RPG のパーティクルを表示します。

## Bedrockのウェイストーンと施設NPC

拡張 1.12.0 以降は、ワールド表示用のエンティティ定義、形状、レンダーコントローラー、テクスチャが揃ったパックを登録した接続で、RPG が送る印付きの空 TextDisplay を `astralrecord:world_display` に変換します。ウェイストーンの解除状態と施設NPCの素材は、生成時の `astralrecord:style` でモデルへ渡します。

対応パックを登録できない接続では、ウェイストーンに名称と状態色の粒子を表示し、施設NPCは従来の表示を Geyser に渡します。従来の施設モデルの描画は GeyserDisplayEntity の設定に従います。対応パックの接続では施設NPCの従来表示を抑止し、専用モデルを使います。NPC の操作判定は RPG の `Interaction` が担当します。

## Bedrockのアイテムモデル

拡張 `1.3.0` 以降は、起動時に検証済みBedrockパックのルートにある `astralrecord_item_models.json` を読み、`GeyserDefineCustomItemsEvent` でモデルを登録する。カタログを含むパックでは、同名のアーカイブ項目がないこと、各モデルのアイコンとアトラスの対応、アタッチャブル・ジオメトリ・アニメーション・テクスチャのファイルが揃うことを検証する。Java側の基本素材と `minecraft:custom_model_data` の先頭floatが一致したアイテムを、Bedrock側の `astralrecord:<id>` に変換する。モデル定義を含むパックをAPIで公開してからProxyを起動し、Bedrockクライアントを再接続する。

プレイヤー設定の「アクションリング長押し選択」は既定で無効。有効で発動候補が2つ以上の場合は、選択中の主武器をBedrockのトライデントとして送り、長押し入力を受け付ける。拡張 `1.5.0` とパック `1.2.0` 以降では、接続時のカタログに `held_weapon: true` があり、トライデントのアタッチャブルから対応するレンダーコントローラーを参照できるモデルを本人の持ち手に表示する。フラグや対応アセットのないパックでは通常のトライデント表示を使う。

同じVelocity内で接続先を切り替えるときは、AstralRecordProxy `1.1.0` 以降が前の武器モデルの表示を解除する。Paperから次の表示通知を受け取ると、同じモデル番号でも再適用する。表示の解除通知を受け取った場合や更新が約8秒間途切れた場合も、通常のトライデント表示に戻す。

起動時にパックを取得できなかった場合や、新しいマッピングを公開した場合はProxyを再起動する。稼働中のProxyは、登録済みマッピングをすべて含むパック更新を配布できる。登録済みマッピングを変更または削除したパックが公開された場合は、互換性のあるパックが公開されるかProxyを再起動するまで、現在のパックを維持する。

カタログ項目の `render_mode` が `"generated"` の場合は、アトラスと `textures/items/<id>.png` のみを必須にします。省略または `"3d"` は従来どおり立体モデルの全ファイルを検証します。アイコンのみの項目も同じGeyserカスタムアイテムAPIに登録し、`astralrecord.<id>` のアトラスキーを使います。稼働中に同じIDの描画方式を変更したパックは互換性がないため、Proxy再起動まで現在のパックを保持します。

配布カタログの任意フィールド `armor: true` は、パックの `armor-assets.json` に明示登録したネイティブの頭・胴・脚・足防具だけに付く。Geyserは通常定義に加え `astralrecord:hidden_armor_<id>` を登録し、送信コピーの `custom_model_data.flags[0]`（trueが非表示）を `ItemPredicateContext.customModelDataFlag(0)` で読み、排他的に選ぶ。削除componentの列挙には依存しない。非表示定義はそのcomponentを除去し、同じ `astralrecord.<id>` のアイコンと手持ち専用attachableを使う。これによりRPGの閲覧者別防具表示設定が、装備実体やGUI表示を変えずにBEの固定wearable定義へ反映される。元の3D武器・2Dアイテム・馬鎧素材は未指定のままで従来の登録を維持する。派生識別子の衝突と必要アセット不足はパック読込時に拒否する。`armor: true` と `render_mode: generated` または `held_weapon: true` の併記は拒否し、2D装飾や仮想手持ち武器へ身体防具の契約を適用しない。

## 起動・障害時の契約

ヘッドAPI取得は起動時イベント内のタイムアウト付きGET一回。全レスポンスの構造を検証した後に登録し、テクスチャとUUIDをそれぞれ重複排除する。リダイレクトは追跡しない。固定ヘッドはAPI接続失敗時も登録し、API由来ヘッドを読み込めなかったことをエラーログに出す。復旧後はProxyを再起動する。

登録対象を増やした場合は、Proxy再起動とBedrockクライアントの再接続が必要。通常のGeyser reloadでカスタム定義が再構築されることは前提にしない。

各UUIDで最初に解決できたスキンのテクスチャIDを、Extensionデータフォルダの `player-head-skins.json` に期限なしで保存する。次の起動では現在のUUID一覧にある保存済みの値を再利用し、未保存のUUIDだけMojangへ問い合わせる。初回は起動時にキャッシュを埋める。Mojangで解決できないUUID（Floodgate由来など）、通信失敗、起動時の取得上限で残ったUUIDは保存せず、次回の起動で再試行する。後からスキンを変えても、Extensionは自動で新しいテクスチャを登録しない。RPGが新しいテクスチャを送る場合、そのテクスチャに一致するカスタムヘッド定義が別途必要になる。

Mojang への問い合わせは起動ごとに順序を変え、最大8件並行で全体30秒まで待つ。HTTP 429を受け取った場合は、その起動中の新規問い合わせを打ち切る。キャッシュファイルを読み取れない場合は元ファイルを保持し、その起動ではユーザーUUID由来のヘッド登録を見送る。正常なバックアップの復元、ファイルの修復、または破損ファイルの退避を行ってからProxyを再起動する。

## 固定ヘッドを追加する場合

RPGに固定のプレイヤーヘッドスキンを追加・変更したら、`BuiltinHeadTextures` の定数と `ALL` も同じ変更で同期する。マスター由来テクスチャはこの定数へ追加しない。プログラムに設定する値はBase64の `textures` 値であり、仮のProfile UUIDではない。

公式参照: [Custom Skulls](https://geysermc.org/wiki/geyser/custom-skulls/)、[Extensions](https://geysermc.org/wiki/geyser/extensions/)。

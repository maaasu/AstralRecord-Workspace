/**
 * プラグインのイベントハンドラを管理・実装するパッケージです。
 * * <h3>イベント優先度 (EventPriority) の指針</h3>
 * 独自のイベント処理を実装する際は、以下の実行順序と用途を参考に優先度を設定してください。
 * * <table border="1">
 * <caption>イベント優先度の概要</caption>
 * <tr>
 * <th>優先度 (実行順)</th>
 * <th>説明</th>
 * <th>よくある用途</th>
 * </tr>
 * <tr>
 * <td>{@code LOWEST}</td>
 * <td>一番最初に実行される</td>
 * <td>他のプラグインに先んじてデータを読み込む</td>
 * </tr>
 * <tr>
 * <td>{@code LOW}</td>
 * <td>早めに実行される</td>
 * <td>標準より少し前の処理</td>
 * </tr>
 * <tr>
 * <td><b>{@code NORMAL}</b></td>
 * <td><b>デフォルト</b></td>
 * <td><b>特にこだわりがなければこれ</b></td>
 * </tr>
 * <tr>
 * <td>{@code HIGH}</td>
 * <td>遅めに実行される</td>
 * <td>他のプラグインの結果を上書き・修正する</td>
 * </tr>
 * <tr>
 * <td>{@code HIGHEST}</td>
 * <td>一番最後に実行される</td>
 * <td>最終的な結果（ダメージ量など）を確定させる</td>
 * </tr>
 * <tr>
 * <td>{@code MONITOR}</td>
 * <td>実行後の確認専用</td>
 * <td>ログ出力など。イベントの結果を変更してはいけない</td>
 * </tr>
 * </table>
 * * <p>注意: イベントをキャンセル（{@code setCancelled(true)}）する可能性がある場合は、
 * {@code HIGH} 以上を検討してください。逆に、単に情報を記録するだけなら {@code MONITOR} を使用します。</p>
 */
package io.github.maaasu.astralRecord.core.event; // 設置場所に合わせて変更してください



package io.github.maaasu.astralRecord.feature.sound.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理者が現在地へ任意のサウンドを再生するコマンドです。
 */
public final class SoundCommand extends AstCommand {

    private static final float DEFAULT_VOLUME = 1.0F;
    private static final float DEFAULT_PITCH = 1.0F;
    private static final float MIN_VOLUME = 0.0F;
    private static final float MAX_VOLUME = 10.0F;
    private static final float MIN_PITCH = 0.5F;
    private static final float MAX_PITCH = 2.0F;
    private static final Map<String, Sound> SOUNDS_BY_NAME = Registry.SOUND_EVENT.stream()
            .collect(Collectors.toUnmodifiableMap(SoundCommand::toCommandName, Function.identity()));
    private static final List<String> SOUND_NAMES = SOUNDS_BY_NAME.keySet().stream()
            .sorted()
            .toList();

    /**
     * 管理者サウンドコマンドを初期化します。
     */
    public SoundCommand() {
        super(
                "sound",
                "現在地でサウンドを再生します。",
                "/sound <sound> [volume] [pitch]",
                true,
                UserPermission.ADMIN.getValue()
        );
    }

    /**
     * コマンド引数を検証し、実行者の現在地で指定サウンドを再生します。
     * サウンド名はレジストリから解決し、音量とピッチは指定範囲および有限値を確認します。
     * 不正な入力では再生せず、実行者へエラーメッセージを送信します。
     * 正常終了時はワールドへサウンドを再生し、実行者へ成功メッセージを送信します。
     *
     * @param player コマンドを実行した管理者プレイヤー
     * @param args サウンド名、任意の音量、任意のピッチ。1〜3個で指定します
     */
    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 1 || args.length > 3) {
            sendUsage(player.getBukkit());
            return;
        }

        Sound sound = resolveSound(args[0]);
        if (sound == null) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_6931.getId(), args[0]));
            return;
        }

        Float volume = args.length >= 2
                ? parseParameter(player, args[1], "音量", MIN_VOLUME, MAX_VOLUME)
                : DEFAULT_VOLUME;
        if (volume == null) {
            return;
        }

        Float pitch = args.length >= 3
                ? parseParameter(player, args[2], "ピッチ", MIN_PITCH, MAX_PITCH)
                : DEFAULT_PITCH;
        if (pitch == null) {
            return;
        }

        player.getBukkit().getWorld().playSound(
                player.getBukkit().getLocation(),
                sound,
                SoundCategory.PLAYERS,
                volume,
                pitch
        );
        sendSuccess(
                player.getBukkit(),
                PlayerMsgResource.format(
                        PlayerMsgId.P_6930.getId(),
                        toCommandName(sound),
                        Float.toString(volume),
                        Float.toString(pitch)
                )
        );
    }

    /**
     * Tab補完と実行時解決で共有するBukkitサウンド名一覧を返します。
     *
     * @return Sound列挙値の名前をソートした変更不可リスト
     */
    static @NotNull List<String> getSoundNames() {
        return SOUND_NAMES;
    }

    /**
     * 入力されたサウンド名を大文字へ正規化し、登録済みサウンドから解決します。
     * 不明な値は例外を送出せず {@code null} を返します。
     *
     * @param value コマンドから受け取ったサウンド名
     * @return 解決したサウンド。不明な値の場合は {@code null}
     */
    private static @Nullable Sound resolveSound(@NotNull String value) {
        return SOUNDS_BY_NAME.get(value.toUpperCase(Locale.ROOT));
    }

    /**
     * サウンドのNamespacedKeyをコマンド向けの大文字名へ変換します。
     *
     * @param sound 登録済みのサウンド
     * @return ドット区切りをアンダースコアへ変換した大文字のサウンド名
     * @throws IllegalArgumentException サウンドがレジストリへ登録されていない場合
     */
    private static @NotNull String toCommandName(@NotNull Sound sound) {
        return Registry.SOUND_EVENT.getKeyOrThrow(sound).getKey()
                .replace('.', '_')
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
    }

    /**
     * 音量またはピッチの数値引数を有限値かつ指定範囲内で解析します。
     * 不正な入力や範囲外の入力の場合は実行者へエラーメッセージを送信します。
     *
     * @param player コマンド実行者
     * @param value 解析する入力値
     * @param parameterName エラーメッセージに表示する項目名
     * @param minimum 許容する最小値
     * @param maximum 許容する最大値
     * @return 解析済みの値。不正な場合は {@code null}
     */
    private @Nullable Float parseParameter(
            @NotNull AstPlayer player,
            @NotNull String value,
            @NotNull String parameterName,
            float minimum,
            float maximum
    ) {
        final float parsed;
        try {
            parsed = Float.parseFloat(value);
        } catch (NumberFormatException exception) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_6932.getId(), parameterName));
            return null;
        }

        if (!Float.isFinite(parsed)) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_6932.getId(), parameterName));
            return null;
        }
        if (parsed < minimum || parsed > maximum) {
            sendError(
                    player.getBukkit(),
                    PlayerMsgResource.format(
                            PlayerMsgId.P_6933.getId(),
                            parameterName,
                            Float.toString(minimum),
                            Float.toString(maximum)
                    )
            );
            return null;
        }
        return parsed;
    }
}

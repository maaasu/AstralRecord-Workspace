package io.github.maaasu.astralRecord.feature.channelboost;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import java.time.Instant;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** API永続状態をキャッシュし、期限と発動通知を全チャンネルで同期します。 */
public final class ChannelBoostService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        .withZone(ZoneId.of("Asia/Tokyo"));
    private final AstralRecord plugin;
    private final ChannelBoostRepository repository = new ChannelBoostRepository();
    private final String channelId;
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile ChannelBoostState state = ChannelBoostState.EMPTY;
    private volatile long eventCursor;
    private volatile boolean initialized;
    private BukkitTask task;
    private int secondsSincePoll;
    private double appliedExp = 1.0;
    private double appliedDrop = 1.0;

    /** 現在サーバーの識別子をconfigから固定して構築します。 */
    public ChannelBoostService(AstralRecord plugin) {
        this.plugin = plugin;
        this.channelId = ConfigProperties.getInstance().getNetworkChannelName().toLowerCase(Locale.ROOT);
    }

    /** 起動時に正本を読み、期限を毎秒、APIイベントを5秒ごとに追跡します。 */
    public void start() {
        poll();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            refreshStatusesIfChanged();
            if (++secondsSincePoll >= 5) {
                secondsSincePoll = 0;
                poll();
            }
        }, 20L, 20L);
    }

    /** 更新タスクを停止します。 */
    public void stop() { if (task != null) task.cancel(); }

    /** 現在チャンネルのEXP最終値へ掛ける倍率です。 */
    public double expFactor() {
        var channel = state.current(channelId);
        return channel == null ? 1.0 : channel.expFactorAt(Instant.now());
    }

    /** 現在チャンネルのDROP最終値へ掛ける倍率です。 */
    public double dropFactor() {
        var channel = state.current(channelId);
        return channel == null ? 1.0 : channel.dropFactorAt(Instant.now());
    }

    /** 表示用に現在の全チャンネル状態を返します。 */
    public ChannelBoostState current() { return state; }

    /** APIでチケット消費とチャンネル発動を原子的に実行します。保存lane内の非同期スレッドから呼びます。 */
    public JsonObject activate(JsonObject body) {
        try { return repository.activate(channelId, body); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CompletionException(error);
        } catch (java.io.IOException error) {
            throw new java.util.concurrent.CompletionException(error);
        }
    }

    /** API応答のチャンネル確定値を即時反映し、次回pollでも照合します。main threadから呼びます。 */
    public void applyActivation(JsonObject response) {
        if (!"COMPLETED".equals(response.get("status").getAsString())) return;
        poll();
    }

    /** server-info用の全チャンネル行を生成します。 */
    public List<String> displayLines() {
        List<String> lines = new ArrayList<>();
        Instant now = Instant.now();
        state.channels().values().stream().sorted(Comparator.comparing(ChannelBoostState.Channel::channelId))
            .forEach(channel -> lines.add(channel.channelId() + " EXP " + display(channel.exp(), now)
                + " / DROP " + display(channel.drop(), now)));
        return lines;
    }

    /** ネットワーク表示対象のうち、有効なブーストがあるチャンネルだけをTABへ返します。 */
    public List<Component> tabRows() {
        List<Component> rows = new ArrayList<>();
        Instant now = Instant.now();
        state.channels().values().stream().filter(ChannelBoostState.Channel::networkBoostEnabled)
            .sorted(Comparator.comparing(ChannelBoostState.Channel::channelId))
            .forEach(channel -> {
                boolean expActive = channel.exp() != null && channel.exp().activeAt(now);
                boolean dropActive = channel.drop() != null && channel.drop().activeAt(now);
                if (!expActive && !dropActive) return;
                Component row = Component.empty()
                    .append(Component.text(channel.displayName(), NamedTextColor.WHITE, TextDecoration.BOLD))
                    .append(Component.text("  ›  ", NamedTextColor.DARK_GRAY));
                if (expActive) row = row.append(tabBoost(channel.exp(), "EXP", NamedTextColor.GREEN, now));
                if (dropActive) {
                    if (expActive) row = row.append(Component.text("   ·   ", NamedTextColor.DARK_GRAY));
                    row = row.append(tabBoost(channel.drop(), "DROP", NamedTextColor.AQUA, now));
                }
                rows.add(row);
            });
        return rows;
    }

    /** 有効なブーストの倍率と期限までの残分数をTAB用に整形します。 */
    private static Component tabBoost(ChannelBoostState.Boost boost, String label, NamedTextColor color, Instant now) {
        long minutes = Math.max(1, (java.time.Duration.between(now, boost.expiresAt()).getSeconds() + 59) / 60);
        return Component.text(label + " ×" + BigDecimal.valueOf(boost.multiplier()).stripTrailingZeros().toPlainString(), color)
            .append(Component.text("  残り" + minutes + "分", NamedTextColor.GRAY));
    }

    /** server-info用に倍率・残分数を表示します。 */
    private static String display(ChannelBoostState.Boost boost, Instant now) {
        if (boost == null || !boost.activeAt(now)) return "通常";
        long seconds = Math.max(1, java.time.Duration.between(now, boost.expiresAt()).getSeconds());
        return boost.multiplier() + "倍 (残り" + ((seconds + 59) / 60) + "分)";
    }

    /** 前回のAPI読取と重複せず、イベントと全チャンネル正本を取得します。 */
    private void poll() {
        if (!plugin.isEnabled() || !polling.compareAndSet(false, true)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // 最初のcursorは正本のheadから取り、過去発動を再告知しません。
                if (!initialized) {
                    ChannelBoostState initial = ChannelBoostRepository.parse(repository.snapshot());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        state = initial;
                        eventCursor = initial.eventCursor();
                        initialized = true;
                        refreshStatusesIfChanged();
                        polling.set(false);
                    });
                    return;
                }
                JsonObject events = repository.events(eventCursor);
                ChannelBoostState latest = ChannelBoostRepository.parse(repository.snapshot());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    long next = events.get("eventCursor").getAsLong();
                    if (next > eventCursor) {
                        for (JsonElement element : events.getAsJsonArray("events")) {
                            JsonObject event = element.getAsJsonObject();
                            if (event.get("eventCursor").getAsLong() > eventCursor) announce(event);
                        }
                        eventCursor = next;
                    }
                    state = latest;
                    refreshStatusesIfChanged();
                    polling.set(false);
                });
            } catch (Exception error) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Logger.error(LogId.E_7610, error);
                    polling.set(false);
                });
            }
        });
    }

    /** API eventをこのbackendのオンラインプレイヤーへ一度送ります。 */
    private void announce(JsonObject event) {
        String kind = event.get("boostKind").getAsString();
        String label = switch (kind) {
            case "EXP" -> "EXP";
            case "DROP" -> "DROP";
            default -> "EXP・DROP";
        };
        for (AstPlayer player : AstPlayerCache.getAll()) {
            String accountName = event.get("accountName").getAsString();
            String tier = event.has("vipTier") && !event.get("vipTier").isJsonNull()
                ? event.get("vipTier").getAsString() : "NONE";
            var message = PlayerMsgResource.formatComponent(PlayerMsgId.P_7610.getId(),
                accountName, event.get("channelId").getAsString(),
                label, event.get("multiplier").getAsDouble(),
                TIME.format(Instant.parse(event.get("expiresAt").getAsString())));
            message = message.replaceText(builder -> builder.match(Pattern.compile(Pattern.quote(accountName)))
                .replacement(AccountDisplayNameFormatter.nameComponent(accountName, tier)));
            PlayerMessageService.getInstance().sendComponent(player.getBukkit(), message);
        }
    }

    /** 現在チャンネルの実効倍率が変化した場合だけ全員を再計算します。 */
    private void refreshStatusesIfChanged() {
        double exp = expFactor();
        double drop = dropFactor();
        if (exp == appliedExp && drop == appliedDrop) return;
        appliedExp = exp;
        appliedDrop = drop;
        for (AstPlayer player : AstPlayerCache.getAll()) plugin.getStatusService().refreshStatus(player);
    }
}

package io.github.maaasu.astralRecord.feature.skill.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 視線判定から受け取った状態を、本人の画面中央へ描画します。 */
final class SkillActionRingHud {
    static final int INTRO_FRAMES = 8;
    static final int CONFIRM_FRAMES = 8;
    static final int CLOSE_FRAMES = 4;

    private static final int HEARTBEAT_TICKS = 16;
    private static final int TITLE_STAY_TICKS = 22;
    private static final Key FONT = Key.key("astralrecord", "action_ring_hud");
    private static final String REWIND = "\uE400";
    private static final String BEDROCK_MARKER = "arw1";
    private static final TextColor DIMMED_ICON = TextColor.color(0x9a9eae);

    private final AstralRecord plugin;
    private final PlayerHudService playerHud;
    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
    private final Map<UUID, View> active = new ConcurrentHashMap<>();
    private final PacketAdapter listener;

    SkillActionRingHud(AstralRecord plugin, PlayerHudService playerHud) {
        this.plugin = plugin;
        this.playerHud = playerHud;
        listener = new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.SET_ACTION_BAR_TEXT,
            PacketType.Play.Server.SYSTEM_CHAT, PacketType.Play.Server.SET_TITLE_TEXT,
            PacketType.Play.Server.CLEAR_TITLES) {
            @Override
            public void onPacketSending(PacketEvent event) {
                intercept(event);
            }
        };
        protocol.addPacketListener(listener);
    }

    /** HUD の表示を開始します。 */
    View open(AstPlayer player, boolean bedrock) {
        View view = new View(player, bedrock);
        active.put(view.playerId, view);
        return view;
    }

    /** 残留中の表示と演出を解除します。 */
    void closeImmediately(Player player) {
        View view = active.get(player.getUniqueId());
        if (view != null) {
            view.finish();
        }
    }

    /** 全表示とパケット監視を終了します。 */
    void stop() {
        for (View view : List.copyOf(active.values())) {
            view.finish();
        }
        protocol.removePacketListener(listener);
    }

    private void intercept(PacketEvent event) {
        View view = active.get(event.getPlayer().getUniqueId());
        if (view == null || event.isCancelled()) {
            return;
        }
        PacketType type = event.getPacketType();
        if (view.bedrock) {
            // 統合版はリング表示中だけ ActionBar の表示を所有します。
            if (!view.sendFailed && (type == PacketType.Play.Server.SET_ACTION_BAR_TEXT
                || type == PacketType.Play.Server.SYSTEM_CHAT && Boolean.TRUE.equals(event.getPacket().getBooleans().readSafely(0)))) {
                event.setCancelled(true);
            }
        } else if (type == PacketType.Play.Server.SET_TITLE_TEXT || type == PacketType.Play.Server.CLEAR_TITLES) {
            // 到着したワールド案内や演出を優先し、閉じる際もその Title を消去しません。
            view.titleSuperseded = true;
        }
    }

    record Slot(int sprite, char state, int cooldownSteps) {}

    record Frame(List<Slot> slots, int focus, int confirmed, int timer, int intro, int confirmation,
                 int focusPulse, boolean hold, int cooldownTenths, Component detail) {}

    final class View {
        private final AstPlayer owner;
        private final Player player;
        private final UUID playerId;
        private final boolean bedrock;
        private Frame frame;
        private Component lastImage;
        private Component lastDetail;
        private int lastSent;
        private volatile boolean titleSuperseded;
        private volatile boolean sendFailed;
        private boolean publishAttempted;
        private BukkitTask closing;
        private int closeAge;

        private View(AstPlayer owner, boolean bedrock) {
            this.owner = owner;
            player = owner.getBukkit();
            playerId = player.getUniqueId();
            this.bedrock = bedrock;
        }

        /** 外部通知または送信失敗でリング表示が終了したかを返します。 */
        boolean interrupted() {
            return titleSuperseded || sendFailed;
        }

        /** 描画が変化したとき、または表示期限の前に、画面固定のフレームを送ります。 */
        void render(Frame next) {
            frame = next;
            int opacity = Math.min(5, next.intro() + 1);
            publish(next, opacity, next.intro());
        }

        /** リング操作を終え、通常終了時は短い閉じる演出を送ります。 */
        void close(boolean animate) {
            if (!animate || frame == null || interrupted() || !player.isOnline()) {
                finish();
                return;
            }
            if (closing == null) {
                closing = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                    if (interrupted() || !player.isOnline() || ++closeAge > CLOSE_FRAMES) {
                        finish();
                        return;
                    }
                    publish(frame, 5 - closeAge, Math.min(frame.intro(), (CLOSE_FRAMES - closeAge) * 2));
                }, 1L, 1L);
            }
        }

        private void publish(Frame next, int opacity, int intro) {
            if (interrupted()) {
                return;
            }
            int tick = plugin.getServer().getCurrentTick();
            Component image = bedrock ? Component.text(bedrockFrame(next, opacity)) : javaFrame(next, intro, opacity);
            Component detail = bedrock || opacity <= 2 ? Component.empty() : next.detail();
            if (image.equals(lastImage) && detail.equals(lastDetail) && tick - lastSent < HEARTBEAT_TICKS) {
                return;
            }

            try {
                publishAttempted = true;
                if (bedrock) {
                    sendText(PacketType.Play.Server.SET_ACTION_BAR_TEXT, image);
                } else {
                    if (lastImage == null) {
                        PacketContainer times = protocol.createPacket(PacketType.Play.Server.SET_TITLES_ANIMATION);
                        times.getIntegers().write(0, 0).write(1, TITLE_STAY_TICKS).write(2, 2);
                        protocol.sendServerPacket(player, times, false);
                    }
                    sendText(PacketType.Play.Server.SET_SUBTITLE_TEXT, detail);
                    sendText(PacketType.Play.Server.SET_TITLE_TEXT, image);
                }
                lastImage = image;
                lastDetail = detail;
                lastSent = tick;
            } catch (RuntimeException exception) {
                sendFailed = true;
                Logger.log(LogId.W_9000, "skill_action_ring_hud", player.getWorld().getName(),
                    exception.getClass().getSimpleName());
            }
        }

        private void sendText(PacketType type, Component component) {
            PacketContainer packet = protocol.createPacket(type);
            packet.getChatComponents().write(0,
                WrappedChatComponent.fromJson(GsonComponentSerializer.gson().serialize(component)));
            protocol.sendServerPacket(player, packet, false);
        }

        private void finish() {
            if (!active.remove(playerId, this)) {
                return;
            }
            if (closing != null) {
                closing.cancel();
                closing = null;
            }
            if (!player.isOnline() || !publishAttempted) {
                return;
            }
            try {
                if (bedrock) {
                    sendText(PacketType.Play.Server.SET_ACTION_BAR_TEXT, Component.text(BEDROCK_MARKER + "|p0|"));
                    sendText(PacketType.Play.Server.SET_ACTION_BAR_TEXT, Component.empty());
                    playerHud.refreshActionBar(owner);
                } else if (!titleSuperseded) {
                    PacketContainer clear = protocol.createPacket(PacketType.Play.Server.CLEAR_TITLES);
                    clear.getBooleans().write(0, true);
                    protocol.sendServerPacket(player, clear, false);
                }
            } catch (RuntimeException exception) {
                Logger.log(LogId.W_9000, "skill_action_ring_hud_close", player.getWorld().getName(),
                    exception.getClass().getSimpleName());
            }
        }
    }

    /** 選択面・アイコン・ゲージの字形を同じ画面座標に重ねます。 */
    private static Component javaFrame(Frame frame, int intro, int opacity) {
        TextComponent.Builder output = Component.text();
        if (frame.confirmed() >= 0) {
            int confirmation = Math.min(frame.confirmation(), opacity * 2 - 1);
            TextColor fade = opacity >= 5 ? null : TextColor.color(51 * opacity, 51 * opacity, 51 * opacity);
            appendGlyph(output, 0xe409 + confirmation, fade, false);
            Slot slot = frame.slots().get(frame.confirmed());
            if (opacity >= 2) {
                appendGlyph(output, 0xf000 + slot.sprite(), fade, true);
            }
            return output.build();
        }

        appendGlyph(output, 0xe401 + intro, null, false);
        if (opacity < 3) {
            return output.build();
        }
        if (frame.focus() >= 0) {
            int focusGlyph = frame.focusPulse() < 3 ? 0xe4d0 + frame.focus() * 3 + frame.focusPulse()
                : 0xe420 + frame.focus();
            appendGlyph(output, focusGlyph, null, true);
        }
        for (int index = 0; index < frame.slots().size(); index++) {
            Slot slot = frame.slots().get(index);
            if (slot.sprite() == 0) {
                continue;
            }
            appendGlyph(output, 0xe800 + index * 256 + slot.sprite(), slot.state() == 'r' ? null : DIMMED_ICON, true);
            int warning = switch (slot.state()) {
                case 'c' -> 0xe428;
                case 'm' -> 0xe430;
                case 'e' -> 0xe438;
                case 'b', 'u' -> 0xe440;
                default -> 0;
            };
            if (warning != 0) {
                appendGlyph(output, warning + index, null, true);
            }
            if (slot.cooldownSteps() > 0) {
                appendGlyph(output, 0xe470 + index * 10 + slot.cooldownSteps() - 1, null, true);
            }
        }
        appendGlyph(output, 0xe450 + frame.timer(), null, true);
        return output.build();
    }

    private static void appendGlyph(TextComponent.Builder output, int code, TextColor color, boolean overlay) {
        String glyph = String.valueOf((char) code);
        output.append(Component.text(overlay ? REWIND + glyph : glyph).font(FONT).color(color));
    }

    /** 統合版の UI が読む文字列を、固定幅の ASCII トークンで組み立てます。 */
    private static String bedrockFrame(Frame frame, int opacity) {
        StringBuilder output = new StringBuilder(BEDROCK_MARKER).append("|p").append(opacity).append('|');
        if (frame.confirmed() >= 0) {
            output.append("|a||q").append(frame.confirmation()).append('|');
            appendSprite(output, 8, frame.slots().get(frame.confirmed()).sprite());
            return output.toString();
        }

        if (frame.hold()) {
            output.append("|h|");
        }
        if (frame.focus() >= 0) {
            output.append("|f").append(frame.focus()).append('|');
            if (frame.focusPulse() < 3) {
                output.append("|l").append(frame.focusPulse()).append('|');
            }
        }
        output.append("|t");
        appendTwoDigits(output, frame.timer());
        output.append('|');
        for (int index = 0; index < frame.slots().size(); index++) {
            Slot slot = frame.slots().get(index);
            appendSprite(output, index, slot.sprite());
            output.append("|s").append(index).append(slot.state()).append('|');
            if (slot.cooldownSteps() > 0) {
                output.append("|g").append(index);
                appendTwoDigits(output, slot.cooldownSteps());
                output.append('|');
            }
        }
        if (frame.cooldownTenths() >= 0) {
            int tenths = Math.min(9_999, frame.cooldownTenths());
            output.append("|d0").append(tenths / 1000).append("||d1").append(tenths / 100 % 10)
                .append("||d2").append(tenths / 10 % 10).append("||d3").append(tenths % 10).append('|');
            if (frame.cooldownTenths() > tenths) {
                output.append("|big|");
            }
        }
        return output.toString();
    }

    private static void appendSprite(StringBuilder output, int slot, int sprite) {
        if (sprite > 0) {
            output.append("|i").append(slot).append(sprite / 100);
            appendTwoDigits(output, sprite % 100);
            output.append('|');
        }
    }

    private static void appendTwoDigits(StringBuilder output, int value) {
        output.append(value / 10).append(value % 10);
    }
}

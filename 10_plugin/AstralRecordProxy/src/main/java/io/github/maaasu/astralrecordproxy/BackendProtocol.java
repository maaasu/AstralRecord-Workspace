package io.github.maaasu.astralrecordproxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class BackendProtocol {
    static final String CHANNEL = "astralrecord:network";
    static final String CONNECT = "connect";
    static final String METADATA = "metadata";
    static final String CHAT = "chat";
    static final String SERVER_METRICS = "server_metrics";
    static final String PRIVATE_CHAT = "private_chat";
    static final String DIRECT_MESSAGE = "direct_message";
    static final String OPEN_MENU = "open_menu";
    static final String PREPARE_CONNECT = "prepare_connect";
    static final String CHANNEL_CONNECTION = "channel_connection";
    static final String GAMEPLAY_SOUNDS_READY = "gameplay_sounds_ready";
    static final String ITEM_TOOLTIPS_READY = "item_tooltips_ready";
    static final String SKILL_EFFECTS_READY = "skill_effects_ready";
    static final String WEAPON_ATTACK_PROJECTILES_READY = "weapon_attack_projectiles_ready";
    static final String SELENEA_READY = "selenea_ready";
    static final String ACTION_RING_HUD_READY = "action_ring_hud_ready";
    static final String BEDROCK_SKILL_MENU = "bedrock_skill_menu";
    static final String BEDROCK_SKILL_MENU_RESULT = "bedrock_skill_menu_result";
    static final int SKILL_MENU_NO_SELECTION = -1;
    private static final int MAX_SKILL_MENU_BUTTONS = 8;

    private BackendProtocol() {
    }

    static Incoming decode(byte[] payload) throws IOException {
        if (payload.length > 32_767) {
            throw new IOException("Plugin message is too large");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String type = input.readUTF();
            return switch (type) {
                case CONNECT -> new Connect(input.readUTF(), input.available() >= Integer.BYTES ? input.readInt() : 0);
                case METADATA -> {
                    UUID playerId = UUID.fromString(input.readUTF());
                    String mcid = input.readUTF();
                    String channel = input.readUTF();
                    String displayName = input.readUTF();
                    int level = input.readInt();
                    String className = input.readUTF();
                    boolean afk = input.readBoolean();
                    int permission = input.available() >= Integer.BYTES ? input.readInt() : 0;
                    boolean classLevelMax = input.available() > 0 && input.readBoolean();
                    boolean accountNameOnly = input.available() > 0 && input.readBoolean();
                    VipTier vipTier = input.available() > 0 ? VipTier.parse(input.readUTF()) : VipTier.NONE;
                    Integer playerLevel = input.available() > 0 ? input.readInt() : null;
                    yield new Metadata(
                        playerId, mcid, channel, displayName, level, className, afk, permission,
                        classLevelMax, accountNameOnly, vipTier, playerLevel);
                }
                case CHAT -> {
                    UUID messageId = UUID.fromString(input.readUTF());
                    UUID playerId = UUID.fromString(input.readUTF());
                    String authorName = input.readUTF();
                    String channel = input.readUTF();
                    String displayName = input.readUTF();
                    int level = input.readInt();
                    String className = input.readUTF();
                    String original = input.readUTF();
                    String converted = input.available() > 0 ? input.readUTF() : original;
                    yield new Chat(messageId, playerId, authorName, channel, displayName, level, className, original, converted);
                }
                case SERVER_METRICS -> new ServerMetrics(input.readDouble());
                case BEDROCK_SKILL_MENU -> readBedrockSkillMenu(input);
                // 旧RPGが更新前に受け取った対応通知で送信を続けている場合は破棄する。
                case "bedrock_profile_hud", "bedrock_profile_hud_images" -> Retired.INSTANCE;
                case PRIVATE_CHAT -> {
                    UUID playerId = UUID.fromString(input.readUTF());
                    String chatType = input.readUTF();
                    String senderName = input.readUTF();
                    String targetName = input.readUTF();
                    String partyName = input.readUTF();
                    String original = input.readUTF();
                    String converted = input.available() > 0 ? input.readUTF() : original;
                    yield new PrivateChat(
                        playerId, chatType, senderName, targetName, partyName, original, converted,
                        readParticipantIds(input));
                }
                case DIRECT_MESSAGE -> {
                    UUID playerId = UUID.fromString(input.readUTF());
                    String targetName = input.readUTF();
                    String senderName = input.readUTF();
                    int senderLevel = input.readInt();
                    String original = input.readUTF();
                    String converted = input.available() > 0 ? input.readUTF() : original;
                    yield new DirectMessage(playerId, targetName, senderName, senderLevel, original, converted);
                }
                default -> throw new IOException("Unknown plugin message type: " + type);
            };
        }
    }

    private static BedrockSkillMenu readBedrockSkillMenu(DataInputStream input) throws IOException {
        UUID token = new UUID(input.readLong(), input.readLong());
        int count = input.readUnsignedByte();
        if (count == 0 || count > MAX_SKILL_MENU_BUTTONS) {
            throw new IOException("Bedrock skill menu button count is invalid");
        }
        List<String> labels = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            labels.add(input.readUTF());
        }
        if (input.available() > 0) {
            throw new IOException("Bedrock skill menu payload has trailing data");
        }
        return new BedrockSkillMenu(token, List.copyOf(labels));
    }

    static byte[] bedrockSkillMenuResult(UUID token, int index) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF(BEDROCK_SKILL_MENU_RESULT);
                output.writeLong(token.getMostSignificantBits());
                output.writeLong(token.getLeastSignificantBits());
                output.writeByte(index);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static byte[] openMenu() {
        return outgoing(OPEN_MENU, null);
    }

    /** @return 対象プレイヤーのBedrockパックが効果音に対応していることを伝える通知 */
    static byte[] gameplaySoundsReady() {
        return outgoing(GAMEPLAY_SOUNDS_READY, null);
    }

    /** @return 対象プレイヤーの Bedrock パックがツールチップに対応していることを伝える通知 */
    static byte[] itemTooltipsReady() {
        return outgoing(ITEM_TOOLTIPS_READY, null);
    }

    /** @return 対象プレイヤーの統合版パックが通常攻撃の飛翔演出に対応していることを伝える通知 */
    static byte[] skillEffectsReady() {
        return outgoing(SKILL_EFFECTS_READY, null);
    }

    static byte[] weaponAttackProjectilesReady() {
        return outgoing(WEAPON_ATTACK_PROJECTILES_READY, null);
    }

    /** @return 対象プレイヤーの統合版パックがセレネアの描画に対応していることを伝える通知 */
    static byte[] seleneaReady() {
        return outgoing(SELENEA_READY, null);
    }

    /** @return 対象プレイヤーの Bedrock パックのアクションリング HUD 互換性識別子を伝える通知 */
    static byte[] actionRingHudReady(String revision) {
        return outgoing(ACTION_RING_HUD_READY, revision);
    }

    private static Set<UUID> readParticipantIds(DataInputStream input) throws IOException {
        if (input.available() == 0) {
            return Set.of();
        }
        if (input.available() < Integer.BYTES) {
            throw new IOException("Private chat participant count is missing");
        }
        int count = input.readInt();
        if (count < 0 || count > 64) {
            throw new IOException("Private chat participant count is invalid");
        }
        Set<UUID> participantIds = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            try {
                participantIds.add(UUID.fromString(input.readUTF()));
            } catch (IllegalArgumentException | IOException exception) {
                throw new IOException("Private chat participant UUID is invalid", exception);
            }
        }
        if (input.available() > 0) {
            throw new IOException("Private chat payload has trailing data");
        }
        return Set.copyOf(participantIds);
    }

    /** RPG backendへ保存完了後のチャンネル接続要求を指示する。 */
    static byte[] prepareConnect(String targetServer) {
        return outgoing(PREPARE_CONNECT, targetServer);
    }

    /**
     * backendの受信プレイヤーへ表示するチャンネル接続通知を生成します。
     * @param playerName 接続したプレイヤーのMinecraft名
     * @param channelName 管理設定のチャンネル表示名
     * @return 通知payload
     */
    static byte[] channelConnection(String playerName, String channelName) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF(CHANNEL_CONNECTION);
                output.writeUTF(playerName);
                output.writeUTF(channelName);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] outgoing(String type, String value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF(type);
                if (value != null) output.writeUTF(value);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    sealed interface Incoming permits Connect, Metadata, Chat, ServerMetrics, PrivateChat, DirectMessage,
        BedrockSkillMenu, Retired {
    }

    record Connect(String targetServer, int permission) implements Incoming {
    }

    record BedrockSkillMenu(UUID token, List<String> labels) implements Incoming {
    }

    record Metadata(
        UUID playerId,
        String mcid,
        String channel,
        String displayName,
        int level,
        String className,
        boolean afk,
        int permission,
        boolean classLevelMax,
        boolean accountNameOnly,
        VipTier vipTier,
        Integer playerLevel
    ) implements Incoming {
    }

    record Chat(
        UUID messageId,
        UUID playerId,
        String authorName,
        String channel,
        String displayName,
        int level,
        String className,
        String original,
        String converted
    ) implements Incoming {
    }

    enum Retired implements Incoming {
        INSTANCE
    }

    record ServerMetrics(double mspt) implements Incoming {
    }

    record PrivateChat(
        UUID playerId,
        String type,
        String senderName,
        String targetName,
        String partyName,
        String original,
        String converted,
        Set<UUID> participantIds
    ) implements Incoming {
    }

    record DirectMessage(
        UUID playerId,
        String targetName,
        String senderName,
        int senderLevel,
        String original,
        String converted
    ) implements Incoming {
    }
}

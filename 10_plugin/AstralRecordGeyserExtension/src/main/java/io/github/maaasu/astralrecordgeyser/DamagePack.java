package io.github.maaasu.astralrecordgeyser;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

/** APIで公開されたBedrockパックを検証し、書き換えないファイルとして保存する。 */
record DamagePack(
    String sha256, Path file, ResourcePack pack, boolean damageNumbers, boolean actionRing,
    boolean combatResult, boolean weaponAttack, Set<Integer> heldWeaponModels, Set<ItemModel> itemModels,
    Set<Integer> actionRingSprites
) {
    private static final String ROOT = "../resource-packs/";
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int MAX_PACK_BYTES = 32 * 1024 * 1024;
    private static final List<String> DAMAGE_NUMBER_ENTRIES = List.of(
        "entity/damage_number.entity.json", "animations/damage_number.animation.json",
        "models/entity/damage_number.geo.json", "render_controllers/damage_number.render_controllers.json",
        "textures/entity/damage_number/normal.png", "textures/entity/damage_number/critical.png",
        "textures/entity/damage_number/shield.png", "textures/entity/damage_number/burning.png",
        "textures/entity/damage_number/shocked.png", "textures/entity/damage_number/poison.png",
        "textures/entity/damage_number/condition.png", "textures/entity/damage_number/pet.png"
    );
    private static final List<String> ACTION_RING_ENTRIES = List.of(
        "entity/action_ring.entity.json", "animations/action_ring.animation.json",
        "models/entity/action_ring.geo.json", "render_controllers/action_ring.render_controllers.json",
        "textures/entity/action_ring.png"
    );
    private static final List<String> ACTION_RING_PANEL_ENTRIES = List.of(
        "entity/action_ring_panel.entity.json", "animations/action_ring_panel.animation.json",
        "animations/action_ring.animation.json", "models/entity/action_ring_panel.geo.json",
        "render_controllers/action_ring_panel.render_controllers.json",
        "textures/entity/action_ring_rim.png", "textures/entity/action_ring_panel.png"
    );
    private static final String PANEL_SPRITES_PREFIX = "textures/entity/action_ring_icons/";
    private static final List<String> COMBAT_RESULT_ENTRIES = List.of(
        "entity/combat_result.entity.json", "animations/combat_result.animation.json",
        "models/entity/combat_result.geo.json", "render_controllers/combat_result.render_controllers.json",
        "textures/entity/combat_result.png"
    );
    private static final List<String> WEAPON_ATTACK_ENTRIES = List.of(
        "entity/weapon_attack.entity.json", "animations/weapon_attack.animation.json",
        "models/entity/weapon_attack.geo.json", "render_controllers/weapon_attack.render_controllers.json",
        "textures/entity/weapon_attack/sword.png", "textures/entity/weapon_attack/hammer.png",
        "textures/entity/weapon_attack/spear.png", "textures/entity/weapon_attack/bow.png",
        "textures/entity/weapon_attack/wand.png", "textures/entity/weapon_attack/staff.png"
    );

    static DamagePack refresh(HeadApiConfig config, Path directory, DamagePack current)
        throws IOException, InterruptedException {
        try (HttpClient client = HeadApiClient.newClient(config)) {
            byte[] manifest = get(client, config, config.endpoint.resolve(ROOT + "manifest"), "application/json", MAX_MANIFEST_BYTES);
            var bedrock = JsonParser.parseString(new String(manifest, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("bedrock");
            String sha256 = bedrock.get("sha256").getAsString();
            String rawUuid = bedrock.get("uuid").getAsString();
            UUID uuid = UUID.fromString(rawUuid);
            if (!sha256.matches("[0-9a-f]{64}") || !uuid.toString().equalsIgnoreCase(rawUuid)) {
                throw new IOException("Invalid Bedrock pack manifest");
            }
            if (current != null && sha256.equals(current.sha256)) {
                if (!uuid.equals(current.pack.uuid())) throw new IOException("Bedrock pack UUID mismatch");
                return current;
            }

            byte[] bytes = get(client, config, config.endpoint.resolve(ROOT + "bedrock/" + sha256 + ".mcpack"),
                "application/octet-stream", MAX_PACK_BYTES);
            if (!sha256.equals(sha256(bytes))) throw new IOException("Bedrock pack SHA-256 mismatch");
            return load(directory, bytes, sha256, uuid);
        }
    }

    private static DamagePack load(Path directory, byte[] bytes, String sha256, UUID uuid) throws IOException {
        Files.createDirectories(directory);
        Path file = Files.createTempFile(directory, "damage-", ".mcpack");
        boolean keep = false;
        try {
            Files.write(file, bytes);
            boolean damageNumberAssets;
            boolean actionRing;
            boolean combatResult;
            boolean weaponAttack;
            Set<Integer> heldWeaponModels;
            Set<ItemModel> itemModels;
            Set<Integer> actionRingSprites;
            try (ZipFile zip = new ZipFile(file.toFile())) {
                var manifest = zip.getEntry("manifest.json");
                // Geyser reads entries containing manifest.json. Require only the root manifest and bound its size.
                if (manifest == null || manifest.getSize() > MAX_MANIFEST_BYTES
                    || zip.stream().filter(entry -> entry.getName().contains("manifest.json")).count() != 1) {
                    throw new IOException("Invalid Bedrock pack archive manifest");
                }
                try (var input = zip.getInputStream(manifest)) {
                    if (input.readNBytes(MAX_MANIFEST_BYTES + 1).length > MAX_MANIFEST_BYTES) {
                        throw new IOException("Bedrock pack archive manifest is too large");
                    }
                }
                damageNumberAssets = DAMAGE_NUMBER_ENTRIES.stream().map(zip::getEntry)
                    .allMatch(entry -> entry != null && !entry.isDirectory() && entry.getSize() > 0);
                actionRing = ACTION_RING_ENTRIES.stream().allMatch(entry -> zip.getEntry(entry) != null);
                combatResult = COMBAT_RESULT_ENTRIES.stream().map(zip::getEntry)
                    .allMatch(entry -> entry != null && !entry.isDirectory() && entry.getSize() > 0);
                weaponAttack = WEAPON_ATTACK_ENTRIES.stream().map(zip::getEntry)
                    .allMatch(entry -> entry != null && !entry.isDirectory() && entry.getSize() > 0);
                var catalog = ItemModel.read(zip);
                itemModels = catalog.models();
                heldWeaponModels = ItemModel.verifiedHeldWeaponModels(zip, catalog);
                actionRingSprites = readActionRingSprites(zip);
            }
            var pack = ResourcePack.create(PackCodec.path(file));
            if (!uuid.equals(pack.uuid())) throw new IOException("Bedrock pack UUID mismatch");
            var version = pack.manifest().header().version();
            boolean damageNumbers = damageNumberAssets && version != null
                && (version.major() > 1 || (version.major() == 1 && version.minor() >= 1));
            file.toFile().deleteOnExit();
            keep = true;
            return new DamagePack(sha256, file, pack, damageNumbers, actionRing, combatResult, weaponAttack,
                heldWeaponModels, itemModels, actionRingSprites);
        } finally {
            if (!keep) Files.deleteIfExists(file);
        }
    }

    private static Set<Integer> readActionRingSprites(ZipFile zip) {
        if (ACTION_RING_PANEL_ENTRIES.stream().map(zip::getEntry).anyMatch(entry -> !present(entry))) {
            return Set.of();
        }
        Set<Integer> sprites = new HashSet<>();
        for (int id = 1; id <= 254; id++) {
            if (present(zip.getEntry(PANEL_SPRITES_PREFIX + id + ".png"))) {
                sprites.add(id);
            }
        }
        return Set.copyOf(sprites);
    }

    private static boolean present(ZipEntry entry) {
        return entry != null && !entry.isDirectory() && entry.getSize() > 0;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static byte[] get(HttpClient client, HeadApiConfig config, URI uri, String accept, int maxBytes)
        throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(config.timeout)
            .header("X-Api-Key", config.apiKey).header("Accept", accept).GET().build();
        var pending = client.sendAsync(request, info -> new BoundedBody(info.statusCode() == 200 ? maxBytes : 0));
        try {
            HttpResponse<byte[]> response = pending.get(config.timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new IOException("Pack API returned HTTP " + response.statusCode());
            return response.body();
        } catch (TimeoutException | ExecutionException exception) {
            throw new IOException("Failed to download Bedrock pack API response", exception);
        } finally {
            pending.cancel(true);
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
        private int remaining;
        private Flow.Subscription subscription;

        private BoundedBody(int maxBytes) {
            remaining = maxBytes;
        }

        @Override public CompletionStage<byte[]> getBody() {
            return body.getBody();
        }

        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            body.onSubscribe(subscription);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > remaining) {
                    subscription.cancel();
                    body.onError(new IOException("Bedrock pack API response is too large"));
                    return;
                }
                remaining -= buffer.remaining();
            }
            body.onNext(buffers);
        }

        @Override public void onError(Throwable error) {
            body.onError(error);
        }

        @Override public void onComplete() {
            body.onComplete();
        }
    }
}

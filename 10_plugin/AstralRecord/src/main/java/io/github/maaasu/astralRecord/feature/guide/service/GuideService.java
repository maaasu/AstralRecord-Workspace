package io.github.maaasu.astralRecord.feature.guide.service;

import io.github.maaasu.astralRecord.feature.guide.model.GuideEntry;
import io.github.maaasu.astralRecord.feature.guide.repository.GuideRepository;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GuideService {
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("\\{([a-zA-Z_]+):([^}]+)}");

    private final GuideRepository repository;
    private final ItemService itemService;
    private final PlayerClassService playerClassService;
    private final WorldService worldService;
    private final Map<String, GuideEntry> loadedGuides = new LinkedHashMap<>();

    public GuideService(
        @NotNull GuideRepository repository,
        @NotNull ItemService itemService,
        @NotNull PlayerClassService playerClassService,
        @NotNull WorldService worldService
    ) {
        this.repository = repository;
        this.itemService = itemService;
        this.playerClassService = playerClassService;
        this.worldService = worldService;
    }

    public synchronized int loadAll() {
        try {
            List<GuideEntry> guides = repository.findAll().stream()
                .sorted(Comparator
                    .comparingInt((GuideEntry guide) -> categoryOrder(guide.category()))
                    .thenComparingInt(GuideEntry::displayOrder)
                    .thenComparing(GuideEntry::id))
                .toList();
            loadedGuides.clear();
            for (GuideEntry guide : guides) {
                loadedGuides.put(guide.id(), guide);
            }
            return loadedGuides.size();
        } catch (RuntimeException e) {
            Logger.log(LogId.E_5200, e);
            return loadedGuides.size();
        }
    }

    public synchronized @NotNull List<GuideEntry> getAll() {
        return loadedGuides.values().stream()
            .sorted(Comparator
                .comparingInt((GuideEntry guide) -> categoryOrder(guide.category()))
                .thenComparingInt(GuideEntry::displayOrder)
                .thenComparing(GuideEntry::id))
            .toList();
    }

    public synchronized @Nullable GuideEntry getById(@NotNull String guideId) {
        return loadedGuides.get(guideId);
    }

    public @NotNull String resolveText(@NotNull String text) {
        Matcher matcher = REFERENCE_PATTERN.matcher(text);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String replacement = resolveReference(matcher.group(1), matcher.group(2));
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private @NotNull String resolveReference(@NotNull String type, @NotNull String id) {
        String normalizedType = type.trim().toLowerCase(Locale.ROOT);
        String normalizedId = id.trim();
        return switch (normalizedType) {
            case "item" -> resolveItem(normalizedId);
            case "class" -> playerClassService.getDisplayName(normalizedId);
            case "world" -> resolveWorld(normalizedId);
            case "menu" -> menuName(normalizedId);
            default -> normalizedId;
        };
    }

    private @NotNull String resolveItem(@NotNull String itemId) {
        ItemModel item = itemService.findLoadedById(itemId);
        return item == null ? itemId : item.getName();
    }

    private @NotNull String resolveWorld(@NotNull String worldId) {
        WorldMasterData world = worldService.getById(worldId);
        return world == null ? worldId : world.displayName();
    }

    private @NotNull String menuName(@NotNull String menuId) {
        return switch (menuId.trim().toLowerCase(Locale.ROOT)) {
            case "equipment" -> "&6装備";
            case "skill_bind" -> "&bスキル設定";
            case "status" -> "&eステータス";
            case "guide" -> "&dガイド";
            default -> menuId;
        };
    }

    private static int categoryOrder(@NotNull String category) {
        return switch (category.trim().toLowerCase(Locale.ROOT)) {
            case "beginner" -> 10;
            case "equipment" -> 20;
            case "skill" -> 30;
            case "world" -> 40;
            default -> 100;
        };
    }
}

package io.github.maaasu.astralRecord.feature.menu.service;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiCloseSoundPolicyTest extends MockBukkitTestBase {
    private static final Path MAIN_JAVA_ROOT = Path.of("src/main/java");
    private static final String CENTRAL_CLOSE_HANDLER =
        "io/github/maaasu/astralRecord/feature/menu/event/MenuOpenEventHandler.java";
    private static final String TRANSITION_SERVICE =
        "io/github/maaasu/astralRecord/feature/menu/service/MenuGuiTransitionService.java";
    private static final Pattern INVENTORY_CLOSE_METHOD = Pattern.compile(
        "\\bvoid\\s+\\w+\\s*\\([^)]*InventoryCloseEvent[^)]*\\)"
    );

    @Test
    void suppressedCloseSoundIsConsumedOnceByCentralState() throws Exception {
        PlayerMock player = server().addPlayer();
        Method consume = MenuGuiTransitionService.class.getDeclaredMethod("consumeSuppressedCloseSound", Player.class);
        consume.setAccessible(true);

        MenuGuiTransitionService.suppressNextCloseSound(player);

        assertTrue(invokeConsume(consume, player));
        assertFalse(invokeConsume(consume, player));
    }

    @Test
    void inventoryCloseHandlersDoNotPlayGuiCloseSoundOutsideCentralHandler() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles()) {
            String relativePath = relativePath(file);
            if (CENTRAL_CLOSE_HANDLER.equals(relativePath)) {
                continue;
            }
            String content = Files.readString(file);
            if (!content.contains("InventoryCloseEvent") || !content.contains("GuiSound.CLOSE.play")) {
                continue;
            }
            if (inventoryCloseMethods(content).stream().anyMatch(method -> method.contains("GuiSound.CLOSE.play"))) {
                offenders.add(relativePath);
            }
        }

        assertTrue(offenders.isEmpty(), "InventoryCloseEvent close sound must stay in the central handler: " + offenders);
    }

    @Test
    void suppressedCloseSoundIsConsumedOnlyInsideTransitionService() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles()) {
            String relativePath = relativePath(file);
            if (TRANSITION_SERVICE.equals(relativePath)) {
                continue;
            }
            String content = Files.readString(file);
            if (content.contains("consumeSuppressedCloseSound")) {
                offenders.add(relativePath);
            }
        }

        assertTrue(offenders.isEmpty(), "Close sound suppression consumption must stay inside MenuGuiTransitionService: " + offenders);
    }

    private static boolean invokeConsume(Method method, Player player)
        throws InvocationTargetException, IllegalAccessException {
        return (boolean) method.invoke(null, player);
    }

    private static List<Path> javaFiles() throws IOException {
        try (var stream = Files.walk(MAIN_JAVA_ROOT)) {
            return stream
                .filter(path -> path.toString().endsWith(".java"))
                .toList();
        }
    }

    private static String relativePath(Path file) {
        return MAIN_JAVA_ROOT.relativize(file).toString().replace('\\', '/');
    }

    private static List<String> inventoryCloseMethods(String content) {
        List<String> methods = new ArrayList<>();
        Matcher matcher = INVENTORY_CLOSE_METHOD.matcher(content);
        while (matcher.find()) {
            int bodyStart = content.indexOf('{', matcher.end());
            if (bodyStart < 0) {
                continue;
            }
            int bodyEnd = findMatchingBrace(content, bodyStart);
            if (bodyEnd > bodyStart) {
                methods.add(content.substring(bodyStart, bodyEnd + 1));
            }
        }
        return methods;
    }

    private static int findMatchingBrace(String content, int openBraceIndex) {
        int depth = 0;
        for (int index = openBraceIndex; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }
}

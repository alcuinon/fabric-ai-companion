package net.nian.aicompanion.monitor;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.nian.aicompanion.AICompanionClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerInteractionHandler {

    private static final MinecraftClient client = MinecraftClient.getInstance();
    private static final Map<UUID, Long> lastMessageTime = new HashMap<>();
    private static final int MESSAGE_COOLDOWN_TICKS = 20 * 10; // 10 seconds

    // Register the event
    public static void registerEvent() {
        // Register the client tick event to detect screen interaction
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return; // Avoid null pointer exceptions

            ScreenHandler screenHandler = client.player.currentScreenHandler;
            UUID uuid = client.player.getUuid();
            long currentTime = client.world.getTime();
            boolean sentMessage = false;

            // Cooldown
            if (lastMessageTime.getOrDefault(uuid, 0L) > currentTime - MESSAGE_COOLDOWN_TICKS) return;

            if (screenHandler != null) {
                // Check for crafting screen or enchanting screen
                if (screenHandler instanceof CraftingScreenHandler) {
                    //sendMessageToPlayer("You’re getting stronger… I can feel it.");
                    AICompanionClient.callAI(client, "", "Player is opening a crafting table.");
                    sentMessage = true;
                } else if (screenHandler instanceof EnchantmentScreenHandler) {
                    //sendMessageToPlayer("You’re getting stronger… I can feel it.");
                    AICompanionClient.callAI(client, "", "Player is opening an enchantment table.");
                    sentMessage = true;
                }

                if (sentMessage) {
                    lastMessageTime.put(uuid, currentTime);
                }
            }
        });
    }

    private static void sendMessageToPlayer(String message) {
        ClientPlayerEntity player = client.player;
        if (player != null) {
            player.sendMessage(Text.of(message), false);
        }
    }
}

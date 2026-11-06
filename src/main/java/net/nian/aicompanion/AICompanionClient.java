package net.nian.aicompanion;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.nian.aicompanion.monitor.PlayerInteractionHandler;
import net.nian.aicompanion.monitor.PlayerStateMonitor;

import java.util.Objects;
import java.util.Random;

public class AICompanionClient implements ClientModInitializer {
    private int tickCounter = 0;
    private final Random random = new Random();
    private static final AICompanionHandler aiHandler = new AICompanionHandler();

    @Override
    public void onInitializeClient() {

        ConfigLoader.loadConfig();
        PlayerStateMonitor.register();
        PlayerInteractionHandler.registerEvent();


        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tickCounter++;

            // Trigger every 100-200 ticks (5 to 10 seconds)
            int interval = 400 + random.nextInt(500);
            if (tickCounter >= interval) {
                tickCounter = 0;
                //callAI(client, "");
            }
        });

        ClientSendMessageEvents.ALLOW_CHAT.register((message) -> {
            if (message.toLowerCase().contains("@" + ConfigLoader.config.Settings.AI_Companion_Name.toLowerCase())) {
                String prompt = message.replaceFirst("@"+ConfigLoader.config.Settings.AI_Companion_Name, "").trim();

                MinecraftClient.getInstance().execute(() -> {
                    MinecraftClient.getInstance().inGameHud.getChatHud()
                            .addMessage(Text.literal("§b<Me> §r" + message));
                });

                MinecraftClient client = MinecraftClient.getInstance();

                callAI(client, prompt, "");

                return false; // cancel sending to server
            }

            return true; // allow message to be sent to server
        });
    }

    public static void callAI(MinecraftClient client, String playerPrompt, String systemPrompt) {
        if (client.player == null || client.world == null) return;
        ClientPlayerEntity player = client.player;

        // Randomly choose which to display
        String[] messages = PlayerInfoHandler.GetPlayerAllInfo(client);

        String systemFullPrompt = String.join(" | ", messages);
        if(!Objects.equals(systemPrompt, "")){
            systemFullPrompt += "[Player current state]: " + systemPrompt;
        }

        aiHandler.runHeyAI(client.getName(), player.getName().getString(), playerPrompt,
                        ConfigLoader.config.Settings.AI_Companion_Name, systemFullPrompt)
                .thenAccept(result -> {
                    if (result.success) {
                        player.sendMessage(Text.literal("§b<"+ConfigLoader.config.Settings.AI_Companion_Name+"> §r" + result.response), false);
                    } else {
                        player.sendMessage(Text.literal("Error: " + result.error).formatted(Formatting.RED), false);
                    }
                });
    }
}

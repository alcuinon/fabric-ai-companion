package net.nian.aicompanion;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class AIHandler {

    public static void makeOpenAIRequest(String prompt) {
        String body = """
			{
			  "model": "%s",
			  "messages": [
				{ "role": "system", "content": "You are a helpful assistant who lives inside the game. Always be concise and friendly. Response only in a chat format. Keep responses under 80 tokens." },
				{ "role": "user", "content": "%s" }
			  ]
			}
			""".formatted(ConfigLoader.config.Settings.Open_AI_Model, prompt.replace("\"", "\\\""));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ConfigLoader.config.Settings.Open_AI_API_Url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ConfigLoader.config.Settings.Open_AI_API_Key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    String responseBody = response.body();
                    String reply = parseResponse(responseBody);
                    MinecraftClient.getInstance().execute(() -> {
                        if (reply != null) {
                            MinecraftClient.getInstance().inGameHud.getChatHud()
                                    .addMessage(Text.literal("§b<"+ConfigLoader.config.Settings.AI_Companion_Name+"> §r" + reply));
                        } else {
                            MinecraftClient.getInstance().inGameHud.getChatHud()
                                    .addMessage(Text.literal("§c<"+ConfigLoader.config.Settings.AI_Companion_Name+"> Failed to parse response."));
                        }
                    });
                })
                .exceptionally(e -> {
                    MinecraftClient.getInstance().execute(() -> {
                        MinecraftClient.getInstance().inGameHud.getChatHud()
                                .addMessage(Text.literal("§c<"+ConfigLoader.config.Settings.AI_Companion_Name+"> Error calling API."));
                    });
                    return null;
                });
    }

    private static String parseResponse(String json) {
        int contentIndex = json.indexOf("\"content\":");
        if (contentIndex == -1) return null;
        int start = json.indexOf('\"', contentIndex + 10) + 1;
        int end = json.indexOf('\"', start);
        if (start == 0 || end == -1) return null;
        return json.substring(start, end).replace("\\n", " ").trim();
    }
}

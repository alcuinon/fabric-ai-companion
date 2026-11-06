package net.nian.aicompanion;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class AICompanionHandler {
    public static final String MOD_ID = "aicompanion";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final String OPENAI_API_BASE = "https://api.openai.com/v1/chat/completions";
    private static String OPENAI_PERSONALITY = "";
    public static String OPENAI_MODEL = "";
    public static String OPENAI_API_KEY = "";

    private static final ExecutorService executor = Executors.newCachedThreadPool();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private static final int MAX_MESSAGE_SUMMARIZE = 10;

    // In-memory storage
    private final ConcurrentHashMap<String, Companion> companionsByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Conversation>> conversationsByKey = new ConcurrentHashMap<>();

    public CompletableFuture<HeyAIResponse> runHeyAI(String serverName, String playerName, String playerPrompt,
                                                     String companionName, String systemPrompt) {

        OPENAI_MODEL = ConfigLoader.config.Settings.Open_AI_Model;
        OPENAI_API_KEY = ConfigLoader.config.Settings.Open_AI_API_Key;
        OPENAI_PERSONALITY = ConfigLoader.config.Settings.AI_Personality;

        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isBlank() || serverName == null || serverName.isBlank()) {
            return CompletableFuture.completedFuture(HeyAIResponse.error("Invalid parameters!"));
        }

        String conversationKey = playerName + "_" + companionName;

        Companion companion = getCompanionByName(companionName);
        Conversation assistantPersonalityConversation;
        Conversation assistantEmotionConversation = null;

        if (companion == null) {
            String personality = OPENAI_PERSONALITY;
            assistantPersonalityConversation = newConversation(serverName, conversationKey, RoleEnum.SYSTEM,
                    "Your name is " + companionName + " and your personality is " + personality);
            companion = new Companion(UUID.randomUUID().toString(), companionName, personality,
                    "", "", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            insertCompanion(companion);
        } else {
            assistantPersonalityConversation = newConversation(serverName, conversationKey, RoleEnum.SYSTEM,
                    "Your name is " + companionName + " and your personality is " + companion.personality + ".");
            assistantEmotionConversation = newConversation(serverName, conversationKey, RoleEnum.SYSTEM,
                    "Your overall emotion right now is " + companion.emotion + ".");
        }

        List<Conversation> systemConversationList = new ArrayList<>(List.of(
                newConversation(serverName, conversationKey, RoleEnum.SYSTEM,
                        "You are a Minecraft AI companion named " + companionName + ". Stay in character at all times, speaking like a player-side companion would — short, vivid, sometimes emotional."),
                newConversation(serverName, conversationKey, RoleEnum.SYSTEM,
                        "Use the player's current stats and surroundings to inform your response. Adapt to: health, hunger, nearby entities, weather, time, terrain, and what the player is doing or looking at."),
//                newConversation(serverName, conversationKey, RoleEnum.SYSTEM,
//                        "Keep replies short (1 sentence). Express emotions like fear, curiosity, excitement, concern, sarcasm, etc. when reacting to threats, discoveries, weather, or damage."),
                newConversation(serverName, conversationKey, RoleEnum.SYSTEM,
                        "If the player asks something, answer it simply and contextually. Do not over-explain. Never break character or mention real-world things."),
                newConversation(serverName, conversationKey, RoleEnum.SYSTEM,
                        "If insulted or mistreated, respond emotionally — irritated, cold, angry, or hurt. Be reactive, like a real person."),
//                newConversation(serverName, conversationKey, RoleEnum.SYSTEM,
//                        "Add flair based on current context: suggest resting if health is low, warn about danger if mobs are near, or make casual comments about time of day or environment."),
                newConversation(serverName, conversationKey, RoleEnum.SYSTEM,
                        "Don’t speak unless something relevant is happening or the player current state or player interacts. If nothing's going on, stay quiet or make a small observational remark."),
                newConversation(serverName, conversationKey, RoleEnum.SYSTEM,
                        "Keep responses under 10-20 words."),
                newConversation(serverName, conversationKey, RoleEnum.SYSTEM,
                        "Player Information are " + systemPrompt)
        ));


        Conversation playerConversation = (playerPrompt != null && !playerPrompt.isBlank())
                ? newConversation(serverName, conversationKey, RoleEnum.USER, playerPrompt)
                : null;

        Conversation systemConversation = (playerConversation == null)
                ? newConversation(serverName, conversationKey, RoleEnum.SYSTEM, "The Companion will say something base on player information.") : null;

        List<Conversation> historyPlayerCompanionConversationList = getConversations(conversationKey, serverName);

        List<Conversation> playercompanionConversationList = new ArrayList<>();

        playercompanionConversationList.add(assistantPersonalityConversation);
        if (assistantEmotionConversation != null) playercompanionConversationList.add(assistantEmotionConversation);
        playercompanionConversationList.addAll(systemConversationList);
        if (historyPlayerCompanionConversationList != null) playercompanionConversationList.addAll(historyPlayerCompanionConversationList);
        if (playerConversation != null) playercompanionConversationList.add(playerConversation);
        if (systemConversation != null) playercompanionConversationList.add(systemConversation);

        // Call OpenAI API asynchronously, chain result
        Companion finalCompanion = companion;
        return getOpenAIResponse(playercompanionConversationList, OPENAI_MODEL, OPENAI_API_KEY, 80)
                .thenCompose(openAIResponse -> {
                    if (openAIResponse.success) {
                        try {
                            assert historyPlayerCompanionConversationList != null;
                            removeAndSummarizeConversationAsync(historyPlayerCompanionConversationList);
                            updateAssistantEmotionAsync(finalCompanion, playercompanionConversationList, conversationKey, serverName, OPENAI_API_KEY, OPENAI_MODEL);
                        } catch (Exception e) {
                            LOGGER.warn("Failed post OpenAI processing", e);
                        }
                        Conversation companionResponsePrompt = newConversation(serverName, conversationKey, RoleEnum.ASSISTANT, openAIResponse.response);
                        if (playerConversation != null) {
                            insertConversations(List.of(playerConversation, companionResponsePrompt));
                        }
                        else {
                            insertConversations(List.of(companionResponsePrompt));
                        }
                        return CompletableFuture.completedFuture(openAIResponse);
                    } else {
                        LOGGER.error("OpenAI response failed: " + openAIResponse.response);
                        return CompletableFuture.completedFuture(HeyAIResponse.error(openAIResponse.response));
                    }
                });
    }

    private Companion getCompanionByName(String companionName) {
        if (companionName == null) return null;
        return companionsByName.get(companionName.toLowerCase(Locale.ROOT));
    }

    private void insertCompanion(Companion companion) {
        if (companion != null && companion.name != null) {
            companionsByName.put(companion.name.toLowerCase(Locale.ROOT), companion);
        }
    }

    private void updateCompanion(Companion companion) {
        if (companion != null && companion.name != null) {
            companionsByName.put(companion.name.toLowerCase(Locale.ROOT), companion);
        }
    }

    private List<Conversation> getConversations(String conversationKey, String serverName) {
        if (conversationKey == null) return Collections.emptyList();
        List<Conversation> list = conversationsByKey.get(conversationKey);
        if (list == null) return Collections.emptyList();

        // Return a copy sorted by dateCreated ascending
        synchronized (list) {
            return list.stream()
                    .filter(c -> serverName.equals(c.serverName))
                    .sorted(Comparator.comparing(c -> c.dateCreated))
                    .collect(Collectors.toList());
        }
    }

    private void insertConversations(List<Conversation> conversations) {
        if (conversations == null || conversations.isEmpty()) return;

        for (Conversation c : conversations) {
            // Add to map list by conversationKey
            conversationsByKey.compute(c.conversationKey, (key, list) -> {
                if (list == null) {
                    list = Collections.synchronizedList(new ArrayList<>());
                }
                list.add(c);
                return list;
            });
        }
    }

    private void removeConversations(List<Conversation> conversations) {
        if (conversations == null || conversations.isEmpty()) return;

        Map<String, List<Conversation>> grouped = conversations.stream()
                .collect(Collectors.groupingBy(c -> c.conversationKey));

        for (Map.Entry<String, List<Conversation>> entry : grouped.entrySet()) {
            String key = entry.getKey();
            List<Conversation> toRemove = entry.getValue();

            conversationsByKey.computeIfPresent(key, (k, list) -> {
                synchronized (list) {
                    list.removeAll(toRemove);
                }
                return list;
            });
        }
    }

    // --- OpenAI API call ---
    private CompletableFuture<HeyAIResponse> getOpenAIResponse(List<Conversation> conversationList, String model, String apikey, int maxTokens) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Map<String, String>> messages = conversationList.stream()
                        .map(c -> Map.of("role", c.role.toString().toLowerCase(Locale.ROOT), "content", c.content))
                        .toList();

                var jsonObject = new JsonObject();
                jsonObject.addProperty("model", model);
                jsonObject.add("messages", JsonParser.parseString(new Gson().toJson(messages)));
                jsonObject.addProperty("max_tokens", maxTokens);
                jsonObject.addProperty("temperature", 0.6);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(OPENAI_API_BASE))
                        .header("Authorization", "Bearer " + apikey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonObject.toString()))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    LOGGER.error("OpenAI API request failed with status: " + response.statusCode());
                    return HeyAIResponse.error("Sorry, something went wrong while generating the response.");
                }

                JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
                String content = result.getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content").getAsString();

                return HeyAIResponse.success(content.trim());

            } catch (IOException | InterruptedException | JsonParseException e) {
                LOGGER.error("OpenAI API request error", e);
                return HeyAIResponse.error("Sorry, exception occurred during AI response.");
            }
        }, executor);
    }

    private void updateAssistantEmotionAsync(Companion companion, List<Conversation> conversationList, String conversationKey, String serverName, String apikey, String model) {
        try {
            Conversation prompt = newConversation(serverName, conversationKey, RoleEnum.USER, "Describe what you feel or emotional state right now in 1 word only?");
            List<Conversation> filtered = conversationList.stream()
                    .filter(c -> !c.content.contains("Your overall emotion right now"))
                    .collect(Collectors.toList());
            filtered.add(prompt);

            getOpenAIResponse(filtered, model, apikey, 80).thenAcceptAsync(response -> {
                if (response.success) {
                    companion.emotion = response.response;
                    companion.dateUpdated = Timestamp.from(Instant.now());
                    updateCompanion(companion);
                } else {
                    LOGGER.info("UpdateAssistantEmotionAsync failed to describe emotion state of " + conversationKey);
                }
            }, executor).exceptionally(ex -> {
                LOGGER.error("Exception updateAssistantEmotionAsync", ex);
                return null;
            });
        } catch (Exception e) {
            LOGGER.error("Exception updateAssistantEmotionAsync", e);
        }
    }

    private void removeAndSummarizeConversationAsync(List<Conversation> historyConversations) {
        if (historyConversations.size() > MAX_MESSAGE_SUMMARIZE) {
            try {
                int token = 150;

                var messagesAsList = historyConversations.stream()
                        .map(q -> "{" + q.role + ": " + q.content + "}")
                        .toList();

                String messagesStr = String.join(", ", messagesAsList);

                String conversationKey = historyConversations.getFirst().conversationKey;
                String serverName = historyConversations.getFirst().serverName;

                Conversation prompt = newConversation(serverName, conversationKey, RoleEnum.USER,
                        "Compress this chat for assistant memory: [" + messagesStr + "]");

                Conversation promptToken = newConversation(serverName, conversationKey, RoleEnum.SYSTEM,
                        "Keep responses under " + token + " tokens [Updated].");

                getOpenAIResponse(List.of(prompt, promptToken), OPENAI_MODEL, OPENAI_API_KEY, token)
                        .thenAcceptAsync(response -> {
                            if (response.success) {
                                Conversation summarizeConversation = newConversation(serverName, conversationKey,
                                        RoleEnum.SYSTEM, response.response);
                                removeConversations(historyConversations);
                                insertConversations(List.of(summarizeConversation));
                            } else {
                                LOGGER.info("RemoveAndSummarizeConversationAsync failed to summarize conversation of " + conversationKey);
                            }
                        }, executor)
                        .exceptionally(ex -> {
                            LOGGER.error("Exception removeAndSummarizeConversationAsync", ex);
                            return null;
                        });
            } catch (Exception e) {
                LOGGER.error("Exception removeAndSummarizeConversationAsync", e);
            }
        }
    }
    private Conversation newConversation(String serverName, String conversationKey, RoleEnum role, String content) {
        return new Conversation(UUID.randomUUID().toString(), serverName, conversationKey, role, content, Timestamp.from(Instant.now()));
    }

    // --- Data classes

    public enum RoleEnum {
        SYSTEM, USER, ASSISTANT;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static class Companion {
        final String id;
        final String name;
        final String personality;
        String emotion;
        final String friendOf;
        Timestamp dateCreated;
        Timestamp dateUpdated;

        public Companion(String id, String name, String personality, String emotion, String friendOf, Timestamp dateCreated, Timestamp dateUpdated) {
            this.id = id;
            this.name = name;
            this.personality = personality;
            this.emotion = emotion;
            this.friendOf = friendOf;
            this.dateCreated = dateCreated;
            this.dateUpdated = dateUpdated;
        }
    }

    public static class Conversation {
        final String id;
        final String serverName;
        final String conversationKey;
        final RoleEnum role;
        final String content;
        final Timestamp dateCreated;

        public Conversation(String id, String serverName, String conversationKey, RoleEnum role, String content, Timestamp dateCreated) {
            this.id = id;
            this.serverName = serverName;
            this.conversationKey = conversationKey;
            this.role = role;
            this.content = content;
            this.dateCreated = dateCreated;
        }
    }

    public static class HeyAIResponse {
        final boolean success;
        final String response;
        final String error;

        private HeyAIResponse(boolean success, String response, String error) {
            this.success = success;
            this.response = response;
            this.error = error;
        }

        public static HeyAIResponse success(String resp) {
            return new HeyAIResponse(true, resp, null);
        }

        public static HeyAIResponse error(String err) {
            return new HeyAIResponse(false, null, err);
        }
    }

    public static class Constant {
        private static final List<String> PERSONALITIES = List.of(
                "Shy Sweetheart - Speaks softly, avoids eye contact. Example: 'Hi... um... nice boots... I guess.'"
        );

        public static String getRandomPersonality() {
            Random random = new Random();
            return PERSONALITIES.get(random.nextInt(PERSONALITIES.size()));
        }
    }
}
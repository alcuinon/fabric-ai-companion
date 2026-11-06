package net.nian.aicompanion;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * One OpenAI conversation per key. Later turns send only the new line.
 * OpenAI keeps the history, so two players talking to one villager share one memory.
 */
public final class OpenAIConversations {
	private static final Gson GSON = new Gson();
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.build();
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "aicompanion-openai");
		thread.setDaemon(true);
		return thread;
	});

	private final ConcurrentHashMap<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

	public void ask(MinecraftServer server, String key, String instructions, String userText, Consumer<String> deliver) {
		if (userText == null || userText.isBlank()) {
			return;
		}
		CompletableFuture<Void> done = new CompletableFuture<>();
		done.whenComplete((value, error) -> tails.remove(key, done));
		tails.compute(key, (ignored, previous) -> {
			CompletableFuture<Void> waitFor = previous == null ? CompletableFuture.completedFuture(null) : previous;
			waitFor.whenCompleteAsync((value, error) -> {
				int world = ConversationStore.generation();
				if (!ConversationStore.isCurrent(world)) {
					done.complete(null);
					return;
				}
				String reply = null;
				try {
					reply = exchange(world, key, instructions, userText.trim());
				} catch (Exception exception) {
					AICompanion.LOGGER.warn("OpenAI request failed for {}", key, exception);
				}
				if (!ConversationStore.isCurrent(world)) {
					done.complete(null);
					return;
				}
				String spoken = reply;
				try {
					server.execute(() -> deliver.accept(spoken));
				} catch (Exception exception) {
					AICompanion.LOGGER.debug("Dropped an AI reply because the server was stopping", exception);
				} finally {
					done.complete(null);
				}
			}, EXECUTOR);
			return done;
		});
	}

	private String exchange(int world, String key, String instructions, String userText) throws IOException, InterruptedException {
		String conversationId = ConversationStore.get(key);
		if (conversationId == null) {
			conversationId = createConversation(instructions);
			ConversationStore.put(world, key, conversationId);
			AICompanion.LOGGER.info("Opened an OpenAI conversation for {}", key);
		}
		try {
			return respond(conversationId, instructions, userText);
		} catch (MissingConversation missing) {
			String freshId = createConversation(instructions);
			ConversationStore.put(world, key, freshId);
			AICompanion.LOGGER.info("Reopened the OpenAI conversation for {}", key);
			return respond(freshId, instructions, userText);
		}
	}

	private String createConversation(String instructions) throws IOException, InterruptedException {
		JsonObject systemText = new JsonObject();
		systemText.addProperty("type", "input_text");
		systemText.addProperty("text", instructions);

		JsonArray content = new JsonArray();
		content.add(systemText);

		JsonObject item = new JsonObject();
		item.addProperty("type", "message");
		item.addProperty("role", "system");
		item.add("content", content);

		JsonArray items = new JsonArray();
		items.add(item);

		JsonObject body = new JsonObject();
		body.add("items", items);

		JsonObject created = JsonParser.parseString(post(ConfigLoader.apiBase() + "/conversations", body)).getAsJsonObject();
		if (!created.has("id")) {
			throw new IOException("OpenAI did not return a conversation id");
		}
		return created.get("id").getAsString();
	}

	private String respond(String conversationId, String instructions, String userText) throws IOException, InterruptedException, MissingConversation {
		JsonObject body = new JsonObject();
		body.addProperty("model", ConfigLoader.model());
		body.addProperty("conversation", conversationId);
		if (instructions != null && !instructions.isBlank()) {
			body.addProperty("instructions", instructions);
		}
		body.addProperty("input", userText);
		body.addProperty("max_output_tokens", 80);

		String raw = post(ConfigLoader.apiBase() + "/responses", body);
		String reply = readReply(raw).replaceAll("\\s+", " ").trim();
		if (reply.isEmpty()) {
			throw new IOException("OpenAI returned an empty reply");
		}
		if (reply.length() > 240) {
			reply = reply.substring(0, 240).trim();
		}
		return reply;
	}

	private String post(String url, JsonObject payload) throws IOException, InterruptedException, MissingConversation {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(REQUEST_TIMEOUT)
				.header("Authorization", "Bearer " + ConfigLoader.apiKey())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
				.build();
		HttpResponse<String> response;
		try {
			response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw interrupted;
		}
		int status = response.statusCode();
		if (status == 404) {
			throw new MissingConversation();
		}
		if (status < 200 || status >= 300) {
			throw new IOException("OpenAI HTTP " + status + ": " + errorMessage(response.body()));
		}
		return response.body();
	}

	private static String readReply(String body) {
		JsonObject root = JsonParser.parseString(body).getAsJsonObject();
		if (!root.has("output") || !root.get("output").isJsonArray()) {
			return "";
		}
		StringBuilder text = new StringBuilder();
		for (JsonElement element : root.getAsJsonArray("output")) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject item = element.getAsJsonObject();
			if (item.has("type") && !"message".equals(item.get("type").getAsString())) {
				continue;
			}
			if (!item.has("content") || !item.get("content").isJsonArray()) {
				continue;
			}
			for (JsonElement partElement : item.getAsJsonArray("content")) {
				if (!partElement.isJsonObject()) {
					continue;
				}
				JsonObject part = partElement.getAsJsonObject();
				if (part.has("type") && !"output_text".equals(part.get("type").getAsString())) {
					continue;
				}
				if (part.has("text") && part.get("text").isJsonPrimitive()) {
					if (!text.isEmpty()) {
						text.append(' ');
					}
					text.append(part.get("text").getAsString());
				}
			}
		}
		return text.toString();
	}

	private static String errorMessage(String body) {
		try {
			JsonObject root = JsonParser.parseString(body).getAsJsonObject();
			if (root.has("error") && root.get("error").isJsonObject()) {
				JsonObject error = root.getAsJsonObject("error");
				if (error.has("message")) {
					return error.get("message").getAsString();
				}
			}
		} catch (Exception ignored) {
			// The body was not JSON. Fall through to a short raw snippet.
		}
		return body.length() > 240 ? body.substring(0, 240) : body;
	}

	private static final class MissingConversation extends IOException {
	}
}

package net.nian.aicompanion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Saves each villager's OpenAI conversation id with the world.
 * The messages themselves stay on OpenAI. This file only remembers which id belongs to whom.
 */
public final class ConversationStore {
	public static final String COMPANION_KEY = "companion";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Object LOCK = new Object();
	private static final Map<String, String> IDS = new HashMap<>();

	private static Path file;
	private static int generation;

	private ConversationStore() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(ConversationStore::load);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
	}

	public static int generation() {
		synchronized (LOCK) {
			return generation;
		}
	}

	public static boolean isCurrent(int worldGeneration) {
		synchronized (LOCK) {
			return worldGeneration > 0 && worldGeneration == generation;
		}
	}

	public static String get(String key) {
		synchronized (LOCK) {
			return IDS.get(key);
		}
	}

	public static void put(int worldGeneration, String key, String conversationId) {
		synchronized (LOCK) {
			if (worldGeneration != generation || file == null) {
				return;
			}
			IDS.put(key, conversationId);
			try {
				Files.createDirectories(file.getParent());
				JsonObject saved = new JsonObject();
				IDS.forEach(saved::addProperty);
				Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
				Files.writeString(temporary, GSON.toJson(saved));
				try {
					Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (IOException ignored) {
					Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException e) {
				AICompanion.LOGGER.error("Could not save villager conversation ids", e);
			}
		}
	}

	private static void load(MinecraftServer server) {
		synchronized (LOCK) {
			generation++;
			file = server.getWorldPath(LevelResource.ROOT).resolve("aicompanion").resolve("conversations.json");
			IDS.clear();
			if (!Files.exists(file)) {
				AICompanion.LOGGER.info("No saved villager conversations yet at {}", file);
				return;
			}
			try (Reader reader = Files.newBufferedReader(file)) {
				JsonObject saved = JsonParser.parseReader(reader).getAsJsonObject();
				for (String key : saved.keySet()) {
					if (saved.get(key).isJsonPrimitive()) {
						IDS.put(key, saved.get(key).getAsString());
					}
				}
				AICompanion.LOGGER.info("Loaded {} OpenAI conversation ids", IDS.size());
			} catch (Exception e) {
				AICompanion.LOGGER.error("Could not read {}", file, e);
			}
		}
	}

	private static void clear() {
		synchronized (LOCK) {
			generation++;
			IDS.clear();
			file = null;
		}
	}
}

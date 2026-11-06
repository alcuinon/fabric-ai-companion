package net.nian.aicompanion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class ConfigLoader {
	private static final Path CONFIG_PATH = FabricLoader.getInstance()
			.getConfigDir()
			.resolve("aicompanion_config.json");
	private static final Gson GSON = new Gson();

	public static Config config = new Config();

	private ConfigLoader() {
	}

	public static void load() {
		try {
			if (!Files.exists(CONFIG_PATH)) {
				Files.createDirectories(CONFIG_PATH.getParent());
				try (InputStream input = ConfigLoader.class.getClassLoader()
						.getResourceAsStream("assets/aicompanion/config/aicompanion_config.json")) {
					if (input != null) {
						Files.copy(input, CONFIG_PATH);
					} else {
						AICompanion.LOGGER.error("Default AI Companion config is missing from the mod jar.");
					}
				}
			}

			try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
				Config loaded = GSON.fromJson(reader, Config.class);
				if (loaded != null) {
					if (loaded.Settings == null) {
						loaded.Settings = new Config.Settings();
					}
					config = loaded;
				}
			}
			if (config.Settings.Bubble_Position == null || config.Settings.Bubble_Position.isBlank()) {
				config.Settings.Bubble_Position = "head";
				Files.writeString(CONFIG_PATH, new GsonBuilder().setPrettyPrinting().create().toJson(config));
			}
		} catch (IOException e) {
			AICompanion.LOGGER.error("Could not load config/aicompanion_config.json", e);
		}
	}

	public static String apiKey() {
		String key = config.Settings.Open_AI_API_Key;
		return key == null ? "" : key.trim();
	}

	public static String model() {
		String model = config.Settings.Open_AI_Model;
		return model == null || model.isBlank() ? "gpt-4o-mini" : model.trim();
	}

	public static String companionName() {
		String name = config.Settings.AI_Companion_Name;
		return name == null || name.isBlank() ? "ai" : name.trim();
	}

	public static String personality() {
		String personality = config.Settings.AI_Personality;
		return personality == null || personality.isBlank()
				? "A friendly Minecraft companion."
				: personality.trim();
	}

	public static boolean ambientRemarks() {
		return Boolean.TRUE.equals(config.Settings.Ambient_Remarks);
	}

	public static String bubblePosition() {
		String value = config.Settings.Bubble_Position;
		if (value == null || value.isBlank()) {
			return "head";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}

	public static String apiBase() {
		String configured = config.Settings.Open_AI_API_Url;
		String base = configured == null ? "" : configured.trim();
		if (base.isEmpty()) {
			return "https://api.openai.com/v1";
		}
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		if (base.endsWith("/chat/completions")) {
			base = base.substring(0, base.length() - "/chat/completions".length());
		}
		if (base.endsWith("/responses")) {
			base = base.substring(0, base.length() - "/responses".length());
		}
		if (base.endsWith("/conversations")) {
			base = base.substring(0, base.length() - "/conversations".length());
		}
		return base;
	}
}

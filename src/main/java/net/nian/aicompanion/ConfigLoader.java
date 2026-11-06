package net.nian.aicompanion;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigLoader {

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("aicompanion_config.json");

    public static Config config;

    public static void loadConfig() {
        try {
            // Check if the config file exists
            if (!Files.exists(CONFIG_PATH)) {
                // Copy the default config from assets if it does not exist
                Path defaultConfigPath = FabricLoader.getInstance().getGameDirectory().toPath()
                        .resolve("assets/aicompanion/config/aicompanion_config.json");

                // Create the parent directories if they do not exist
                Files.createDirectories(CONFIG_PATH.getParent());

                // Open the input stream from the resources
                try (InputStream inputStream = Config.class.getClassLoader().getResourceAsStream("assets/aicompanion/config/aicompanion_config.json")) {
                    if (inputStream != null) {
                        // Write the content to the config directory
                        try (OutputStream outputStream = Files.newOutputStream(CONFIG_PATH)) {
                            byte[] buffer = new byte[1024];
                            int bytesRead;
                            while ((bytesRead = inputStream.read(buffer)) != -1) {
                                outputStream.write(buffer, 0, bytesRead);
                            }
                        }
                    } else {
                        System.err.println("Default config file not found in resources.");
                    }
                }
            }

            // Load the config file into the TierDropConfig object
            Reader reader = Files.newBufferedReader(CONFIG_PATH);
            config = new Gson().fromJson(reader, Config.class);

            if (config == null) {
                System.err.println("Failed to load config.");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}

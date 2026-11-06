package net.nian.aicompanion;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AICompanion implements ModInitializer {
	public static final String MOD_ID = "aicompanion";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ConfigLoader.load();
		OpenAIConversations conversations = new OpenAIConversations();
		ConversationStore.register();
		VillagerTalk.register(conversations);
		CompanionRemarks.register(conversations);
		LOGGER.info("AI Companion ready for Minecraft 26.1.1");
	}
}

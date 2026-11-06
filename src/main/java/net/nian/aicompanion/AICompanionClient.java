package net.nian.aicompanion;

import net.fabricmc.api.ClientModInitializer;

public class AICompanionClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Talking is handled on the server so every player shares one villager memory.
	}
}

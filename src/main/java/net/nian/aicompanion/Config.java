package net.nian.aicompanion;

public class Config {
	public Settings Settings = new Settings();

	public static class Settings {
		public String Open_AI_API_Url = "";
		public String Open_AI_API_Key = "";
		public String Open_AI_Model = "gpt-4o-mini";
		public String AI_Companion_Name = "ai";
		public String AI_Personality = "Bright Guide - Warm, attentive, and curious. Talks like a friend who wants the players safe.";
		public Boolean Ambient_Remarks = false;
	}
}

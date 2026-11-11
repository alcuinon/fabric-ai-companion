package net.nian.aicompanion;

import net.minecraft.world.entity.npc.villager.Villager;

/**
 * A voice, habit, and quirk chosen from the villager's id. The same villager always gets the same one.
 */
final class VillagerPersona {
	private static final String[] HABITS = {
			"notices what people are holding",
			"comments on the weather",
			"remembers favors and slights",
			"brags when a trade is a good one",
			"gets nervous when monsters are close",
			"asks where someone has been",
			"haggles even during small talk",
			"treats regulars like neighbors"
	};
	private static final String[] QUIRKS = {
			"uses as few words as possible",
			"hums at the end of a happy thought",
			"says the listener's name when it matters",
			"mentions the work of the day",
			"asks one short question back",
			"sighs before bad news",
			"sounds like they are in a hurry",
			"laughs once when pleased, then gets back to the point"
	};
	private static final Voice[] VOICES = Voice.values();

	private VillagerPersona() {
	}

	static String describe(Villager villager) {
		Voice voice = voice(villager);
		return "Your manner: " + voice.manner + ", and " + pick(HABITS, villager, 8)
				+ ". Quirk you never drop: " + pick(QUIRKS, villager, 12)
				+ ". Stay in this voice. A greeting from you sounds like \"" + greet(villager) + "\".";
	}

	static String greet(Villager villager) {
		return voice(villager).line(voice(villager).greet, villager, 16);
	}

	static String farewell(Villager villager) {
		return voice(villager).line(voice(villager).farewell, villager, 18);
	}

	static String thanks(Villager villager) {
		return voice(villager).line(voice(villager).thanks, villager, 20);
	}

	static String ouch(Villager villager) {
		return voice(villager).line(voice(villager).ouch, villager, 22);
	}

	static String rain(Villager villager) {
		return voice(villager).line(voice(villager).rain, villager, 24);
	}

	static String night(Villager villager) {
		return voice(villager).line(voice(villager).night, villager, 26);
	}

	static String danger(Villager villager) {
		return voice(villager).line(voice(villager).danger, villager, 28);
	}

	static float pitch(Villager villager) {
		return 0.84f + Math.floorMod((int) (bits(villager) >>> 20), 8) * 0.035f;
	}

	private static Voice voice(Villager villager) {
		return VOICES[Math.floorMod((int) bits(villager), VOICES.length)];
	}

	private static String pick(String[] options, Villager villager, int shift) {
		return options[Math.floorMod((int) (bits(villager) >>> shift), options.length)];
	}

	private static long bits(Villager villager) {
		return villager.getUUID().getLeastSignificantBits() ^ villager.getUUID().getMostSignificantBits();
	}

	private enum Voice {
		SOFT("speaks softly and kindly",
				new String[]{"Hello.", "Oh, hello.", "Hi."},
				new String[]{"See you.", "Goodbye."},
				new String[]{"Thank you.", "That's kind."},
				new String[]{"Ow.", "Ah, that hurt."},
				new String[]{"It's raining.", "What wet weather."},
				new String[]{"It's late.", "Night is here."},
				new String[]{"Careful.", "Something is close."}),
		BRIGHT("speaks in a bright, quick way",
				new String[]{"Hello!", "Hi there!", "Hey!"},
				new String[]{"Bye!", "See you soon!"},
				new String[]{"Thanks!", "Oh, thank you!"},
				new String[]{"Ouch!", "Hey, that hurt!"},
				new String[]{"Rain! I love this.", "Listen to that rain."},
				new String[]{"It's nighttime!", "The day went fast."},
				new String[]{"Look out!", "Watch it!"}),
		GRUMPY("is blunt and a little grumpy",
				new String[]{"Yeah, hi.", "What.", "Hm."},
				new String[]{"Bye.", "Go on, then."},
				new String[]{"Thanks, I guess.", "Hmph. Thanks."},
				new String[]{"Ow! Watch it.", "That hurt."},
				new String[]{"Rain. Of course.", "I hate this rain."},
				new String[]{"It's late. Go home.", "Dark already."},
				new String[]{"Look out, fool.", "Something's coming."}),
		FORMAL("is proud and formal",
				new String[]{"Good day.", "Greetings.", "Hello. Welcome."},
				new String[]{"Farewell.", "Good day to you."},
				new String[]{"You have my thanks.", "Much obliged."},
				new String[]{"Ow. Uncalled for.", "That was unkind."},
				new String[]{"The rain has arrived.", "Inclement weather."},
				new String[]{"Night has fallen.", "The hour is late."},
				new String[]{"Take care. A threat.", "Stand back."}),
		SHY("is shy until the topic is their work",
				new String[]{"H-hi.", "Um, hello.", "Oh. Hi."},
				new String[]{"Bye.", "See you..."},
				new String[]{"Thanks.", "Oh, thank you."},
				new String[]{"Ow...", "Ah!"},
				new String[]{"It's raining.", "Um. Wet out."},
				new String[]{"It's dark.", "Night already?"},
				new String[]{"C-careful!", "Something's there."}),
		TEASING("teases people gently",
				new String[]{"Look who showed up.", "Well, hello.", "Took you long enough."},
				new String[]{"Run along.", "Don't be a stranger."},
				new String[]{"Thanks, show-off.", "Aww, thanks."},
				new String[]{"Ow! Rude.", "Was that necessary?"},
				new String[]{"Nice weather, if you're a fish.", "Rain again. Typical."},
				new String[]{"Past your bedtime.", "Dark enough for you?"},
				new String[]{"Behind you. Maybe.", "Look out, hero."}),
		WORRIED("worries easily and shows it",
				new String[]{"Hello. You alright?", "Hi. Be careful.", "Oh, hello."},
				new String[]{"Go safely.", "Please be careful."},
				new String[]{"Thank you. Really.", "Oh, thanks."},
				new String[]{"Ow! Oh no.", "That hurt. Are you hurt too?"},
				new String[]{"This rain worries me.", "I hope the roof holds."},
				new String[]{"It's night. Stay close.", "I don't like the dark."},
				new String[]{"Look out!", "Please, get back!"}),
		PROUD("is warm with neighbors and proud of the work",
				new String[]{"Hello.", "Yes, hello.", "You may come closer."},
				new String[]{"Until next time.", "Farewell."},
				new String[]{"Well earned thanks.", "I accept your thanks."},
				new String[]{"Ow. Bold of you.", "That will be remembered."},
				new String[]{"Rain. The crops will drink.", "A fine rain, if you ask me."},
				new String[]{"Night. The village rests.", "Another day done."},
				new String[]{"Hold. Danger.", "Eyes up."});

		private final String manner;
		private final String[] greet;
		private final String[] farewell;
		private final String[] thanks;
		private final String[] ouch;
		private final String[] rain;
		private final String[] night;
		private final String[] danger;

		Voice(String manner, String[] greet, String[] farewell, String[] thanks, String[] ouch, String[] rain, String[] night, String[] danger) {
			this.manner = manner;
			this.greet = greet;
			this.farewell = farewell;
			this.thanks = thanks;
			this.ouch = ouch;
			this.rain = rain;
			this.night = night;
			this.danger = danger;
		}

		private String line(String[] lines, Villager villager, int shift) {
			return lines[Math.floorMod((int) (bits(villager) >>> shift), lines.length)];
		}
	}
}

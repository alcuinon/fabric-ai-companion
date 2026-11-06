package net.nian.aicompanion;

import com.mojang.math.Transformation;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A text bubble on the villager. It stays upright, faces the player, and follows them.
 */
public final class VillagerBubble {
	private static final String TAG = "aicompanion_bubble";
	private static final String UNTIL_PREFIX = "until:";
	private static final int MIN_TICKS = 20 * 5;
	private static final int MAX_TICKS = 20 * 12;
	private static final Map<UUID, LiveBubble> ACTIVE = new ConcurrentHashMap<>();

	private static int tick;

	private VillagerBubble() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tick++;
			for (ServerLevel level : server.getAllLevels()) {
				follow(level);
				if (tick % 100 == 0) {
					sweep(level);
				}
			}
		});
	}

	public static void show(Villager villager, String text) {
		if (!(villager.level() instanceof ServerLevel level) || text == null || text.isBlank()) {
			return;
		}
		long expire = level.getGameTime() + duration(text);
		LiveBubble current = ACTIVE.get(villager.getUUID());
		if (current != null && level.getEntity(current.displayId) instanceof Display.TextDisplay existing) {
			write(existing, text, expire);
			style(existing);
			place(level, existing, villager);
			current.expire = expire;
			speak(level, villager);
			return;
		}

		Display.TextDisplay display = new Display.TextDisplay(EntityType.TEXT_DISPLAY, level);
		write(display, text, expire);
		style(display);
		display.setNoGravity(true);
		display.addTag(TAG);
		place(level, display, villager);
		if (!level.addFreshEntity(display)) {
			return;
		}
		place(level, display, villager);
		speak(level, villager);
		ACTIVE.put(villager.getUUID(), new LiveBubble(display.getUUID(), villager.getUUID(), level.dimension(), expire));
	}

	public static boolean isActive(UUID villagerId) {
		return ACTIVE.containsKey(villagerId);
	}

	private static void follow(ServerLevel level) {
		long now = level.getGameTime();
		Iterator<LiveBubble> bubbles = ACTIVE.values().iterator();
		while (bubbles.hasNext()) {
			LiveBubble bubble = bubbles.next();
			if (!bubble.dimension.equals(level.dimension())) {
				continue;
			}
			Entity displayEntity = level.getEntity(bubble.displayId);
			if (now >= bubble.expire) {
				if (displayEntity != null) {
					displayEntity.discard();
				}
				bubbles.remove();
				continue;
			}
			if (!(displayEntity instanceof Display.TextDisplay display)) {
				continue;
			}
			if (!(level.getEntity(bubble.villagerId) instanceof Villager villager) || !villager.isAlive()) {
				display.discard();
				bubbles.remove();
				continue;
			}
			place(level, display, villager);
		}
	}

	private static void sweep(ServerLevel level) {
		long now = level.getGameTime();
		for (ServerPlayer player : level.players()) {
			AABB box = player.getBoundingBox().inflate(48);
			for (Entity entity : level.getEntities(player, box, entity -> entity instanceof Display.TextDisplay && entity.entityTags().contains(TAG))) {
				if (expired(entity, now)) {
					entity.discard();
				}
			}
		}
	}

	private static void style(Display.TextDisplay display) {
		display.setLineWidth(160);
		display.setBackgroundColor(0xC0000000);
		display.setTextOpacity((byte) -1);
		display.setFlags((byte) 0);
		display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
		display.setTransformation(new Transformation(
				new Vector3f(0f, 0f, 0f),
				new Quaternionf(),
				new Vector3f(0.5f, 0.5f, 0.5f),
				new Quaternionf()));
		display.setShadowRadius(0f);
		display.setShadowStrength(0f);
		display.setViewRange(4f);
		display.setPosRotInterpolationDuration(0);
	}

	private static void place(ServerLevel level, Display.TextDisplay display, Villager villager) {
		display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
		display.setPosRotInterpolationDuration(0);
		Vec3 anchor = anchor(villager);
		ServerPlayer reader = nearest(level, villager);
		float yaw = display.getYRot();
		float pitch = display.getXRot();
		if (reader != null) {
			double dx = reader.getX() - anchor.x;
			double dy = reader.getEyeY() - anchor.y;
			double dz = reader.getZ() - anchor.z;
			double flat = Math.sqrt(dx * dx + dz * dz);
			yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(flat, 0.001)));
		}
		display.setPos(anchor.x, anchor.y, anchor.z);
		display.setYRot(yaw);
		display.setXRot(pitch);
	}

	private static ServerPlayer nearest(ServerLevel level, Villager villager) {
		ServerPlayer closest = null;
		double best = 48.0 * 48.0;
		for (ServerPlayer player : level.players()) {
			if (player.isSpectator()) {
				continue;
			}
			double distance = player.distanceToSqr(villager);
			if (distance < best) {
				best = distance;
				closest = player;
			}
		}
		return closest;
	}

	private static void write(Display.TextDisplay display, String text, long expire) {
		display.setText(Component.literal(pad(text)).withStyle(ChatFormatting.WHITE));
		for (String tag : java.util.List.copyOf(display.entityTags())) {
			if (tag.startsWith(UNTIL_PREFIX)) {
				display.removeTag(tag);
			}
		}
		display.addTag(UNTIL_PREFIX + expire);
	}

	private static boolean expired(Entity entity, long now) {
		for (String tag : entity.entityTags()) {
			if (tag.startsWith(UNTIL_PREFIX)) {
				try {
					return now >= Long.parseLong(tag.substring(UNTIL_PREFIX.length()));
				} catch (NumberFormatException ignored) {
					return true;
				}
			}
		}
		return true;
	}

	private static Vec3 anchor(Villager villager) {
		double x = villager.getX();
		double y = villager.getY();
		double z = villager.getZ();
		double height = Math.max(1.0, villager.getBbHeight());
		double rad = Math.toRadians(villager.getYRot());
		double forwardX = -Math.sin(rad);
		double forwardZ = Math.cos(rad);
		double sideX = -forwardZ;
		double sideZ = forwardX;
		double chest = y + height * 0.58;
		return switch (ConfigLoader.bubblePosition()) {
			case "front", "body" -> new Vec3(x + forwardX * 0.4, chest, z + forwardZ * 0.4);
			case "left" -> new Vec3(x + sideX * 0.55, chest, z + sideZ * 0.55);
			case "right" -> new Vec3(x - sideX * 0.55, chest, z - sideZ * 0.55);
			default -> new Vec3(x, y + height + 0.32, z);
		};
	}

	private static String pad(String text) {
		String[] words = text.trim().split("\\s+");
		StringBuilder line = new StringBuilder();
		StringBuilder block = new StringBuilder();
		for (String word : words) {
			if (word.isEmpty()) {
				continue;
			}
			if (!line.isEmpty() && line.length() + 1 + word.length() > 28) {
				block.append(' ').append(line).append(" \n");
				line.setLength(0);
			}
			if (!line.isEmpty()) {
				line.append(' ');
			}
			line.append(word);
		}
		if (!line.isEmpty()) {
			block.append(' ').append(line).append(' ');
		}
		return block.toString();
	}

	private static void speak(ServerLevel level, Villager villager) {
		int step = Math.floorMod(villager.getUUID().hashCode(), 5);
		float pitch = 0.9f + step * 0.035f;
		level.playSound(null, villager, SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 0.9f, pitch);
	}

	private static int duration(String text) {
		return Math.min(MAX_TICKS, MIN_TICKS + text.length() * 2);
	}

	private static final class LiveBubble {
		private final UUID displayId;
		private final UUID villagerId;
		private final ResourceKey<Level> dimension;
		private long expire;

		private LiveBubble(UUID displayId, UUID villagerId, ResourceKey<Level> dimension, long expire) {
			this.displayId = displayId;
			this.villagerId = villagerId;
			this.dimension = dimension;
			this.expire = expire;
		}
	}
}

package se.metacraft.ovvar.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;

/**
 * Our Danse fork runs on a public server under the AGPL, so every player must be able to find its
 * source — including players whose permissions keep them out of {@code /gesture} itself.
 */
public final class DanseForkTests {

	@GameTest
	public void anyPlayerCanAskWhereDansesSourceIs(GameTestHelper helper) {
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("danse")) { helper.succeed(); return; }
		var root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot();
		var danse = root.getChild("danse");
		if (danse == null || danse.getChild("source") == null) { helper.fail("no /danse source command"); return; }
		CommandSourceStack player = helper.makeMockServerPlayerInLevel().createCommandSourceStack();
		if (!danse.canUse(player) || !danse.getChild("source").canUse(player)) {
			helper.fail("/danse source is not open to a plain player");
			return;
		}
		helper.succeed();
	}

	/**
	 * The end of a gesture puts the player back where the stand-in stood, and must do it as a real
	 * server teleport. Upstream Danse sent a hand-made position packet whose teleport id was the
	 * player's <em>entity</em> id: the server ignored the reply — unless that id happened to equal
	 * its own pending teleport id (the first player to join after a restart is entity 1, and the
	 * join teleport is id 1), in which case it read the reply as the real acknowledgement and 26.3
	 * kicked the player ("Invalid move player packet received"). A real teleport leaves the server
	 * waiting for its acknowledgement at the stand-in's position.
	 */
	@GameTest(maxTicks = 100)
	public void aGestureEndsWithARealTeleport(GameTestHelper helper) {
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("danse")) { helper.succeed(); return; }
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		de.tomalbrc.danse.util.MinecraftSkinParser.calculateAndCache(player.getUUID(), de.tomalbrc.danse.Danse.STEVE_SKIN);
		clearAwaiting(player.connection);
		de.tomalbrc.danse.GestureController.onStart(player, "wave");
		helper.runAfterDelay(10, () -> {
			if (!de.tomalbrc.danse.GestureController.GESTURE_CAMS.containsKey(player.getUUID())) {
				helper.fail("the gesture did not start");
				return;
			}
			de.tomalbrc.danse.GestureController.onStop(player);
			if (de.tomalbrc.danse.GestureController.GESTURE_CAMS.containsKey(player.getUUID())) {
				helper.fail("the gesture did not stop");
				return;
			}
			Vec3 awaiting = awaiting(player.connection);
			if (awaiting == null) {
				helper.fail("the gesture ended without a real teleport: the server is not waiting for the client to accept one");
				return;
			}
			helper.succeed();
		});
	}

	/**
	 * A {@code danse:player_model} (a statue that plays a gesture) shrugs off ordinary damage so it
	 * keeps dancing, but {@code /kill} and a creative player's hit must remove it. Upstream answered
	 * a creative hit with {@code kill()}, which in 26.3 is itself a {@code genericKill} hit, so it
	 * came straight back, was ignored, and nothing could ever remove the model.
	 */
	@GameTest(maxTicks = 40)
	public void aPlayerModelCanBeKilled(GameTestHelper helper) {
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("danse")) { helper.succeed(); return; }
		var level = helper.getLevel();
		// By id: the class implements bil's AnimatedEntity, a jar nested in Danse that we do not compile against
		var playerModel = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.parse("danse:player_model"));
		var survivor = helper.spawn(playerModel, 1, 2, 1);
		survivor.hurtServer(level, level.damageSources().generic(), 5);
		if (survivor.isRemoved()) { helper.fail("ordinary damage removed the player model"); return; }

		survivor.kill(level);
		if (!survivor.isRemoved()) { helper.fail("/kill did not remove the player model"); return; }

		var punched = helper.spawn(playerModel, 2, 2, 1);
		ServerPlayer creative = helper.makeMockServerPlayerInLevel();
		creative.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
		punched.hurtServer(level, level.damageSources().playerAttack(creative), 1);
		if (!punched.isRemoved()) { helper.fail("a creative player's hit did not remove the player model"); return; }
		helper.succeed();
	}

	private static Field awaitingField() throws NoSuchFieldException {
		Field f = ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
		f.setAccessible(true);
		return f;
	}

	private static Vec3 awaiting(ServerGamePacketListenerImpl connection) {
		try {
			return (Vec3) awaitingField().get(connection);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** A fresh mock player may still be waiting on its join teleport; start from nothing pending. */
	private static void clearAwaiting(ServerGamePacketListenerImpl connection) {
		try {
			awaitingField().set(connection, null);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}
}

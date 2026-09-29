package com.merchantmind.client;

import com.merchantmind.MerchantMind;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;

/**
 * 2.1 (2.8.0) - third person while you are dead, back to normal once you move again.
 *
 * <h2>What it does</h2>
 *
 * On death the camera is switched to <b>third person, behind the player</b>, which is the view the
 * "Cinematic Respawn" mod's death sequence is built to be seen in - that mod lifts and rotates the
 * camera around the body, and in first person there is no body to look at. After the respawn the
 * view is left alone until the player actually <b>moves</b>, and the first movement puts it back to
 * whatever it was before they died.
 *
 * <h2>Only with Cinematic Respawn installed</h2>
 *
 * Verified from the mod's own {@code fabric.mod.json} inside
 * {@code cinematic_respawn-26.1.x-fabric-1.1.0.jar} (Modrinth project {@code cinematic-respawn},
 * author VoKaP): the declared mod id is <b>{@code cinematic_respawn}</b>, with an underscore - not
 * {@code cinematicrespawn}. That file also declares {@code "environment": "client"}, which is why
 * everything here is client-side and nothing is sent to the server.
 *
 * <p>{@link #register()} checks that id and, if the mod is absent, <b>registers no tick handler at
 * all</b>. A player without Cinematic Respawn does not get a disabled feature, they get no feature:
 * no listener, no per-tick work, and the camera is never touched.
 *
 * <h2>Rules it keeps</h2>
 *
 * <ul>
 *   <li><b>Back, never front.</b> {@link CameraType#THIRD_PERSON_BACK} only. THIRD_PERSON_FRONT
 *       would point the camera at the player's face, which is the opposite of what is wanted.</li>
 *   <li><b>Death to respawn only.</b> Outside that window this class sets nothing. The state machine
 *       starts on death and ends on the first movement after respawning.</li>
 *   <li><b>F5 always wins.</b> If the camera stops matching what this class set - the player pressed
 *       F5, or another mod changed it - the sequence is abandoned on the spot and the view is left
 *       exactly where the player put it. It is never forced back.</li>
 *   <li><b>Restores, does not assume.</b> The view in use before death is remembered and restored.
 *       A player who plays in third person is not silently moved to first person; for everyone else
 *       the remembered value <i>is</i> first person, so the observable result is the same.</li>
 * </ul>
 *
 * <h2>Why "movement" is read from the sanitised input</h2>
 *
 * Cinematic Respawn blocks movement input for the length of its sequence (its
 * {@code KeyboardInputMixin} clears {@link net.minecraft.client.player.ClientInput}). Reading
 * {@code player.input.keyPresses} therefore reports movement only once the cinematic has finished
 * and the player really is in control - which is exactly the moment being waited for. Reading the
 * raw key bindings instead would fire while the camera was still flying.
 */
public final class RespawnCameraDirector {
   /** Verified from the mod's fabric.mod.json - underscore, not concatenated. */
   private static final String CINEMATIC_RESPAWN_ID = "cinematic_respawn";

   /** Horizontal distance in one tick that counts as moving, for input this mod cannot see. */
   private static final double MOVE_EPSILON = 0.02;

   private enum Phase {
      /** Not our business: alive, or the sequence was handed back to the player. */
      IDLE,
      /** Dead, camera pushed to third person, waiting for the respawn. */
      DEAD,
      /** Alive again, camera still third person, waiting for the first movement. */
      WAITING_FOR_MOVE
   }

   private static Phase phase = Phase.IDLE;
   /** The camera the player had before dying - what gets restored. */
   private static CameraType savedView = CameraType.FIRST_PERSON;
   /** The camera this class set, so a change made by anything else can be spotted. */
   private static CameraType forcedView;
   private static double lastX;
   private static double lastZ;

   private RespawnCameraDirector() {
   }

   /**
    * Installs the feature, but only when Cinematic Respawn is present.
    *
    * @return true if the feature is active in this session
    */
   public static boolean register() {
      if (!FabricLoader.getInstance().isModLoaded(CINEMATIC_RESPAWN_ID)) {
         MerchantMind.LOGGER
            .info("[{}] Cinematic Respawn ('{}') not installed - respawn camera feature stays off", MerchantMind.MOD_ID, CINEMATIC_RESPAWN_ID);
         return false;
      }

      MerchantMind.LOGGER.info("[{}] Cinematic Respawn detected - death switches to third person until you move", MerchantMind.MOD_ID);
      ClientTickEvents.END_CLIENT_TICK.register(RespawnCameraDirector::tick);
      return true;
   }

   private static void tick(Minecraft client) {
      try {
         drive(client);
      } catch (Throwable error) {
         /*
          * A camera helper must never be able to break the client tick, and it must never leave the
          * player stuck in a view they did not choose - so a failure restores and stands down.
          */
         MerchantMind.LOGGER.error("[{}] respawn camera failed - restoring the player's own view", MerchantMind.MOD_ID, error);
         restore(client);
         phase = Phase.IDLE;
      }
   }

   private static void drive(Minecraft client) {
      if (client == null) {
         return;
      }

      LocalPlayer player = client.player;
      if (player == null || client.level == null) {
         // left the world mid-sequence: hand the view back rather than leaving it where we put it
         if (phase != Phase.IDLE) {
            restore(client);
            phase = Phase.IDLE;
         }
         return;
      }

      // the player took control of the camera themselves - stop interfering, permanently for this
      // death, and do NOT restore anything: what they chose is what they want
      if (phase != Phase.IDLE && forcedView != null && client.options.getCameraType() != forcedView) {
         phase = Phase.IDLE;
         forcedView = null;
         return;
      }

      boolean dead = player.isDeadOrDying() || player.getHealth() <= 0.0F;

      switch (phase) {
         case IDLE -> {
            if (dead) {
               // remember the real view exactly once, before it is overwritten
               savedView = client.options.getCameraType();
               force(client, CameraType.THIRD_PERSON_BACK);
               phase = Phase.DEAD;
            }
         }
         case DEAD -> {
            if (!dead) {
               // respawned - the respawn also replaces the LocalPlayer instance, so the position
               // baseline has to be taken from the new one
               lastX = player.getX();
               lastZ = player.getZ();
               phase = Phase.WAITING_FOR_MOVE;
            }
         }
         case WAITING_FOR_MOVE -> {
            if (dead) {
               // died again before moving; keep the view and the ORIGINAL savedView, do not
               // overwrite it with the third person view we ourselves set
               phase = Phase.DEAD;
               return;
            }
            if (hasMoved(player)) {
               restore(client);
               phase = Phase.IDLE;
            } else {
               lastX = player.getX();
               lastZ = player.getZ();
            }
         }
      }
   }

   /**
    * "The player started moving": either they are pressing a movement key, or they actually changed
    * position horizontally.
    *
    * <p>The key check is the real one - it is the player's intent, and it reads the input Cinematic
    * Respawn has already had its say over. The distance check is a backstop for input this mod
    * cannot see (a controller mod, an auto-walk). Horizontal only, so simply falling out of the sky
    * after a respawn does not count as the player moving.
    */
   private static boolean hasMoved(LocalPlayer player) {
      Input keys = player.input == null ? null : player.input.keyPresses;
      if (keys != null && (keys.forward() || keys.backward() || keys.left() || keys.right() || keys.jump())) {
         return true;
      }

      double dx = player.getX() - lastX;
      double dz = player.getZ() - lastZ;
      return dx * dx + dz * dz > MOVE_EPSILON * MOVE_EPSILON;
   }

   private static void force(Minecraft client, CameraType view) {
      forcedView = view;
      client.options.setCameraType(view);
   }

   /** Puts back whatever the player was using before they died. */
   private static void restore(Minecraft client) {
      if (client != null && client.options != null && forcedView != null) {
         client.options.setCameraType(savedView);
      }
      forcedView = null;
   }
}

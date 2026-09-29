package com.merchantmind;

import com.merchantmind.client.RespawnCameraDirector;
import com.merchantmind.client.SlamChargeHud;
import com.merchantmind.client.SlamStretchOverlay;
import com.merchantmind.network.ShopPackets;
import com.merchantmind.shop.ShopRegistry;
import com.merchantmind.shop.ShopScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class MerchantMindClient implements ClientModInitializer {
   private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("merchantmind", "merchantmind"));

   private static final int KEY_B = 66;
   private static final int KEY_K = 75;
   private static final int KEY_V = 86;

   /** A key cannot legitimately be pressed more than this in one tick; stops any pathological spin. */
   private static final int MAX_CLICKS_PER_TICK = 64;

   /**
    * 2.1 (2.8.0) - true when Cinematic Respawn was found and the respawn camera feature is live.
    *
    * <p>Read by the Help tab so the player is told the feature exists - and, just as importantly, is
    * told nothing about it when the mod it depends on is not installed.
    */
   public static boolean respawnCameraActive;

   public static KeyMapping openShopKey;
   public static KeyMapping killItemsKey;
   public static KeyMapping slamKey;

   /**
    * 4.1 - the label the player would actually have to press, read live from the key mapping.
    *
    * <p>Every on-screen hint goes through here instead of hard-coding "B" / "K" / "V". If the key
    * is rebound in the controls menu the hint follows on the very next frame, and an unbound key
    * honestly says so rather than lying about a letter that does nothing.
    */
   public static String keyLabel(KeyMapping mapping) {
      if (mapping == null) {
         return "?";
      }
      if (mapping.isUnbound()) {
         return "unbound";
      }
      return mapping.getTranslatedKeyMessage().getString();
   }

   private boolean charging = false;
   private int chargeTicks = 0;

   @Override
   public void onInitializeClient() {
      MenuScreens.register(ShopRegistry.MENU_TYPE, ShopScreen::new);
      SlamStretchOverlay.register();
      /*
       * 3.0.0 - registered after the stretch overlay so the gauge draws on top of it: both are
       * "last", and the streaks reach across the whole screen, so the readable one has to win.
       */
      SlamChargeHud.register();
      /*
       * 1.1 (2.9.0) - the per-tick GUI texture warm-up is gone.
       *
       * It ran on every client tick for the first 15 ticks in a world, loading one texture at a
       * time, and it did that whether or not the player ever opened the shop - a background job,
       * with its own queue and cursor kept alive for the whole session, racing the player. Opening
       * the shop inside that window meant the screen and the warm-up were both loading the same
       * textures, which is exactly the window the crash reports came from.
       *
       * ShopScreen now loads everything it draws itself, in one go, in init() - see
       * ShopScreen.loadGuiAssets(). Nothing runs, and nothing is held in memory, until the menu is
       * actually opened.
       */
      /*
       * 2.1 (2.8.0) - optional integration: with Cinematic Respawn installed, death switches to
       * third person (back) and the first movement after respawning puts the view back. Registers
       * nothing at all when that mod is absent.
       */
      respawnCameraActive = RespawnCameraDirector.register();

      openShopKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.merchantmind.open_shop", KEY_B, CATEGORY));
      killItemsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.merchantmind.kill_items", KEY_K, CATEGORY));
      slamKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.merchantmind.charge_slam", KEY_V, CATEGORY));

      ClientTickEvents.END_CLIENT_TICK.register(client -> {
         // 1.1 - one bad frame must never take the client down with it
         try {
            this.tickShopKey(client);
            this.tickKillItemsKey(client);
            this.tickChargeSlam(client);
         } catch (Throwable error) {
            MerchantMind.LOGGER.error("[{}] client tick failed - disabling charge state to recover", MerchantMind.MOD_ID, error);
            this.panicReset(client);
         }
      });

      /*
       * 3.3.1 - there is deliberately NO button on the pause screen any more, and nothing here
       * touches a screen this mod does not own.
       *
       * What was here added a 204x20 "✦ Open Merchant Shop" button straight into PauseScreen's
       * widget list at a hard-coded position - centred, 30px off the bottom - which is the one thing
       * a mod must not do to a screen every other mod also decorates. It reserved no space in the
       * pause screen's layout, so it did not push anything aside: it was simply drawn over whatever
       * else was at those coordinates. Any other mod's button in that strip ended up hidden
       * underneath it, and because click handling walks the widget list and stops at the first
       * widget that reports a hit, being in the list at all meant it could also swallow the click
       * aimed at the button it was covering. That is somebody else's feature silently disappearing
       * because THIS mod was installed, which is not a trade the shop was ever worth.
       *
       * Nothing is lost. The keybind (default B, rebindable in Controls, and unbindable) sends the
       * same OpenShopC2SPayload from tickShopKey below, so it remains the way in, and it is the only
       * way in. The packet, the server handler and ShopScreen are all untouched.
       *
       * If a menu entry is ever wanted again, it must go through a layout API that reserves its own
       * row rather than into getWidgets() at fixed coordinates - the position is not the bug, adding
       * an unmanaged widget to a shared screen is.
       */

      /*
       * 3.0.0 - the server spent the charge on a hit, so the gauge is wrong as of this instant.
       *
       * The server restarts the hold from zero when it consumes one (see handleAfterDamage), and
       * the client cannot see that happen. Without this the bar would keep climbing towards a
       * charge that had already been cashed in, which is exactly the case the brief calls out:
       * the gauge must be gone once the attack has gone out, not only when the key is let go.
       * The counter restarts here too, so holding through the hit rebuilds the charge honestly.
       */
      ClientPlayNetworking.registerGlobalReceiver(ShopPackets.SlamConsumedS2CPayload.TYPE, (payload, ctx) -> {
         Minecraft client = ctx.client();
         if (client == null) {
            return;
         }
         client.execute(() -> {
            this.chargeTicks = 0;
            SlamStretchOverlay.setCharge(0.0F);
            SlamChargeHud.hide();
         });
      });

      // 5.1 - same reasoning as the server side: client.execute() runs the task inside the client
      // tick, so an exception escaping here would take the whole client down.
      ClientPlayNetworking.registerGlobalReceiver(ShopPackets.SyncS2CPayload.TYPE, (payload, ctx) -> {
         Minecraft client = ctx.client();
         if (client == null) {
            return;
         }

         client.execute(() -> {
            try {
               if (client.screen instanceof ShopScreen shop) {
                  shop.applySync(payload);
               }
            } catch (Throwable error) {
               MerchantMind.LOGGER.error("[{}] applying a shop sync packet failed - the screen is left as it was", MerchantMind.MOD_ID, error);
            }
         });
      });
   }

   private void tickShopKey(Minecraft client) {
      // bounded: consumeClick() should always drain, but a bounded loop can never hang the client
      for (int guard = 0; guard < MAX_CLICKS_PER_TICK && openShopKey.consumeClick(); guard++) {
         if (client.player == null) {
            continue;
         }
         if (client.screen instanceof ShopScreen shop) {
            shop.onClose();
         } else if (client.screen == null) {
            ClientPlayNetworking.send(new ShopPackets.OpenShopC2SPayload());
         }
      }
   }

   /** 3.1 - one press wipes every dropped item; the server replies with the count. */
   private void tickKillItemsKey(Minecraft client) {
      for (int guard = 0; guard < MAX_CLICKS_PER_TICK && killItemsKey.consumeClick(); guard++) {
         if (client.player != null && client.screen == null) {
            ClientPlayNetworking.send(new ShopPackets.KillItemsC2SPayload());
            client.gui.setOverlayMessage(Component.literal("🗑 Clearing dropped items…"), false);
         }
      }
   }

   /**
    * 3.1/3.2 - hold to charge; the server turns the exact hold time into bonus damage on the next
    * sword/axe hit.
    *
    * <p>The zoom is gone. It used to work by writing {@code client.options.fov()} down towards 22
    * every tick, which is a real, persisted video setting - so it fought with any other mod that
    * touches FOV, and a crash or disconnect mid-charge left the player permanently zoomed in.
    * {@link SlamStretchOverlay} now draws the wind-up as a HUD effect instead: nothing outside this
    * mod is modified, and there is no state that can leak if the client dies mid-charge.
    */
   private void tickChargeSlam(Minecraft client) {
      if (client.player == null || client.level == null) {
         if (this.charging) {
            this.panicReset(client);
         }
         return;
      }

      boolean wantCharge = client.screen == null && slamKey.isDown();

      if (wantCharge && !this.charging) {
         this.charging = true;
         this.chargeTicks = 0;
         ClientPlayNetworking.send(new ShopPackets.SlamChargeC2SPayload(true));
      } else if (!wantCharge && this.charging) {
         this.releaseCharge(client.player != null);
         return;
      }

      if (!this.charging) {
         return;
      }

      this.chargeTicks = Math.min(MerchantMind.SLAM_MAX_TICKS, this.chargeTicks + 1);
      float ratio = this.chargeTicks / (float)MerchantMind.SLAM_MAX_TICKS;
      SlamStretchOverlay.setCharge(ratio);
      /*
       * 3.0.0 - the charge readout is a drawn HUD element now, not an action bar message.
       *
       * What used to be here was client.gui.setOverlayMessage(...) carrying a bar built out of
       * block characters. The action bar is a single shared slot with its own fade, so any other
       * overlay message - including this mod's own - wiped the charge display mid-swing, and ten
       * text cells could not resolve a 38 tick charge. SlamChargeHud draws the gauge itself, above
       * the hotbar, and owns its own lifetime. See that class.
       */
      SlamChargeHud.show(this.chargeTicks);
   }

   /** Puts the client back in a sane state after an unexpected error mid-charge. */
   private void panicReset(Minecraft client) {
      this.charging = false;
      this.chargeTicks = 0;
      SlamStretchOverlay.setCharge(0.0F);
      SlamChargeHud.hide();
   }

   private void releaseCharge(boolean notifyServer) {
      this.charging = false;
      this.chargeTicks = 0;
      SlamStretchOverlay.setCharge(0.0F);
      SlamChargeHud.hide();
      if (notifyServer) {
         ClientPlayNetworking.send(new ShopPackets.SlamChargeC2SPayload(false));
      }
   }

}

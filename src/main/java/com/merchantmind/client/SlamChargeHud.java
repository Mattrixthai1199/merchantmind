package com.merchantmind.client;

import com.merchantmind.MerchantMind;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * 3.0.0 - the Charge-and-Slam gauge: a real, drawn HUD widget above the hotbar.
 *
 * <h2>What this replaces</h2>
 *
 * Charging used to report itself through {@code Gui.setOverlayMessage} - the vanilla action bar -
 * with the bar faked out of {@code █} and {@code ░} characters inside a chat component. That was
 * text pretending to be a gauge, and it came with everything the action bar does: it is one shared
 * slot, so any other overlay message (an advertisement of a changed held item, another mod, this
 * mod's own "clearing dropped items" note) replaced the charge mid-swing; it inherits the action
 * bar's own fade timing rather than the charge's; and its resolution was ten blocks for a
 * thirty-eight tick charge, so nearly four ticks of hold produced no visible change at all.
 *
 * <h2>What is drawn instead</h2>
 *
 * Nothing here goes through any Minecraft message system. It is one HUD element drawing flat
 * rectangles and text of its own:
 *
 * <ul>
 *   <li>a <b>charge bar</b>, {@value #BAR_W} px wide - the width of the hotbar and the vanilla
 *       experience bar, so it lines up with the HUD it sits above - with a 1 px black frame, a
 *       recessed track, quarter tick marks and a vertically shaded fill;</li>
 *   <li>a <b>bonus bar</b> underneath it, thinner and crimson, showing the damage the swing is
 *       currently worth as a fraction of {@link MerchantMind#SLAM_MAX_BONUS}. The bonus curve is
 *       {@code ratio^1.6}, so this bar deliberately lags the charge bar: the gap between the two is
 *       the visual statement that the last third of a hold is where the damage is;</li>
 *   <li>a text line above them: {@code SLAM} on the left, the live bonus and percentage on the
 *       right, drawn with the game font in the game's own HUD colours;</li>
 *   <li>a pulsing outline once the charge is full, so "let go now" is readable without looking
 *       away from the crosshair.</li>
 * </ul>
 *
 * <h2>Placement</h2>
 *
 * Centred horizontally on the hotbar and {@value #BOTTOM_MARGIN} px up from the bottom edge, which
 * clears the entire vanilla bottom cluster - hotbar (22), experience bar (~32) and the tallest
 * health/armour/air row (~49) - with room to spare. It never overlaps them at any GUI scale,
 * because every offset is in GUI-scaled pixels, exactly like the elements it is avoiding.
 *
 * <h2>Lifetime</h2>
 *
 * {@link #show} is called once per client tick while the key is held; {@link #hide} is called the
 * instant it is released, the instant the server reports the charge was spent on a hit, and by the
 * client's panic reset. There is no fade and no timer: charge is either being held or the widget is
 * not drawn, so nothing can be left on screen by a crash, a disconnect or a screen opening.
 */
public final class SlamChargeHud {
   private static final Identifier ID = Identifier.fromNamespaceAndPath(MerchantMind.MOD_ID, "slam_charge_hud");

   /** Hotbar / experience-bar width, so the gauge reads as part of the same HUD column. */
   private static final int BAR_W = 182;
   private static final int BAR_H = 9;
   /** The damage-bonus bar under the charge bar. */
   private static final int BONUS_H = 3;
   private static final int GAP = 1;
   /** Distance from the bottom of the screen to the top of the charge bar, in GUI pixels. */
   private static final int BOTTOM_MARGIN = 78;

   /* --- palette, picked to sit with vanilla's own HUD rather than shout over it --- */
   private static final int FRAME = 0xFF000000;
   private static final int TRACK_TOP = 0xFF12151B;
   private static final int TRACK_BOTTOM = 0xFF23272F;
   private static final int TICK = 0xFF31363F;
   /** Fill: cool amber at the start, hot gold at the end. */
   private static final int FILL_LOW_TOP = 0xFFB4741C;
   private static final int FILL_LOW_BOTTOM = 0xFF7A4A10;
   private static final int FILL_HIGH_TOP = 0xFFFFE08A;
   private static final int FILL_HIGH_BOTTOM = 0xFFE0A32B;
   private static final int BONUS_TRACK = 0xFF1A1013;
   private static final int BONUS_FILL = 0xFFD8483C;
   private static final int BONUS_FILL_FULL = 0xFFFF7A5C;
   private static final int LABEL = 0xFF9AA3B0;
   private static final int READOUT = 0xFFFFD37A;
   private static final int READOUT_FULL = 0xFFFFF3C4;

   /**
    * Written from the client tick, read from the render thread - hence volatile. {@code ticks < 0}
    * means "not charging" and is the single flag that decides whether anything is drawn at all.
    */
   private static volatile int ticks = -1;

   private SlamChargeHud() {
   }

   /**
    * @param heldTicks how long the key has been held, clamped by the caller to
    *                  {@link MerchantMind#SLAM_MAX_TICKS}
    */
   public static void show(int heldTicks) {
      ticks = Math.max(0, heldTicks);
   }

   public static void hide() {
      ticks = -1;
   }

   public static void register() {
      HudElementRegistry.addLast(ID, (graphics, delta) -> {
         int held = ticks;
         if (held < 0) {
            return;
         }
         try {
            draw(graphics, held, delta.getGameTimeDeltaPartialTick(false));
         } catch (Throwable error) {
            // the HUD must never be the thing that kills the client
            ticks = -1;
            MerchantMind.LOGGER.error("[{}] slam charge HUD failed - gauge disabled for this charge", MerchantMind.MOD_ID, error);
         }
      });
   }

   private static void draw(GuiGraphicsExtractor graphics, int held, float partialTick) {
      Minecraft client = Minecraft.getInstance();
      if (client.player == null || client.options.hideGui || client.screen != null) {
         return;
      }

      int w = graphics.guiWidth();
      int h = graphics.guiHeight();
      if (w < BAR_W + 8 || h < 60) {
         return;
      }

      /*
       * Real time, not tick time. The counter advances 20 times a second but the HUD draws far more
       * often than that, so the partial tick is added back in: the fill slides smoothly instead of
       * stepping ~4.8 px whenever the client ticks. It is still the true hold time - the value is
       * clamped at the same ceiling the server uses, so the bar cannot promise a charge that does
       * not exist.
       */
      float exact = Math.min(MerchantMind.SLAM_MAX_TICKS, held + partialTick);
      float ratio = Math.max(0.0F, Math.min(1.0F, exact / MerchantMind.SLAM_MAX_TICKS));
      boolean full = ratio >= 1.0F;
      float bonus = MerchantMind.slamBonusFor(Math.round(exact));

      int x = (w - BAR_W) / 2;
      int y = h - BOTTOM_MARGIN;
      int right = x + BAR_W;
      int barBottom = y + BAR_H;

      /* ---- text line above the gauge ---- */
      Font font = client.font;
      int textY = y - 11;
      graphics.text(font, "SLAM", x, textY, LABEL);
      String readout = "\u26a1 +" + String.format(java.util.Locale.ROOT, "%.1f", bonus) + "   " + Math.round(ratio * 100.0F) + "%";
      graphics.text(font, readout, right - font.width(readout), textY, full ? READOUT_FULL : READOUT);

      /* ---- charge bar: frame, recessed track, fill, tick marks ---- */
      graphics.fill(x - 1, y - 1, right + 1, barBottom + 1, FRAME);
      graphics.fillGradient(x, y, right, barBottom, TRACK_TOP, TRACK_BOTTOM);

      int fillW = Math.round(BAR_W * ratio);
      if (fillW > 0) {
         // one gradient stop pair, interpolated by charge, gives a fill that heats up as it grows
         graphics.fillGradient(x, y, x + fillW, barBottom, lerpColor(FILL_LOW_TOP, FILL_HIGH_TOP, ratio), lerpColor(FILL_LOW_BOTTOM, FILL_HIGH_BOTTOM, ratio));
         // a single bright pixel row along the top edge is what makes a flat fill read as a bar
         graphics.fill(x, y, x + fillW, y + 1, lerpColor(FILL_HIGH_TOP, 0xFFFFFFFF, ratio * 0.5F));
      }

      for (int quarter = 1; quarter < 4; quarter++) {
         int tx = x + BAR_W * quarter / 4;
         graphics.fill(tx, y + 1, tx + 1, barBottom - 1, TICK);
      }

      /* ---- bonus bar: how much damage, which is not the same shape as how much charge ---- */
      int by = barBottom + 1 + GAP;
      int bBottom = by + BONUS_H;
      graphics.fill(x - 1, by - 1, right + 1, bBottom + 1, FRAME);
      graphics.fill(x, by, right, bBottom, BONUS_TRACK);
      int bonusW = Math.round(BAR_W * Math.max(0.0F, Math.min(1.0F, bonus / MerchantMind.SLAM_MAX_BONUS)));
      if (bonusW > 0) {
         graphics.fill(x, by, x + bonusW, bBottom, full ? BONUS_FILL_FULL : BONUS_FILL);
      }

      /* ---- full charge: a slow pulse on the frame, so the release cue needs no reading ---- */
      if (full) {
         float pulse = 0.35F + 0.65F * (float)Math.abs(Math.sin(pulseClock() / 180.0));
         int glow = (int)(255 * pulse) << 24 | 0x00FFF0C0;
         graphics.fill(x - 1, y - 1, right + 1, y, glow);
         graphics.fill(x - 1, barBottom, right + 1, barBottom + 1, glow);
         graphics.fill(x - 1, y - 1, x, barBottom + 1, glow);
         graphics.fill(right, y - 1, right + 1, barBottom + 1, glow);
      }
   }

   /** Wall clock for the pulse; deliberately independent of game ticks so it never stutters. */
   private static double pulseClock() {
      return System.currentTimeMillis() % 100000L;
   }

   /** Straight per-channel blend of two opaque ARGB colours. */
   private static int lerpColor(int from, int to, float t) {
      float k = Math.max(0.0F, Math.min(1.0F, t));
      int a = 0xFF;
      int r = Math.round((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * k);
      int g = Math.round((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * k);
      int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
      return a << 24 | r << 16 | g << 8 | b;
   }
}

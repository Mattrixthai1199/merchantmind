package com.merchantmind.client;

import com.merchantmind.MerchantMind;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * The charge-up "stretch", drawn on the HUD.
 *
 * <h2>Why not a zoom</h2>
 *
 * The old effect drove {@code Options.fov()} down to 22 while the slam key was held. That is a
 * persisted user setting, not a render-time value: every charge rewrote the player's own FOV
 * option, it fought with any other mod that adjusts FOV, and if the game crashed, the connection
 * dropped or an exception escaped mid-charge, the player was left zoomed in until they went and
 * fixed the slider by hand. It also simply <em>was</em> a zoom, which is the thing that was asked
 * to go away.
 *
 * <h2>What this draws instead</h2>
 *
 * Two cues, both scaled by charge and both purely additive overlay:
 *
 * <ul>
 *   <li><b>Anamorphic squeeze bars</b> at the top and bottom of the screen, growing inward. The
 *       frame appears to be pulled wide as the swing winds up.</li>
 *   <li><b>Horizontal motion streaks</b> reaching in from the left and right edges, layered in
 *       four steps of decreasing alpha to fake a gradient (the HUD only gives us flat rectangles).
 *       Row lengths are varied by a fixed table so the streaks do not look like a bar chart, and
 *       the table is constant, so the effect is identical every charge.</li>
 * </ul>
 *
 * <p>No world state, no options, no mixin. {@link #setCharge} is the only entry point and a charge
 * of zero draws literally nothing, so there is nothing to leak if the client dies mid-swing.
 */
public final class SlamStretchOverlay {
   private static final Identifier ID = Identifier.fromNamespaceAndPath(MerchantMind.MOD_ID, "slam_stretch");

   /**
    * 1.3 - charge fraction at which the effect switches on.
    *
    * <p>It used to scale straight off {@code ratio squared}, so at 4% charge the squeeze bar
    * computed to {@code round(h * 0.055 * 0.0016)} = <b>0 pixels</b> and the alpha to 0: the player
    * saw literally nothing until the charge was most of the way done. The ramp now starts here and
    * opens at {@link #MIN_STRENGTH}, so the very first visible frame is already unmistakable.
    */
   private static final float START_AT = 0.04F;
   /** Strength the effect snaps to the moment it crosses {@link #START_AT}. */
   private static final float MIN_STRENGTH = 0.30F;

   /** How far the streaks reach in, as a fraction of screen width, at full charge. */
   private static final float STREAK_MAX_REACH = 0.20F;
   /** How tall the top/bottom squeeze bars get, as a fraction of screen height, at full charge. */
   private static final float SQUEEZE_MAX = 0.055F;
   private static final int STREAK_ROWS = 9;
   /** Fixed per-row length multipliers - constant so the effect never shimmers randomly. */
   private static final float[] ROW_SCALE = {0.55F, 1.00F, 0.72F, 0.88F, 0.45F, 0.95F, 0.63F, 1.00F, 0.50F};

   private static final int TINT = 0xFFD37A;   // the mod's gold, alpha applied per layer
   private static final int SQUEEZE = 0x05070B;

   /** 0 = idle, 1 = fully charged. Written from the client tick, read from the HUD pass. */
   private static volatile float charge = 0.0F;

   /* ------------------------------------------------------------------ *
    *  1.1 (2.7.0) - cached row geometry.
    *
    *  The streak rows sit at fixed positions that depend only on the screen
    *  height, but they were recomputed on every frame of every charge. The
    *  window is not resized while a slam is winding up, so the layout is
    *  computed when the height actually changes and read back otherwise.
    *
    *  The number of fills is deliberately unchanged: the layered rectangles
    *  ARE the effect, and there is no cheaper way to fake a gradient with
    *  flat fills. What is gone is the per-frame arithmetic and the four alpha
    *  values that used to be recomputed once per row instead of once.
    * ------------------------------------------------------------------ */
   private static int cachedHeight = -1;
   private static int cachedRowH;
   private static final int[] ROW_Y = new int[STREAK_ROWS];
   /** Scratch for the four step colours, reused so the HUD pass allocates nothing. */
   private static final int[] STEP_COLOR = new int[4];

   private SlamStretchOverlay() {
   }

   public static void setCharge(float value) {
      charge = Math.max(0.0F, Math.min(1.0F, value));
   }

   public static void register() {
      HudElementRegistry.addLast(ID, (graphics, delta) -> {
         float ratio = charge;
         if (ratio < START_AT) {
            return;
         }
         try {
            draw(graphics, ratio);
         } catch (Throwable error) {
            // a broken overlay must never take the HUD - and with it the client - down
            charge = 0.0F;
            MerchantMind.LOGGER.error("[{}] slam stretch overlay failed - effect disabled for this charge", MerchantMind.MOD_ID, error);
         }
      });
   }

   private static void draw(net.minecraft.client.gui.GuiGraphicsExtractor graphics, float ratio) {
      Minecraft client = Minecraft.getInstance();
      if (client.getWindow() == null) {
         return;
      }
      int w = client.getWindow().getGuiScaledWidth();
      int h = client.getWindow().getGuiScaledHeight();
      if (w <= 0 || h <= 0) {
         return;
      }

      /*
       * 1.3 - remap [START_AT..1] onto [MIN_STRENGTH..1] instead of using the raw ratio.
       *
       * The squaring is kept, because the effect building towards the release is the whole point,
       * but it now happens *above* a visible floor rather than from zero. At 4% charge the frame
       * already shows a real bar and real streaks; from there it keeps growing to the full effect.
       */
      float t = (ratio - START_AT) / (1.0F - START_AT);
      t = Math.max(0.0F, Math.min(1.0F, t));
      float eased = MIN_STRENGTH + (1.0F - MIN_STRENGTH) * (t * t);

      /* --- anamorphic squeeze --- */
      int bar = Math.max(1, Math.round(h * SQUEEZE_MAX * eased));
      int alpha = (int)(215 * eased) << 24;
      graphics.fill(0, 0, w, bar, alpha | SQUEEZE);
      graphics.fill(0, h - bar, w, h, alpha | SQUEEZE);

      /* --- motion streaks --- */
      int reach = Math.round(w * STREAK_MAX_REACH * eased);
      if (reach <= 0) {
         return;
      }
      if (h != cachedHeight) {
         cachedHeight = h;
         cachedRowH = Math.max(1, h / (STREAK_ROWS * 4));
         int spacing = h / (STREAK_ROWS + 1);
         for (int row = 0; row < STREAK_ROWS; row++) {
            ROW_Y[row] = spacing * (row + 1) - cachedRowH / 2;
         }
      }
      int rowH = cachedRowH;

      /*
       * The four step colours depend only on the charge, not on the row, so they are built once per
       * frame instead of 36 times. If even the brightest step is fully transparent there is nothing
       * to draw at all.
       */
      int[] stepColor = STEP_COLOR;
      boolean anyVisible = false;
      for (int step = 0; step < 4; step++) {
         int a = (int)(170 * eased * (1.0F - step / 4.0F));
         stepColor[step] = a << 24 | TINT;
         anyVisible |= a > 0;
      }
      if (!anyVisible) {
         return;
      }

      for (int row = 0; row < STREAK_ROWS; row++) {
         int y = ROW_Y[row];
         int bottom = y + rowH;
         int len = Math.round(reach * ROW_SCALE[row]);
         if (len <= 0) {
            continue;
         }
         // four flat steps standing in for a gradient: brightest at the edge, fading inward
         int from = 0;
         for (int step = 0; step < 4; step++) {
            int to = len * (step + 1) / 4;
            if (to > from && (stepColor[step] >>> 24) != 0) {
               graphics.fill(w - to, y, w - from, bottom, stepColor[step]);
               graphics.fill(from, y, to, bottom, stepColor[step]);
            }
            from = to;
         }
      }
   }
}

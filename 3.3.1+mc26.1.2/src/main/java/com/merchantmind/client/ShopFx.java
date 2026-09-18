package com.merchantmind.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 3.2.0 - the shop screen's animation toolkit.
 *
 * <p>Every method here is <b>static, pure and stateless</b>: it takes a clock (or a 0..1 progress),
 * a rectangle and a colour, and draws. Nothing in this class touches the menu, the container, the
 * network or the world, and nothing in it remembers anything between calls - which is the whole
 * design rule of the animation layer. The worst a bug in this file can do is look wrong.
 *
 * <p>It is built out of {@code fill} and {@code text} only, deliberately. Those are the two
 * primitives the shop screen already used before this release, so no new rendering path is
 * introduced and there is nothing here that can fail to draw on one machine and work on another.
 * Circles, rings, gradients and glows are all plotted as small rectangles.
 *
 * <p>Cost matters, because all of this runs inside the frame. Every loop in this file is bounded by
 * a small constant (a ring is 32 points, a gradient 8 bands, a burst 3 rings), nothing allocates,
 * and the per-character helpers walk a string once. The heaviest single call is
 * {@link #shimmerText}, which is one text draw per character; it is used on short labels only.
 */
public final class ShopFx {

   private ShopFx() {
   }

   /* ==================================================================== *
    *  clocks and easing
    * ==================================================================== */

   /** A 0..1 sine that completes one cycle every {@code period} ticks. */
   public static float pulse(float clock, float period) {
      return 0.5F + 0.5F * (float)Math.sin(clock / period * 2.0 * Math.PI);
   }

   /** A 0..1 sawtooth: rises linearly over {@code period} ticks, then snaps back. */
   public static float saw(float clock, float period) {
      float t = clock / period;
      return t - (float)Math.floor(t);
   }

   /** Ease-out-cubic: fast at first, gently settling. */
   public static float easeOut(float t) {
      float u = 1.0F - clamp01(t);
      return 1.0F - u * u * u;
   }

   /** Ease-in-out-sine, for anything that should leave and arrive at rest. */
   public static float easeInOut(float t) {
      return 0.5F - 0.5F * (float)Math.cos(clamp01(t) * Math.PI);
   }

   /**
    * Overshoots past 1 and settles back - the "pop" curve.
    *
    * <p>Used for anything that appears rather than moves: the countdown digit changing, the READY
    * banner, the trade result landing. A linear or ease-out appearance reads as a fade; the
    * overshoot is what makes it read as an impact.
    */
   public static float easeOutBack(float t) {
      float u = clamp01(t) - 1.0F;
      return 1.0F + u * u * (2.70158F * u + 1.70158F);
   }

   public static float clamp01(float t) {
      return t < 0.0F ? 0.0F : (t > 1.0F ? 1.0F : t);
   }

   /* ==================================================================== *
    *  colour
    * ==================================================================== */

   /** Linear blend between two ARGB colours, alpha included. */
   public static int mix(int from, int to, float t) {
      float k = clamp01(t);
      int out = 0;
      for (int shift = 0; shift < 32; shift += 8) {
         int a = from >> shift & 0xFF;
         int b = to >> shift & 0xFF;
         out |= (int)(a + (b - a) * k) << shift;
      }
      return out;
   }

   /** Scales a colour's existing alpha, for fading things in and out. */
   public static int withAlpha(int color, float alpha) {
      int a = (int)(clamp01(alpha) * (color >>> 24));
      return color & 0x00FFFFFF | a << 24;
   }

   /* ==================================================================== *
    *  primitives
    * ==================================================================== */

   /** {@code fill} that refuses to draw an inside-out or invisible rectangle. */
   public static void box(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
      if (x2 > x1 && y2 > y1 && (color >>> 24) != 0) {
         g.fill(x1, y1, x2, y2, color);
      }
   }

   /** A one pixel outline, as four fills. */
   public static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
      box(g, x, y, x + w, y + 1, color);
      box(g, x, y + h - 1, x + w, y + h, color);
      box(g, x, y, x + 1, y + h, color);
      box(g, x + w - 1, y, x + w, y + h, color);
   }

   /**
    * A vertical gradient, drawn as {@code bands} horizontal strips.
    *
    * <p>Eight bands is enough to read as a gradient at GUI scale and cheap enough to run on a panel
    * every frame; a per-pixel-row version of this was visibly no better and eight times the calls.
    */
   public static void vGradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int top, int bottom, int bands) {
      int n = Math.max(1, bands);
      for (int i = 0; i < n; i++) {
         int y1 = y + h * i / n;
         int y2 = y + h * (i + 1) / n;
         box(g, x, y1, x + w, y2, mix(top, bottom, (i + 0.5F) / n));
      }
   }

   /**
    * A soft moving highlight band inside a rectangle - the "shine sweep".
    *
    * <p>{@code phase} 0..1 walks the band from just before the left edge to just past the right, so
    * a caller can drive it from {@link #saw} for a loop or from a progress value for a one-shot. The
    * band is drawn as a handful of columns whose alpha falls off from the centre, which is what makes
    * it read as light rather than as a moving rectangle.
    */
   public static void shine(GuiGraphicsExtractor g, int x, int y, int w, int h, float phase, int color, float strength) {
      if (w <= 0 || h <= 0 || strength <= 0.0F) {
         return;
      }
      int halfWidth = Math.max(3, w / 8);
      int centre = x - halfWidth + (int)(phase * (w + halfWidth * 2));
      for (int i = -halfWidth; i <= halfWidth; i++) {
         int cx = centre + i;
         if (cx < x || cx >= x + w) {
            continue;
         }
         float falloff = 1.0F - Math.abs(i) / (float)halfWidth;
         box(g, cx, y, cx + 1, y + h, withAlpha(color, falloff * falloff * strength));
      }
   }

   /**
    * Diagonal stripes scrolling to the right, clipped to a rectangle - the "barber pole".
    *
    * <p>Drawn as one-pixel columns whose vertical offset shifts with x, which is how a diagonal is
    * built out of axis-aligned fills. It is what makes a progress bar look like it is working during
    * the seconds when its length has not visibly changed.
    */
   public static void stripes(GuiGraphicsExtractor g, int x, int y, int w, int h, float phase, int color, float alpha, int spacing) {
      if (w <= 0 || h <= 0) {
         return;
      }
      int step = Math.max(3, spacing);
      int shift = (int)(phase * step) % step;
      for (int i = 0; i < w; i++) {
         int cx = x + i;
         // the +i term is the slope: one pixel down per pixel right, i.e. a 45 degree stripe
         if ((i + shift + h) % step < 2) {
            box(g, cx, y, cx + 1, y + h, withAlpha(color, alpha));
         }
      }
   }

   /** A horizontal line sweeping down a rectangle, wrapping - a scanline. */
   public static void scanline(GuiGraphicsExtractor g, int x, int y, int w, int h, float phase, int color, float alpha) {
      if (w <= 0 || h <= 0) {
         return;
      }
      int ly = y + (int)(clamp01(phase) * (h - 1));
      box(g, x, ly, x + w, ly + 1, withAlpha(color, alpha));
      box(g, x, ly + 1, x + w, ly + 2, withAlpha(color, alpha * 0.35F));
   }

   /**
    * A circle outline plotted as 32 points.
    *
    * <p>32 is chosen rather than something adaptive because the rings this draws are 4..40 pixels
    * across: below 32 points a small ring reads as a polygon, above it a large one gains nothing
    * because neighbouring points land on the same pixel.
    */
   public static void ring(GuiGraphicsExtractor g, int cx, int cy, float radius, int color, int thickness) {
      if (radius <= 0.0F || (color >>> 24) == 0) {
         return;
      }
      int t = Math.max(1, thickness);
      for (int i = 0; i < 32; i++) {
         double a = i / 32.0 * 2.0 * Math.PI;
         int px = cx + (int)Math.round(Math.cos(a) * radius);
         int py = cy + (int)Math.round(Math.sin(a) * radius);
         box(g, px, py, px + t, py + t, color);
      }
   }

   /**
    * Rings expanding outward and fading - the impact burst.
    *
    * @param t 0..1 through the burst; three rings are staggered across it so the burst reads as a
    *          shockwave rather than as one circle growing
    */
   public static void burst(GuiGraphicsExtractor g, int cx, int cy, float t, float maxRadius, int color) {
      for (int i = 0; i < 3; i++) {
         float local = clamp01(t * 1.6F - i * 0.18F);
         if (local <= 0.0F || local >= 1.0F) {
            continue;
         }
         float r = easeOut(local) * maxRadius;
         ring(g, cx, cy, r, withAlpha(color, (1.0F - local) * (1.0F - i * 0.25F)), 1);
      }
   }

   /**
    * Dots orbiting a centre, evenly spaced, with a short fading tail behind each.
    *
    * <p>The tail is what separates this from "some dots in a circle": three trailing samples at
    * decreasing alpha read as motion blur even at 20 Hz.
    */
   public static void orbit(GuiGraphicsExtractor g, int cx, int cy, float radius, int count, float clock, float speed, int color, int size) {
      int n = Math.max(1, count);
      for (int i = 0; i < n; i++) {
         double base = clock * speed + i / (double)n * 2.0 * Math.PI;
         for (int tail = 0; tail < 3; tail++) {
            double a = base - tail * 0.14;
            int px = cx + (int)Math.round(Math.cos(a) * radius);
            int py = cy + (int)Math.round(Math.sin(a) * radius);
            int s = Math.max(1, size - tail);
            box(g, px, py, px + s, py + s, withAlpha(color, 1.0F - tail * 0.3F));
         }
      }
   }

   /**
    * A travelling glint: a short bright segment running along a horizontal line.
    *
    * <p>Used on the header underline and on panel edges. Cheap, and the one effect on screen that
    * never stops, so the panel is never completely still.
    */
   public static void glint(GuiGraphicsExtractor g, int x, int y, int w, float phase, int color, int length) {
      if (w <= 0) {
         return;
      }
      int len = Math.max(2, length);
      int head = x + (int)(clamp01(phase) * w);
      for (int i = 0; i < len; i++) {
         int cx = head - i;
         if (cx < x || cx >= x + w) {
            continue;
         }
         box(g, cx, y, cx + 1, y + 1, withAlpha(color, 1.0F - i / (float)len));
      }
   }

   /**
    * Text with a bright band travelling through it, one character at a time.
    *
    * <p>Each character is drawn separately with its own colour, mixed toward {@code hi} by how close
    * the moving band is to it. Used on short labels - the panel title, a price under the cursor -
    * because it is one draw call per character.
    */
   public static void shimmerText(
      GuiGraphicsExtractor g, Font font, String text, int x, int y, int base, int hi, float phase, boolean shadow
   ) {
      if (text == null || text.isEmpty()) {
         return;
      }
      int n = text.length();
      float head = clamp01(phase) * (n + 6.0F) - 3.0F;
      int cx = x;
      for (int i = 0; i < n; i++) {
         String ch = text.substring(i, i + 1);
         float d = Math.abs(i - head);
         float lit = d > 3.0F ? 0.0F : (1.0F - d / 3.0F);
         g.text(font, ch, cx, y, mix(base, hi, lit * lit), shadow);
         cx += font.width(ch);
      }
   }

   /**
    * A chevron pointing down, drawn as two diagonal runs of pixels.
    *
    * <p>The "put the iron here" pointer. Solid geometry rather than the "▼" glyph, because it has to
    * bounce a couple of pixels and a text glyph at GUI scale cannot be moved smoothly.
    */
   public static void chevronDown(GuiGraphicsExtractor g, int cx, int cy, int size, int color) {
      for (int i = 0; i < size; i++) {
         box(g, cx - size + i, cy + i, cx - size + i + 2, cy + i + 1, color);
         box(g, cx + size - i - 1, cy + i, cx + size - i + 1, cy + i + 1, color);
      }
   }

   /**
    * "…" that actually animates: one, two, then three dots, half a second apart.
    *
    * @return the ellipsis to append to a label, never longer than three characters so the label's
    *         width cannot jump the text around it
    */
   public static String ellipsis(float clock) {
      int dots = 1 + (int)(clock / 10.0F) % 3;
      return dots == 1 ? "." : (dots == 2 ? ".." : "...");
   }
}

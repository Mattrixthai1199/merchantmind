package com.merchantmind.ai;

import java.util.Map;
import java.util.Map.Entry;
import java.util.function.ToIntFunction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * The "price AI" behind the Sell tab.
 *
 * <h2>What changed and why</h2>
 *
 * The old appraiser multiplied every stack by {@code 0.7 + 0.6 * net.score(...)}, i.e. a fuzzy
 * 0.7x..1.3x fudge factor around the item's value, and one of the features it fed the net was
 * {@code min(1, count / 64)} with a weight of <b>-0.5</b>. Bringing more of something therefore
 * pushed the score <i>down</i>: selling 64 iron paid less per ingot than selling 4. That is the
 * opposite of how a bulk sale works and it is what made payouts feel disconnected from what you
 * actually put in the slots.
 *
 * <h2>The model now</h2>
 *
 * <pre>
 *   base   = sum over stacks of  unitSellPrice(item) * count      &lt;- real per-item value
 *   bulk   = BULK_MAX * progress(base, count)                     &lt;- capped volume bonus
 *   payout = round(base * (1 + bulk)),  clamped to WORTH_CEILING * totalWorth
 * </pre>
 *
 * <ul>
 *   <li><b>Value first, bonus second.</b> {@code base} comes straight from the worth table, so a
 *       stack of netherite is never appraised like a stack of dirt.</li>
 *   <li><b>More in = more per unit.</b> {@code bulk} only ever grows as you add items, so the
 *       effective price per item rises with the size of the haul - never falls.</li>
 *   <li><b>Capped.</b> {@code bulk} saturates at {@value #BULK_MAX_PERCENT}%. Past that point extra
 *       items still add their own value but stop improving the rate, so there is no runaway.</li>
 *   <li><b>No money printer.</b> The payout is additionally clamped to {@value #WORTH_CEILING_PERCENT}%
 *       of the goods' true worth, while the shop sells at 125% of worth. Buying from the shop and
 *       selling straight back is always a loss, at any volume.</li>
 * </ul>
 *
 * <p>Both halves of {@code progress} are non-decreasing in both count and value, so the whole
 * function is monotonic: adding an item to the slots can never lower the quote.
 */
public final class PriceAppraiser {
   /** Confidence/flavour only - it no longer touches the number the player is paid. */
   private static final NeuralNet NET = new NeuralNet(4, 6493802617414025233L, 6, 4, 1);

   /** Maximum bulk bonus, as a fraction of the base value. */
   private static final int BULK_MAX_PERCENT = 35;
   private static final double BULK_MAX = BULK_MAX_PERCENT / 100.0;
   /**
    * Base value at which the value half of the bonus is fully earned. Tuned so that selling a full
    * stack of an ordinary item already moves the rate by a visible ~14%, rather than a rounding
    * error the player never notices.
    */
   private static final double BULK_FULL_VALUE = 600.0;
   /** Item count at which the count half of the bonus is fully earned (three full stacks). */
   private static final double BULK_FULL_COUNT = 192.0;
   /** Value drives most of the bonus; raw count matters, but a pile of cobble is not a fortune. */
   private static final double VALUE_WEIGHT = 0.75;
   private static final double COUNT_WEIGHT = 0.25;
   /** Hard ceiling: the shop never pays more than this share of an item's true worth. */
   private static final int WORTH_CEILING_PERCENT = 70;

   private PriceAppraiser() {
   }

   /**
    * @param offered   what is sitting in the sell slots
    * @param unitSell  the shop's base per-item price (worth * SELL_RATE)
    * @param unitWorth the item's true worth, used only for the anti-arbitrage ceiling
    */
   public static PriceAppraiser.Quote appraise(Map<Item, Integer> offered, ToIntFunction<Item> unitSell, ToIntFunction<Item> unitWorth) {
      if (offered.isEmpty()) {
         return new PriceAppraiser.Quote(0L, 0, "Drop items in the sell slots and I'll appraise them.");
      }

      long base = 0L;
      long worth = 0L;
      int totalCount = 0;
      int topTier = 1;
      StringBuilder items = new StringBuilder();

      for (Entry<Item, Integer> stack : offered.entrySet()) {
         Item item = stack.getKey();
         int count = Math.max(0, stack.getValue());
         if (count == 0) {
            continue;
         }
         String id = BuiltInRegistries.ITEM.getKey(item).toString();

         base += (long)Math.max(1, unitSell.applyAsInt(item)) * count;
         worth += (long)Math.max(1, unitWorth.applyAsInt(item)) * count;
         totalCount += count;
         topTier = Math.max(topTier, ItemFeatures.tier(id));

         if (items.length() > 0) {
            items.append(", ");
         }
         items.append(count).append("x ").append(ItemFeatures.pretty(id));
      }

      if (base <= 0L || totalCount == 0) {
         return new PriceAppraiser.Quote(0L, 0, "Nothing in there is worth anything to me.");
      }

      double bulk = bulkBonus(base, totalCount);
      long payout = Math.round(base * (1.0 + bulk));

      // anti-arbitrage clamp - buy price is 125% of worth, so this keeps a round trip a loss
      long ceiling = Math.max(1L, worth * WORTH_CEILING_PERCENT / 100L);
      boolean capped = payout > ceiling;
      if (capped) {
         payout = ceiling;
      }

      int bonusPercent = (int)Math.round(bulk * 100.0);
      long bonusCoins = payout - base;

      String note;
      if (bonusCoins > 0L) {
         note = "Bulk rate +" + bonusPercent + "% (" + bonusCoins + "c on top of " + base + "c)"
            + (bulk >= BULK_MAX - 1.0E-6 ? " — that's my best rate, the bonus is maxed." : ". Bring more and the rate improves.");
      } else if (capped) {
         note = "That's already the most these are worth to me.";
      } else {
         note = topTier >= 4 ? "Quality goods, but a small lot — bulk pays better." : "Fair market rate for these.";
      }

      double confidence = clamp01(0.55 + 0.45 * NET.score(new double[]{
         topTier / 5.0,
         Math.min(1.0, Math.log1p(base) / Math.log(BULK_FULL_VALUE)),
         Math.min(1.0, totalCount / BULK_FULL_COUNT),
         ItemFeatures.archetype(BuiltInRegistries.ITEM.getKey(offered.keySet().iterator().next()).toString()).equals("rare") ? 1.0 : 0.4
      }));

      String reasoning = "Appraised " + items + " at " + payout + "c. " + note
         + String.format(" (confidence %.0f%%)", confidence * 100.0);
      return new PriceAppraiser.Quote(payout, totalCount, reasoning);
   }

   /**
    * The volume bonus, in 0..{@link #BULK_MAX}.
    *
    * <p>Two independent ramps, both linear and both clamped at 1, then blended. A high-value haul
    * saturates on the value ramp; a huge pile of cheap goods still earns most of the way up the
    * count ramp. Whichever way you bring volume, the rate improves, and it stops improving at
    * {@link #BULK_MAX}.
    */
   private static double bulkBonus(long base, int totalCount) {
      double valueProgress = Math.min(1.0, base / BULK_FULL_VALUE);
      double countProgress = Math.min(1.0, totalCount / BULK_FULL_COUNT);
      return BULK_MAX * clamp01(VALUE_WEIGHT * valueProgress + COUNT_WEIGHT * countProgress);
   }

   private static double clamp01(double value) {
      return Math.max(0.0, Math.min(1.0, value));
   }

   static {
      NET.encourageFeature(0, 1.2);
      NET.encourageFeature(1, 0.9);
      // 4.2 - was -0.5, which is what made bigger stacks score WORSE. Volume is a positive now.
      NET.encourageFeature(2, 0.6);
   }

   public record Quote(long coins, int quantity, String reasoning) {
   }
}

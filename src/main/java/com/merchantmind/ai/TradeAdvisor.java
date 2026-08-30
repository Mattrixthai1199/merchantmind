package com.merchantmind.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * The "trade AI" behind the Trade tab.
 *
 * <h2>Trade is a barter, not a sale</h2>
 *
 * Selling is about coins; trading is about swapping goods of comparable value. The two used to
 * share the same numbers, and badly: the old code valued the player's input at the shop's
 * <i>sell</i> rate (45% of worth) and priced the return item at the shop's <i>buy</i> rate (125% of
 * worth). Handing over a diamond sword therefore bought you about a third of a diamond sword's
 * worth of goods. On top of that the actual choice of item was made by whichever candidate scored
 * highest on a fixed-seed neural net fed tier/archetype features, so what came back had little to
 * do with what went in - it looked random, because functionally it was.
 *
 * <h2>The model now</h2>
 *
 * <pre>
 *   inputWorth = sum over stacks of  worth(item) * count     &lt;- true worth, both sides
 *   budget     = inputWorth * TRADE_RATE                     &lt;- the merchant's small cut
 *   pick (item, qty) maximising  0.60 * totalFit + 0.40 * unitFit
 *   change     = budget - qty * worth(item)                  &lt;- paid in coins
 * </pre>
 *
 * <ul>
 *   <li><b>totalFit</b> rewards a bundle whose total worth lands on the budget. This is the
 *       "value level" rule: a small pile of junk buys a small item, a rare item buys a rare
 *       item.</li>
 *   <li><b>unitFit</b> compares the worth of a <i>single</i> returned item against the average
 *       worth of a single item handed in, on a log scale. Without it, one diamond block would
 *       happily come back as 64 sticks - same total, absurd trade. With it, high-value input
 *       prefers high-value output.</li>
 *   <li>Both sides are measured in the same unit - worth - so the player can read the two piles
 *       and see that they match.</li>
 * </ul>
 *
 * <h2>3.0.1 - two rules on how MANY come back</h2>
 *
 * The value model above was already worth-based on both sides, but the quantity it produced was
 * simply {@code budget / unitWorth} capped at a stack. Two things fell out of that, and both are
 * fixed here rather than patched at the payout:
 *
 * <ol>
 *   <li><b>Gear is always one.</b> {@code budget / unitWorth} does not know that a sword cannot
 *       stack, so 20 diamonds against a 165c diamond sword asked for <b>nine swords in one
 *       slot</b> - an over-stacked ItemStack, which is not a balance problem but a malformed item.
 *       Every candidate now carries its real {@code maxStack}, and a candidate that cannot stack -
 *       or that {@link ItemFeatures#isWeapon} recognises by name - is capped at one, whatever the
 *       budget is. The unspent worth comes back as change, so the trade stays fair.</li>
 *   <li><b>Bundle size follows worth on a square-root curve.</b> A linear count is what made a
 *       cheap pile buy a mountain: 64 oak logs are only 256c, but against 4c bread that is
 *       {@code 230/4} = <b>58 loaves</b>. Nothing about that is unfair - it is exactly the worth
 *       handed in - and it still reads as absurd, because quantity, not value, is what the player
 *       sees. The bundle is therefore capped at {@code sqrt(inputWorth / BUNDLE_CURVE)}: the nth
 *       item of a bundle effectively costs n times more than the first, so doubling a bundle needs
 *       four times the worth. 64 logs now buy 8 loaves and 198c; a full 64 stack of anything needs
 *       16384c of input, which is around 205 diamonds.</li>
 * </ol>
 *
 * <p>The second rule also does the job the brief asks of it in a way a flat cap could not: because
 * the cap is applied <em>inside</em> the candidate scan, a large budget can no longer be absorbed
 * by a cheap item, so {@code totalFit} pushes the choice towards expensive stock on its own. More
 * worth in means better goods out, not just more of them.
 *
 * <p>The neural net still runs, but only to produce the confidence figure in the flavour text. The
 * item that comes back is chosen by the deterministic value match above, so the same input always
 * yields the same, explainable, trade.
 */
public final class TradeAdvisor {
   private static final NeuralNet NET = new NeuralNet(6, 9127972035748207292L, 8, 6, 1);

   /**
    * The player gets back this share of what they handed over. A trade is meant to feel fair, so
    * the cut is small - but it is not zero, or items could be laundered around the value table for
    * free forever.
    */
   private static final double TRADE_RATE = 0.90;
   /**
    * A return bundle may overshoot the budget by at most this much before it is refused - without
    * it, an input that lands between two shelf prices would always round down and feel stingy.
    *
    * <p>The two constants are chosen together: {@code TRADE_RATE * OVERSHOOT_TOLERANCE = 0.99},
    * so even a maximally lucky trade hands back slightly less worth than it consumed. Trading in
    * circles can never create value, at any input size.
    */
   private static final double OVERSHOOT_TOLERANCE = 1.10;
   private static final int MAX_RETURN_QTY = 64;
   /**
    * 3.0.1 - how quickly a bundle is allowed to grow with the worth handed in.
    *
    * <p>The cap is {@code floor(sqrt(inputWorth / BUNDLE_CURVE))}, i.e. reaching a bundle of n
    * items costs {@code n * n * BUNDLE_CURVE} worth. At 4.0 that reads:
    *
    * <pre>
    *      16c ->  2      256c (64 oak logs) ->  8     4096c -> 32
    *      64c ->  4      1600c (20 diamonds) -> 20   16384c -> 64
    * </pre>
    *
    * <p>Nothing is confiscated by the cap - worth the bundle cannot absorb is returned as coins -
    * so this changes what a trade looks like, never what it is worth. Raising this number makes
    * every bundle smaller; lowering it towards zero restores the old linear behaviour.
    */
   private static final double BUNDLE_CURVE = 4.0;
   /** Log span used to normalise the per-item worth comparison. */
   private static final double UNIT_LOG_SPAN = Math.log(2001.0);

   private static final double TOTAL_FIT_WEIGHT = 0.60;
   private static final double UNIT_FIT_WEIGHT = 0.40;

   private TradeAdvisor() {
   }

   /** Coins-worth of goods the player is entitled to for a given input worth. */
   public static long budgetFor(long inputWorth) {
      return Math.max(0L, Math.round(inputWorth * TRADE_RATE));
   }

   /**
    * 3.0.1 - the most items a bundle may contain for a given total input worth, before the
    * candidate's own stack limit is applied on top. See {@link #BUNDLE_CURVE}.
    *
    * <p>Always at least 1: any trade the shop accepts at all hands back something.
    */
   public static int bundleCap(long inputWorth) {
      if (inputWorth <= 0L) {
         return 1;
      }
      long cap = (long)Math.floor(Math.sqrt(inputWorth / BUNDLE_CURVE));
      return (int)Math.max(1L, Math.min(MAX_RETURN_QTY, cap));
   }

   /**
    * 3.0.1 - the most of THIS candidate a single trade may hand over.
    *
    * <p>Two independent ceilings, whichever is lower: how much worth was handed in (the bundle
    * curve) and whether the item is something you can only hold one of. The gear test is the real
    * stack size first - vanilla already marks every sword, axe, bow, tool and piece of armour as
    * unstackable - with {@link ItemFeatures#isWeapon} as a named second opinion, so the intent
    * survives a modded item that lies about its stack size.
    */
   private static int quantityCeiling(TradeAdvisor.Candidate candidate, int bundleCap) {
      if (candidate.maxStack() <= 1 || ItemFeatures.isWeapon(candidate.itemId())) {
         return 1;
      }
      return Math.max(1, Math.min(bundleCap, Math.min(candidate.maxStack(), MAX_RETURN_QTY)));
   }

   public static TradeAdvisor.Verdict analyze(Map<Item, Integer> offered, long inputWorth, String forcedTarget, List<TradeAdvisor.Candidate> candidates) {
      if (offered.isEmpty() || inputWorth <= 0L) {
         return new TradeAdvisor.Verdict(false, "", 0, inputWorth, 0.0, "Put an item in the trade slots first so I can appraise it.");
      }

      long budget = budgetFor(inputWorth);
      int totalCount = 0;
      for (int count : offered.values()) {
         totalCount += Math.max(0, count);
      }
      double inputUnitWorth = totalCount > 0 ? (double)inputWorth / totalCount : inputWorth;
      int inputTier = maxTier(offered);
      String inputArchetype = dominantArchetype(offered);

      List<TradeAdvisor.Candidate> pool = new ArrayList<>();
      if (forcedTarget != null && !forcedTarget.isEmpty()) {
         for (TradeAdvisor.Candidate candidate : candidates) {
            if (candidate.itemId().equals(forcedTarget)) {
               pool.add(candidate);
            }
         }
      }
      if (pool.isEmpty()) {
         pool.addAll(candidates);
      }
      if (pool.isEmpty()) {
         return new TradeAdvisor.Verdict(false, "", 0, inputWorth, 0.0, "The shop is empty right now — restock a category first.");
      }

      TradeAdvisor.Candidate best = null;
      int bestQty = 0;
      int bestCeiling = 1;
      double bestScore = -1.0;
      long bestValue = 0L;
      int cheapestUnit = Integer.MAX_VALUE;

      // 3.0.1 - worth handed in, not stacks handed in, is what decides how big a bundle can get
      int bundleCap = bundleCap(inputWorth);

      for (TradeAdvisor.Candidate candidate : pool) {
         int unit = candidate.unitWorth();
         if (unit <= 0) {
            continue;
         }
         cheapestUnit = Math.min(cheapestUnit, unit);

         int ceiling = quantityCeiling(candidate, bundleCap);

         /*
          * Only the few quantities that can possibly sit closest to the budget are worth testing -
          * the ideal count and its neighbours. Scanning 1..64 for every candidate would give the
          * same answer for 64x the work.
          *
          * 3.0.1 - the ideal is clamped to the candidate's ceiling before the scan rather than after
          * the winner is picked. That matters: a cheap item can no longer be scored as if it could
          * soak up the whole budget, so an expensive budget stops matching a mountain of bread and
          * starts matching stock that is actually worth it.
          */
         int ideal = (int)Math.max(1L, Math.min(ceiling, Math.round((double)budget / unit)));
         for (int qty = Math.max(1, ideal - 1); qty <= Math.min(ceiling, ideal + 1); qty++) {
            long value = (long)qty * unit;
            if (value > budget * OVERSHOOT_TOLERANCE) {
               continue;
            }

            double totalFit = 1.0 - Math.min(1.0, Math.abs(value - budget) / (double)Math.max(1L, budget));
            double unitFit = 1.0 - Math.min(1.0, Math.abs(Math.log1p(unit) - Math.log1p(inputUnitWorth)) / UNIT_LOG_SPAN);
            double score = TOTAL_FIT_WEIGHT * totalFit + UNIT_FIT_WEIGHT * unitFit;

            if (score > bestScore) {
               bestScore = score;
               best = candidate;
               bestQty = qty;
               bestValue = value;
               bestCeiling = ceiling;
            }
         }
      }

      if (best == null) {
         String why = cheapestUnit == Integer.MAX_VALUE
            ? "The shop is empty right now — restock a category first."
            : "Not enough value on the table. The cheapest thing I have is worth " + cheapestUnit
               + "c and you've only offered " + budget + "c of goods — add more.";
         return new TradeAdvisor.Verdict(false, "", 0, inputWorth, 0.0, why);
      }

      long change = Math.max(0L, budget - bestValue);
      boolean stepUp = ItemFeatures.tier(best.itemId()) >= inputTier;
      boolean sameKind = ItemFeatures.archetype(best.itemId()).equals(inputArchetype);

      double confidence = clamp01(
         0.50 * bestScore
            + 0.50 * clamp01(0.5 + 0.5 * NET.score(new double[]{
               Math.min(1.0, (double)bestValue / Math.max(1L, budget)),
               clamp(((double)ItemFeatures.tier(best.itemId()) - inputTier) / 4.0),
               sameKind ? 1.0 : 0.0,
               ItemFeatures.tier(best.itemId()) / 5.0,
               1.0 - Math.min(1.0, (double)bestValue / Math.max(1L, budget)),
               Math.min(bestQty, 16) / 16.0
            }))
      );

      String name = ItemFeatures.pretty(best.itemId());
      String take = bestQty > 1 ? bestQty + "x " + name : "a " + name;
      /*
       * 3.0.1 - when the bundle stopped at its ceiling rather than at the budget, say which ceiling
       * it was. "Why did 64 logs only buy 8 bread" is a fair question and the answer should be on
       * screen, not in a changelog.
       */
      String capNote = "";
      if (change > 0L && bestQty >= bestCeiling) {
         capNote = bestCeiling == 1 && (best.maxStack() <= 1 || ItemFeatures.isWeapon(best.itemId()))
            ? " Gear goes out one at a time, so the rest is change."
            : " That's the most " + name + " this much value carries — the rest is change.";
      }
      String reasoning = "Your goods are worth " + inputWorth + "c, so I'll trade " + budget + "c of stock: "
         + take + " at " + bestValue + "c"
         + (change > 0L ? " plus " + change + "c change." : ".")
         + capNote
         + (stepUp ? " Same value bracket or better than what you handed me." : " Best match for that value on the board.")
         + String.format(" (confidence %.0f%%)", confidence * 100.0);

      return new TradeAdvisor.Verdict(true, best.itemId(), bestQty, inputWorth, confidence, reasoning);
   }

   private static double clamp(double value) {
      return Math.max(-1.0, Math.min(1.0, value));
   }

   private static double clamp01(double value) {
      return Math.max(0.0, Math.min(1.0, value));
   }

   private static String dominantArchetype(Map<Item, Integer> offered) {
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (Entry<Item, Integer> entry : offered.entrySet()) {
         String id = BuiltInRegistries.ITEM.getKey(entry.getKey()).toString();
         counts.merge(ItemFeatures.archetype(id), entry.getValue(), Integer::sum);
      }

      String dominant = "utility";
      int best = -1;
      for (Entry<String, Integer> entry : counts.entrySet()) {
         if (entry.getValue() > best) {
            best = entry.getValue();
            dominant = entry.getKey();
         }
      }
      return dominant;
   }

   private static int maxTier(Map<Item, Integer> offered) {
      int tier = 1;
      for (Item item : offered.keySet()) {
         tier = Math.max(tier, ItemFeatures.tier(BuiltInRegistries.ITEM.getKey(item).toString()));
      }
      return tier;
   }

   static {
      NET.encourageFeature(0, 1.4);
      NET.encourageFeature(1, 1.1);
      NET.encourageFeature(2, 0.6);
      NET.encourageFeature(3, 0.8);
      NET.encourageFeature(4, -0.7);
   }

   /**
    * One thing the shop currently has on the shelf, valued at its true worth.
    *
    * <p>3.0.1 - {@code maxStack} is the item's real vanilla stack limit, passed in by the server
    * because this package has no access to the item registry. It is the authoritative half of the
    * "gear comes back one at a time" rule; see {@link #quantityCeiling}.
    */
   public record Candidate(String itemId, int unitWorth, int maxStack) {
   }

   public record Verdict(boolean approved, String resultItemId, int quantity, long inputValue, double confidence, String reasoning) {
   }
}

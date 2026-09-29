package com.merchantmind.shop;

import com.merchantmind.MerchantMind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;

public final class ShopManager {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

   /* ------------------------------------------------------------------ *
    *  3.0.0 - where the shop is saved, and why it moved.
    *
    *  Up to and including 2.9.2 this was ONE constant path:
    *
    *      FabricLoader.getConfigDir()/merchantmind_shop.json
    *
    *  config/ belongs to the game installation, not to a world, so every
    *  world in the same instance opened, wrote to and overwrote the SAME
    *  shop: stock, prices, both restock clocks and every player's coin
    *  balance were global. Buying something in world A changed world B's
    *  shelves; a restock started in one world was still running in the next;
    *  and deleting a world left the surviving worlds holding whatever state
    *  the deleted one happened to write last. None of that is recoverable
    *  after the fact, because there was only ever one file.
    *
    *  The shop is world state, so it now lives with the world:
    *
    *      <save>/data/merchantmind_shop.json
    *
    *  data/ is the directory Minecraft already uses for per-world saved
    *  data, and it is copied, renamed, backed up and deleted along with the
    *  world - which is exactly the lifetime this file should have had.
    *  Consequently the path is no longer a constant: it does not exist until
    *  a server (integrated or dedicated) opens a world. See openWorld.
    * ------------------------------------------------------------------ */
   private static final String SAVE_NAME = "merchantmind_shop.json";
   /**
    * Set by {@link #openWorld}, cleared by {@link #closeWorld}. Null means no world is open, and
    * every save is a no-op - there is nowhere legitimate to write to.
    */
   private static Path saveFile;
   /**
    * The pre-3.0.0 shared file, and before that the one written under the old "Quest Mod" name.
    * Read at most ONCE, into the first world opened after the update, and then renamed - see
    * {@link #importSharedSaveOnce}.
    *
    * <p>3.0.1 - resolved on demand rather than in a static initialiser. {@code getConfigDir()} needs
    * a live Fabric loader, and holding it in a constant meant this class could not even be loaded
    * outside a launched game - which made the value model impossible to exercise from a harness.
    */
   private static Path[] sharedLegacyFiles() {
      Path configDir = FabricLoader.getInstance().getConfigDir();
      return new Path[]{configDir.resolve(SAVE_NAME), configDir.resolve("questmod_shop.json")};
   }
   private static final Random RANDOM = new Random();
   /* ------------------------------------------------------------------ *
    *  2.2 (2.7.0) - restock timing.
    *
    *  How long a refill takes, and it is now the SAME number for both ways
    *  of starting one: the shop's own schedule and a player pressing
    *  Restock. That is what "manual uses the normal time, same as auto"
    *  means - pressing the button no longer buys a different duration, it
    *  only starts the clock early.
    *
    *  It was 3 seconds through 2.6.0, which was fine when the wait was just
    *  a formality after paying iron. It cannot stay 3 now: the whole point
    *  of the new flow is that the wait is real, visible as a big countdown,
    *  and that 4 iron is an OPTIONAL shortcut through it. A 3 second wait
    *  has nothing worth skipping and no countdown worth reading.
    *
    *  2.9.0 - 45 -> 75. Same reasoning taken one step further. At 45 seconds
    *  the free route was so cheap that the iron shortcut had nothing to sell:
    *  waiting out a whole category cost less than walking back to a chest,
    *  so 4 iron was never worth spending and restocking stopped being a
    *  decision at all.
    *
    *  2.9.1 - 75 -> 300 (five minutes), on request, and this is the number
    *  that finally makes iron the point of the whole mechanic: no iron means
    *  that category is SHUT for five real minutes and you buy nothing from it
    *  in the meantime. Buying is locked for the entire refill (see
    *  isRestockBusy), so the choice is now "pay 4 iron and shop now" or "come
    *  back in five minutes" - which is what iron is supposed to be for.
    *
    *  2.9.2 - this value STAYS at 300. What was wrong at 2.9.1 was the other
    *  half of the cycle: the automatic schedule was left at 2-5 minutes while
    *  this went to 5, so an unfed category was shut LONGER than it was open
    *  (120-300s open against a flat 300s closed - as little as 29% open in the
    *  worst roll). The fix is the one this note already pointed at: raise
    *  AUTO_RESTOCK_MIN/SPREAD, never lower this. See those constants for the
    *  new ratio.
    *
    *  3.0.0 - 300 -> 150 (2m 30s). Five minutes was correct in principle and
    *  too long in practice: it is longer than most players will stand still
    *  for, so the honest choice stopped being "pay iron or wait" and became
    *  "pay iron or go do something else". Halving it is the whole change, and
    *  150 is chosen rather than a rounder 120 or 180 for three reasons:
    *
    *    - it is exactly half of 300, so the pacing is trivially comparable
    *      with the version this replaces, and trivially reversible;
    *    - it is twice the 75 seconds that 2.9.1 recorded as measurably too
    *      short to give the iron shortcut anything to sell, so the shortcut
    *      keeps its value: 2m 30s is well past a chest round trip (~30-60s),
    *      which is the only thing 4 iron is competing with;
    *    - it is short enough to wait out at a bench or a furnace, which is
    *      what makes the free route a real option again rather than a
    *      punishment.
    *
     *  The AUTO schedule below was rescaled by the same factor in the same
     *  commit, so the open:closed ratio is IDENTICAL to 2.9.2 - only the clock
     *  speed changed. If this moves again, move MIN/SPREAD with it or the
     *  balance 2.9.2 established is lost.
     *
     *  3.1.0 - the single value SPLIT IN TWO, on request. Every build up to
     *  3.0.4 refilled for the same 150s however the refill was started; the
     *  duration now depends on WHO started it, and that dependency is the
     *  whole of this release:
     *
     *      AUTO   (scheduled, or the last listing sold out)   60s
     *      MANUAL (a player pressed the Restock button)      180s
     *
     *  The pairing is deliberate and it is the reverse of what it looks like.
     *  An automatic refill is something the shop DOES TO the player: they did
     *  not ask for it, they cannot see it coming, and it locks buying while it
     *  runs, so it should be over almost before it is noticed - one minute.
     *  A manual refill is something the player CHOOSES, in full knowledge that
     *  buying is locked for the duration, to throw away the stock currently on
     *  the shelves and roll new stock. That is a reroll, and a reroll has to
     *  cost something or it is free and infinite: three minutes is the price,
     *  and the 4-iron shortcut is the way out of it. Making the player-driven
     *  path the LONGER one is what keeps "press Restock until the shelves look
     *  good" from being the optimal way to play.
     *
     *  Both numbers are within the range this file has already argued about:
     *  60s is short but it is no longer the closed half of a bargain (nothing
     *  is being bought with it), and 180s sits between the 150 of 3.0.0 and
     *  the 300 of 2.9.1 that were both judged to give the iron shortcut real
     *  value.
     *
      *  The AUTO schedule below was deliberately NOT rescaled - see the note
      *  there for what that does to the open:closed ratio and why.
      *
      *  3.3.0 - both numbers move, and the SELECTOR moves with them, on
      *  request: "open 2 minutes, auto refill 30s, manual or sold out 1
      *  minute". So:
      *
      *      SCHEDULED (the shop's own timer came due)    30s
      *      MANUAL    (a player pressed Restock)         60s
      *      SOLD_OUT  (the last listing was bought)      60s
      *
      *  The change that is NOT a number is the third line. Up to 3.2.0 the
      *  duration was chosen by one boolean, "did a player press the button",
      *  and SOLD_OUT was lumped in with the schedule because nobody had asked
      *  for it. That is the wrong grouping: a sold-out shelf is not the shop
      *  deciding to close, it is the shop being emptied BY the players, and it
      *  arrives exactly when demand is highest. So the selector is now the
      *  trigger itself ({@link RestockReason#triggered}) and the two refills
      *  that DEMAND caused - one player asking, or players buying the shelf
      *  out - share the longer clock, while the one nobody caused is the short
      *  one.
      *
      *  Why the scheduled refill is the short one is unchanged from 3.1.0 and
      *  is now sharper: it is done TO the player, unasked and unforeseen, and
      *  it locks buying, so it should be over before it is a nuisance. 30s is
      *  half of the 60s that 3.2.0 used for it, which is as far down as this
      *  can go and still leave the iron shortcut something to sell.
      *
      *  The manual clock came DOWN, 180 -> 60, and that is a real loosening:
      *  a reroll now costs one minute rather than three. It is still twice the
      *  scheduled refill, so pressing Restock is still the expensive way to get
      *  new shelves, and it is still the clock the 2-iron shortcut is worth
      *  paying to skip.
      * ------------------------------------------------------------------ */
   public static final int SCHEDULED_RESTOCK_DURATION_SECONDS = 30;
   public static final int TRIGGERED_RESTOCK_DURATION_SECONDS = 60;

   /**
    * 3.1.0 - the one place that turns "what started this refill" into "how long it takes". Every
    * caller that needs a duration goes through here - {@link #beginRestock}, the save clamp in
    * {@link #load}, the client's progress bar - so the two numbers above cannot end up applied
    * inconsistently.
    *
    * <p>3.3.0 - it takes the {@link RestockReason} rather than a boolean. The boolean could only
    * ever answer "was it the button", which stopped being the question the moment SOLD_OUT needed
    * the same clock as the button without being the button; the reason is the trigger itself, it is
    * already saved with the category and already sent to the client, so nothing had to be added
    * anywhere to ask this properly.
    *
    * @param reason the trigger of the refill in question. {@link RestockReason#NONE} - a category
    *               that is not restocking, or an old save with no reason recorded - is treated as
    *               scheduled, which is the shorter of the two and therefore the safe default.
    */
   public static int restockDurationSeconds(ShopManager.RestockReason reason) {
      return reason.triggered ? TRIGGERED_RESTOCK_DURATION_SECONDS : SCHEDULED_RESTOCK_DURATION_SECONDS;
   }

   /**
    * The refill duration as text, for chat lines and the Help tab: "60s" is a number, "1m" is a
    * length of time. Both built once, since they can only change when the constants above do.
    */
   private static final String SCHEDULED_RESTOCK_DURATION_LABEL = formatSeconds(SCHEDULED_RESTOCK_DURATION_SECONDS);
   private static final String TRIGGERED_RESTOCK_DURATION_LABEL = formatSeconds(TRIGGERED_RESTOCK_DURATION_SECONDS);

   /**
    * 3.1.0 - takes the same argument as {@link #restockDurationSeconds} rather than reading a
    * global, so a message about one category's refill can never print the other clock's length.
    * There is deliberately no no-argument version: since 3.1.0 there is no such thing as "the"
    * refill duration, and a caller that does not know which one it is talking about has a bug.
    */
   public static String restockDelayLabel(ShopManager.RestockReason reason) {
      return reason.triggered ? TRIGGERED_RESTOCK_DURATION_LABEL : SCHEDULED_RESTOCK_DURATION_LABEL;
   }

   /**
    * 3.2.0 - how often the automatic refill comes round, as text: "1m", or "2m-3m" if a spread is
    * ever put back.
    *
    * <p>This exists because the Help tab was building the range itself out of the two constants, with
    * integer division by 60 - so the moment the spread went to 0 it read "every 1-1min", and any
    * interval that was not a whole number of minutes would have read "every 0-0min". The label is
    * the manager's business, not the screen's: only the manager knows whether the interval is a range
    * at all, and {@link #rollAutoInterval} is the only thing entitled to say what it draws from.
    */
   public static String autoIntervalLabel() {
      return AUTO_RESTOCK_SPREAD_SECONDS <= 0
         ? formatSeconds(AUTO_RESTOCK_MIN_SECONDS)
         : formatSeconds(AUTO_RESTOCK_MIN_SECONDS) + "-" + formatSeconds(AUTO_RESTOCK_MIN_SECONDS + AUTO_RESTOCK_SPREAD_SECONDS);
   }

   /** "45s" / "5m" / "5m 30s". The screen has its own copy of this for its per-frame countdowns. */
   private static String formatSeconds(int seconds) {
      int s = Math.max(0, seconds);
      if (s < 60) {
         return s + "s";
      }
      int rest = s % 60;
      return rest == 0 ? s / 60 + "m" : s / 60 + "m " + rest + "s";
   }
   /**
    * Iron that finishes an in-progress restock instantly. Optional - never required to start one.
    *
    * <p>3.2.0 - 4 -> 2, on request. Nothing else had to move with it: every message, the Submit
    * button's enable check, the server's own check before it spends the stack, and the Help tab all
    * read this constant, so the number appears exactly once in the mod.
    *
    * <p>It is worth noting what this does to the deal, because the refill it buys out also got
    * shorter in this release. At 3.1.0 it was 4 iron to skip up to 150s of automatic downtime, i.e.
    * ~37s of lock per ingot. It is now 2 iron for up to 60s, i.e. ~30s per ingot - so the shortcut
    * is slightly better value per ingot AND cheaper to reach, which is the point: 2 ingots is
    * something a player has in their pocket, 4 was a trip to a chest.
    */
   public static final int RESTOCK_IRON_COST = 2;
   /* ------------------------------------------------------------------ *
    *  2.2 - a category never asks for a restock sooner than MIN. The real
    *  wait is MIN + rand(0..SPREAD), rolled fresh every cycle, so no two
    *  categories stay in sync.
    *
    *  This is the OPEN half of a category's life: it only counts down while
    *  the category is idle and buyable (see tickOncePerSecond), and it is
    *  re-rolled at the end of every refill. The CLOSED half is the refill
    *  itself, during which buying is locked - flat until 3.0.4, and since
    *  3.3.0 SCHEDULED_RESTOCK_DURATION_SECONDS for the refills this timer
    *  starts (the other two triggers run the longer clock).
    *
    *  2.9.2 - 120/180 -> 300/180. At 120/180 against a 300s refill the shop
    *  was shut more than it was open on every roll below 300s (open:closed
    *  ran from 0.40:1 up to 1.00:1, averaging 0.70:1 - only 41% of the cycle
    *  open). Moving MIN up to the refill duration makes the open stretch at
    *  least as long as the closed one in the WORST roll and longer in every
    *  other:
    *
    *      open 300-480s (avg 390s) vs closed 300s
    *      open:closed  worst 1.00:1   avg 1.30:1   best 1.60:1
    *      share open   worst 50%      avg 56.5%    best 61.5%
    *
    *  3.0.0 - 300/180 -> 150/90. RESTOCK_DELAY_SECONDS was halved to 150, so
    *  both of these are halved too: the invariant being preserved is not the
    *  numbers, it is MIN == RESTOCK_DELAY_SECONDS and SPREAD == 0.6 * MIN,
    *  which is what produces the ratio. Every ratio below is therefore
    *  unchanged from 2.9.2 - only the wall-clock length of a cycle moved:
    *
    *      open 150-240s (avg 195s) vs closed 150s
    *      open:closed  worst 1.00:1   avg 1.30:1   best 1.60:1
    *      share open   worst 50%      avg 56.5%    best 61.5%
    *
    *  A full cycle is now 300-390s instead of 600-780s, so a player sees
    *  roughly twice as many restocks per session at the same balance.
    *
    *  SPREAD stays at 180 so the categories still drift apart instead of
    *  refilling in lockstep. The 4-iron shortcut is untouched and only gets
    *  better: it now cuts into a closed stretch that is never the majority
    *  of the cycle.
    *
    *  3.1.0 - these STAY at 150/90, and that is a change of policy worth
    *  stating outright, because the note above says to move them whenever the
    *  refill duration moves. The refill duration did move - the automatic one
    *  is now 60s - and these did not follow it, so the invariant
    *  MIN == refill duration is broken on purpose. What happens to the cycle:
    *
    *      open 150-240s (avg 195s) vs closed 60s
    *      open:closed  worst 2.50:1   avg 3.25:1   best 4.00:1
    *      share open   worst 71%      avg 76.5%    best 80%
    *
    *  against 50/56.5/61.5% before. A category is open three quarters of the
    *  time instead of somewhat over half, so the shop is materially more
    *  available - which is exactly what a one-minute automatic refill MEANS,
    *  and the reason it was asked for. Rescaling MIN/SPREAD down to 60/36 to
    *  hold the old ratio would have restored the 56.5% by making the shop shut
    *  itself every minute, i.e. it would have undone the request. If the goal
    *  ever becomes the ratio again rather than the availability, 60/36 is the
    *  pair to use, not 150/90.
    *
    *  Note these govern the INTERVAL BETWEEN automatic refills, not their
    *  length: nothing here was retimed, the closed half just got shorter.
    *
    *  3.2.0 - 150/90 -> 60/0, on request: "open one minute, restock one
    *  minute". That is a flat 1:1 cycle, which is the balance 2.9.2 fought
    *  for (open never shorter than closed) taken to its exact boundary:
    *
    *      open 60s (no spread) vs closed 60s
    *      open:closed  1.00:1 every cycle, share open exactly 50%
    *
    *  SPREAD going to 0 is the part that needs care, and it is the reason
    *  rollFirstAutoInterval() below exists. The spread was never about the
    *  length of the open stretch - it was there so eleven categories would not
    *  all refill on the same second. With a fixed 60s interval every category
    *  re-arms for exactly 60s at the end of its own refill, so the offsets
    *  between them are PRESERVED forever once they exist - but a fresh world
    *  starts all eleven timers on the same tick, and without a spread to
    *  scatter them they would stay locked together and the entire shop would
    *  go dark for a minute at a time. So the scatter moved from every cycle to
    *  the first one only: see rollFirstAutoInterval().
    *
    *  3.3.0 - 60/0 -> 120/0, on request: "open the shop 2 minutes". Against
    *  the 30s scheduled refill this is the most available the shop has ever
    *  been:
    *
    *      open 120s (no spread) vs closed 30s
    *      open:closed  4.00:1 every cycle, share open exactly 80%
    *
    *  against exactly 50% in 3.2.0. The whole cycle is 150s, i.e. shorter than
    *  the 3.0.4 refill alone, so a player sees a category cycle more often AND
    *  spends four fifths of it able to buy.
    *
    *  SPREAD stays 0 and rollFirstAutoInterval() below now draws 1..120 by
    *  reading MIN, so nothing else had to move: the scatter is still one partial
    *  first cycle per category, which is all a fixed interval needs.
    *
    *  Note this is the interval between SCHEDULED refills only. The other two
    *  triggers do not wait for it - a sold-out shelf refills the moment it
    *  empties and a player can press Restock at any point in the 120s - so the
    *  80% figure is the ceiling a category reaches when nobody touches it, not
    *  a promise.
    * ------------------------------------------------------------------ */
   public static final int AUTO_RESTOCK_MIN_SECONDS = 120;
   public static final int AUTO_RESTOCK_SPREAD_SECONDS = 0;
   private static final int LISTINGS_PER_CATEGORY = 6;
   public static final Set<String> TRADE_BLOCKED = Set.of(idOf(Items.ENDER_PEARL), idOf(Items.WIND_CHARGE));

   /* ------------------------------------------------------------------ *
    *  4.x - economy
    *
    *  Every item has exactly ONE number: its worth in coins. Buy and sell
    *  prices are derived from it, which is what keeps the spread sane:
    *
    *     buy  = round(worth * BUY_MARKUP)     shop sells to you above worth
    *     sell = round(worth * SELL_RATE)      shop buys from you below worth
    *
    *  buy is therefore always ~2.8x sell and can never be undercut, so
    *  "buy low, sell high" arbitrage is impossible by construction.
    *  The old code priced a whole CATEGORY with one number, which is why
    *  netherite boots and leather boots both cost 45c.
    * ------------------------------------------------------------------ */
   private static final double BUY_MARKUP = 1.25;
   private static final double SELL_RATE = 0.45;
   /* ------------------------------------------------------------------ *
    *  1.2 (2.9.0) - the tables, and why they are the types they are.
    *
    *  All four used to be LinkedHashMaps. A LinkedHashMap carries two extra
    *  references per entry to maintain an insertion order that only one of
    *  them ever actually needed, and the two keyed by Category paid a hash
    *  bucket array plus an Entry object per category for a key that is an
    *  enum - which is exactly what EnumMap exists to avoid: it is one flat
    *  array indexed by ordinal, no hashing, no entries, no iterator garbage.
    *
    *  WORTH is read by key only, never iterated in order, so a plain HashMap
    *  is strictly cheaper; sizing it up front also stops it rehashing its way
    *  through four table copies while ~250 entries are registered.
    * ------------------------------------------------------------------ */
   /**
    * Category.values() hands out a fresh clone of the backing array on every call, and this class
    * walks it several times a second - the restock tick, the iron mask, every sync. Nothing mutates
    * it, so one private copy is shared.
    */
   private static final ShopManager.Category[] CATEGORIES = ShopManager.Category.values();
   /** Worth in coins of one unit of an item. */
   private static final Map<String, Integer> WORTH = new HashMap<>(512);
   /** What each category can stock, as a plain array: no list object and no spare capacity. */
   private static final Map<ShopManager.Category, String[]> POOLS = new EnumMap<>(ShopManager.Category.class);
   private static final Map<ShopManager.Category, ShopManager.CategoryState> STATE = new EnumMap<>(ShopManager.Category.class);
   private static final Map<UUID, Long> BALANCES = new HashMap<>();
   /**
    * 1.2 (2.9.0) - scratch for {@link #rollCategory}'s shuffle, sized once to the largest pool and
    * reused. Restocking used to copy a whole pool into a fresh ArrayList and shuffle that, for every
    * category, every refill. Server thread only, and never read outside that method.
    */
   private static int[] shuffleOrder = new int[0];
   /**
    * 1.2 (2.9.0) - bumped whenever anything the clients can see changes: stock, restock state, or
    * either countdown. The server builds the sync rows once per version and hands the same
    * immutable list to every player, instead of building an identical one per player per second.
    */
   private static int version;
   private static int tickCounter = 0;

   private ShopManager() {
   }

   /**
    * Mod init: builds the parts of the shop that are the same in every world.
    *
    * <p>3.0.0 - this no longer loads anything. The worth table, the item pools and the shuffle
    * scratch are properties of the mod, so they are built once at launch; stock, prices, clocks and
    * balances are properties of a <em>world</em>, so they are built in {@link #openWorld} and torn
    * down in {@link #closeWorld}. Before 3.0.0 both halves happened here, which is precisely how one
    * shop ended up shared by every world the player owned - by the time the first world opened, its
    * state had already been read from a path that had nothing to do with it.
    */
   public static void init() {
      registerWorth();
      registerPools();

      int widest = 0;
      for (String[] pool : POOLS.values()) {
         widest = Math.max(widest, pool.length);
      }
      shuffleOrder = new int[widest];

      resetState();
   }

   /**
    * 3.0.0 - a world is being opened: bind the save file to <b>that world's</b> directory, throw
    * away whatever the previous world left in memory, and load this world's own shop.
    *
    * <p>Called from {@code SERVER_STARTING}, so it runs before the first server tick and before any
    * player can be connected. In single player this fires once per world opened, which is what
    * makes two worlds in one session independent: the second world resets the tables and reads its
    * own file rather than inheriting the first world's shelves.
    *
    * @param server the server that is starting; only its world directory is used
    */
   public static void openWorld(MinecraftServer server) {
      saveFile = server.getWorldPath(LevelResource.DATA).resolve(SAVE_NAME);
      resetState();
      importSharedSaveOnce();
      load();

      for (ShopManager.Category category : CATEGORIES) {
         ShopManager.CategoryState state = STATE.get(category);
         boolean hadOwnSchedule = state.nextAutoRestockSeconds > 0;
         if (state.listings.isEmpty() && !state.restocking) {
            // note this also re-arms the timer, which is why the scatter below runs AFTER it
            rollCategory(category);
         }
         if (!hadOwnSchedule) {
            /*
             * 3.2.0 - nothing in the save gave this category a schedule, so it has no phase of its
             * own yet. Since AUTO_RESTOCK_SPREAD_SECONDS is 0 every later cycle is exactly
             * AUTO_RESTOCK_MIN_SECONDS long, which preserves whatever offsets exist but creates
             * none - so if all eleven started here on the same tick they would refill in lockstep
             * forever and the whole shop would go dark together. A partial first cycle scatters
             * them once, permanently.
             */
            state.nextAutoRestockSeconds = rollFirstAutoInterval();
         }
      }

      MerchantMind.LOGGER.info("[merchantmind] shop bound to {}", saveFile);
   }

   /**
    * 3.0.0 - the world is closing: write it out, unbind, and wipe memory.
    *
    * <p>The wipe is the important half. Without it, closing world A and opening world B in the same
    * session would leave A's listings and balances sitting in the static tables for however long it
    * takes B to overwrite them - and any category B's file does not mention would keep A's stock
    * forever.
    */
   public static void closeWorld() {
      save();
      saveFile = null;
      resetState();
   }

   /** Fresh, empty, unloaded shop state for every category, and no balances. */
   private static void resetState() {
      for (ShopManager.Category category : CATEGORIES) {
         STATE.put(category, new ShopManager.CategoryState());
      }
      BALANCES.clear();
      version++;
   }

   /**
    * 3.0.0 - one-time rescue of the pre-3.0.0 shared file, so updating does not silently delete a
    * long-running single-world shop.
    *
    * <p>It is deliberately a <b>move, not a copy</b>: the old file is adopted by the first world
    * opened after the update and then renamed to {@code .imported}, so the second world opened gets
    * a clean shop rather than a second copy of the first one's. Copying it into every world would
    * technically preserve the data and would completely defeat the point of this release - every
    * world would still start life holding the same stock, prices and balances.
    */
   private static void importSharedSaveOnce() {
      if (saveFile == null || Files.exists(saveFile)) {
         return;
      }

      for (Path legacy : sharedLegacyFiles()) {
         if (!Files.exists(legacy)) {
            continue;
         }
         try {
            Files.createDirectories(saveFile.getParent());
            Files.copy(legacy, saveFile);
            Path retired = legacy.resolveSibling(legacy.getFileName() + ".imported");
            Files.move(legacy, retired, StandardCopyOption.REPLACE_EXISTING);
            MerchantMind.LOGGER
               .info("[merchantmind] adopted the old shared shop file {} into this world and retired it as {}", legacy, retired.getFileName());
         } catch (IOException error) {
            MerchantMind.LOGGER.warn("[merchantmind] could not import the old shared shop file {} - this world starts fresh", legacy, error);
         }
         return;
      }
   }

   /**
    * How long this category stays open before it refills itself.
    *
    * <p>2.2 - always at least AUTO_RESTOCK_MIN_SECONDS, rarely the same twice in a row.
    *
    * <p>3.2.0 - with SPREAD at 0 this is now a flat AUTO_RESTOCK_MIN_SECONDS every time. The
    * expression is left as it is rather than simplified to the constant so that putting a spread back
    * is a one-character change, and so the two constants still describe the interval together.
    *
    * <p>3.3.0 - that flat interval is 120s, which is what "open the shop 2 minutes" means. Nothing
    * here changed; it reads the constant.
    */
   private static int rollAutoInterval() {
      return AUTO_RESTOCK_MIN_SECONDS + RANDOM.nextInt(AUTO_RESTOCK_SPREAD_SECONDS + 1);
   }

   /**
    * 3.2.0 - a category's FIRST open stretch, used only when nothing has given it a schedule yet.
    *
    * <p>A partial cycle, 1..AUTO_RESTOCK_MIN_SECONDS (so 1..120 as of 3.3.0), where every cycle after
    * it is the full flat interval. This is the only randomness left in the schedule and it exists for
    * exactly one
    * reason: with no spread, eleven categories armed on the same tick would refill on the same
    * second forever, closing the entire shop at once. Scattering the first cycle gives each category
    * a phase, and because the interval is fixed that phase is then preserved for the rest of the
    * world's life.
    *
    * @see #AUTO_RESTOCK_SPREAD_SECONDS
    */
   private static int rollFirstAutoInterval() {
      return 1 + RANDOM.nextInt(AUTO_RESTOCK_MIN_SECONDS);
   }

   /**
    * 1.2 (2.9.0) - builds one category's pool as an exactly-sized {@code String[]}.
    *
    * <p>Duplicates are still dropped (hopper is in Utility and Redstone, elytra in Utility and
    * Rare), and every id is interned so that the copy held by the pool, the copy used as a key in
    * the worth table and the copy carried by a live listing are all the same object rather than
    * three identical strings.
    */
   private static void pool(ShopManager.Category category, Item... items) {
      String[] ids = new String[items.length];
      int size = 0;

      next:
      for (Item item : items) {
         String id = idOf(item).intern();
         for (int i = 0; i < size; i++) {
            if (ids[i].equals(id)) {
               continue next;
            }
         }
         if (!WORTH.containsKey(id)) {
            MerchantMind.LOGGER.warn("[merchantmind] {} has no worth entry - falling back to {}c", id, FALLBACK_WORTH);
         }
         ids[size++] = id;
      }

      POOLS.put(category, size == ids.length ? ids : Arrays.copyOf(ids, size));
   }

   /* ---- price maths ---- */

   private static final int FALLBACK_WORTH = 8;

   public static int worthOf(Item item) {
      return WORTH.getOrDefault(idOf(item), FALLBACK_WORTH);
   }

   public static int worthOf(String itemId) {
      return WORTH.getOrDefault(itemId, FALLBACK_WORTH);
   }

   public static int buyPriceFor(String itemId) {
      return Math.max(2, (int)Math.round(worthOf(itemId) * BUY_MARKUP));
   }

   /** Always strictly below the buy price - the shop never pays more than it charges. */
   public static int sellPriceFor(String itemId) {
      int sell = (int)Math.round(worthOf(itemId) * SELL_RATE);
      return Math.max(1, Math.min(sell, buyPriceFor(itemId) - 1));
   }

   public static int sellValueFor(Item item) {
      return sellPriceFor(idOf(item));
   }

   private static void registerPools() {
      pool(
         ShopManager.Category.WEAPON,
         Items.WOODEN_SWORD,
         Items.STONE_SWORD,
         Items.IRON_SWORD,
         Items.GOLDEN_SWORD,
         Items.DIAMOND_SWORD,
         Items.NETHERITE_SWORD,
         Items.BOW,
         Items.CROSSBOW,
         Items.TRIDENT,
         Items.MACE,
         Items.ARROW,
         Items.SPECTRAL_ARROW
      );
      pool(
         ShopManager.Category.ARMOR,
         Items.LEATHER_HELMET,
         Items.LEATHER_CHESTPLATE,
         Items.LEATHER_LEGGINGS,
         Items.LEATHER_BOOTS,
         Items.CHAINMAIL_HELMET,
         Items.CHAINMAIL_CHESTPLATE,
         Items.CHAINMAIL_LEGGINGS,
         Items.CHAINMAIL_BOOTS,
         Items.IRON_HELMET,
         Items.IRON_CHESTPLATE,
         Items.IRON_LEGGINGS,
         Items.IRON_BOOTS,
         Items.GOLDEN_HELMET,
         Items.GOLDEN_CHESTPLATE,
         Items.GOLDEN_LEGGINGS,
         Items.GOLDEN_BOOTS,
         Items.DIAMOND_HELMET,
         Items.DIAMOND_CHESTPLATE,
         Items.DIAMOND_LEGGINGS,
         Items.DIAMOND_BOOTS,
         Items.NETHERITE_HELMET,
         Items.NETHERITE_CHESTPLATE,
         Items.NETHERITE_LEGGINGS,
         Items.NETHERITE_BOOTS,
         Items.TURTLE_HELMET,
         Items.SHIELD
      );
      pool(
         ShopManager.Category.TOOLS,
         Items.WOODEN_PICKAXE,
         Items.STONE_PICKAXE,
         Items.IRON_PICKAXE,
         Items.GOLDEN_PICKAXE,
         Items.DIAMOND_PICKAXE,
         Items.NETHERITE_PICKAXE,
         Items.WOODEN_AXE,
         Items.STONE_AXE,
         Items.IRON_AXE,
         Items.DIAMOND_AXE,
         Items.NETHERITE_AXE,
         Items.IRON_SHOVEL,
         Items.DIAMOND_SHOVEL,
         Items.IRON_HOE,
         Items.DIAMOND_HOE,
         Items.SHEARS,
         Items.FLINT_AND_STEEL,
         Items.FISHING_ROD,
         Items.BRUSH,
         Items.SPYGLASS,
         Items.COMPASS,
         Items.CLOCK
      );
      pool(
         ShopManager.Category.THROWABLES,
         Items.GOLDEN_APPLE,
         Items.FIRE_CHARGE,
         Items.SNOWBALL,
         Items.EGG,
         Items.TNT,
         Items.ENDER_EYE,
         Items.FIREWORK_ROCKET,
         Items.TIPPED_ARROW
      );
      pool(
         ShopManager.Category.RESOURCES,
         Items.IRON_INGOT,
         Items.GOLD_INGOT,
         Items.COPPER_INGOT,
         Items.NETHERITE_INGOT,
         Items.NETHERITE_SCRAP,
         Items.DIAMOND,
         Items.EMERALD,
         Items.LAPIS_LAZULI,
         Items.REDSTONE,
         Items.QUARTZ,
         Items.AMETHYST_SHARD,
         Items.COAL,
         Items.CHARCOAL,
         Items.RAW_IRON,
         Items.RAW_GOLD,
         Items.RAW_COPPER,
         Items.IRON_NUGGET,
         Items.GOLD_NUGGET,
         Items.STICK,
         Items.FLINT,
         Items.LEATHER,
         Items.STRING
      );
      pool(
         ShopManager.Category.BLOCKS,
         Items.STONE,
         Items.COBBLESTONE,
         Items.OAK_PLANKS,
         Items.OAK_LOG,
         Items.GLASS,
         Items.BRICKS,
         Items.IRON_BLOCK,
         Items.GOLD_BLOCK,
         Items.DIAMOND_BLOCK,
         Items.EMERALD_BLOCK,
         Items.COPPER_BLOCK,
         Items.NETHERITE_BLOCK,
         Items.REDSTONE_BLOCK,
         Items.LAPIS_BLOCK,
         Items.COAL_BLOCK,
         Items.OBSIDIAN,
         Items.SANDSTONE,
         Items.QUARTZ_BLOCK,
         Items.BOOKSHELF,
         Items.SMOOTH_STONE,
         Items.DEEPSLATE
      );
      pool(
         ShopManager.Category.UTILITY,
         Items.ENDER_PEARL,
         Items.WIND_CHARGE,
         Items.EXPERIENCE_BOTTLE,
         Items.ENDER_CHEST,
         Items.CHEST,
         Items.BARREL,
         Items.HOPPER,
         Items.SHULKER_BOX,
         Items.ELYTRA,
         Items.SADDLE,
         Items.NAME_TAG,
         Items.LEAD,
         Items.BUCKET,
         Items.WATER_BUCKET,
         Items.LAVA_BUCKET,
         Items.MINECART,
         Items.RAIL,
         Items.POWERED_RAIL
      );
      pool(
         ShopManager.Category.FOOD,
         Items.BREAD,
         Items.COOKED_BEEF,
         Items.COOKED_PORKCHOP,
         Items.COOKED_CHICKEN,
         Items.COOKED_MUTTON,
         Items.COOKED_SALMON,
         Items.COOKED_COD,
         Items.BAKED_POTATO,
         Items.CARROT,
         Items.GOLDEN_CARROT,
         Items.APPLE,
         Items.CAKE,
         Items.PUMPKIN_PIE,
         Items.COOKIE,
         Items.MELON_SLICE,
         Items.SWEET_BERRIES,
         Items.HONEY_BOTTLE,
         Items.MILK_BUCKET
      );
      pool(
         ShopManager.Category.REDSTONE,
         Items.REDSTONE,
         Items.REDSTONE_TORCH,
         Items.REPEATER,
         Items.COMPARATOR,
         Items.PISTON,
         Items.STICKY_PISTON,
         Items.OBSERVER,
         Items.DISPENSER,
         Items.DROPPER,
         Items.HOPPER,
         Items.LEVER,
         Items.TRIPWIRE_HOOK,
         Items.DAYLIGHT_DETECTOR,
         Items.TARGET,
         Items.REDSTONE_LAMP,
         Items.NOTE_BLOCK,
         Items.SLIME_BLOCK,
         Items.HONEY_BLOCK
      );
      pool(
         ShopManager.Category.DECORATION,
         Items.TORCH,
         Items.LANTERN,
         Items.SOUL_LANTERN,
         Items.CANDLE,
         Items.GLOWSTONE,
         Items.SEA_LANTERN,
         Items.FLOWER_POT,
         Items.PAINTING,
         Items.ITEM_FRAME,
         Items.CHISELED_BOOKSHELF,
         Items.OAK_SIGN,
         Items.WHITE_BED,
         Items.ARMOR_STAND,
         Items.DECORATED_POT,
         Items.AMETHYST_CLUSTER,
         Items.LODESTONE
      );
      pool(
         ShopManager.Category.RARE,
         Items.NETHERITE_INGOT,
         Items.ENCHANTED_GOLDEN_APPLE,
         Items.TOTEM_OF_UNDYING,
         Items.NETHER_STAR,
         Items.DRAGON_EGG,
         Items.DRAGON_HEAD,
         Items.BEACON,
         Items.CONDUIT,
         Items.HEART_OF_THE_SEA,
         Items.ENCHANTED_BOOK,
         Items.WITHER_SKELETON_SKULL,
         Items.END_CRYSTAL,
         Items.SHULKER_SHELL,
         Items.ELYTRA
      );
   }

   private static void w(Item item, int worth) {
      // interned so this key and the pool entry for the same item are one string, not two
      WORTH.put(idOf(item).intern(), worth);
   }

   /**
    * The single source of truth for the economy: what one unit of an item is actually WORTH.
    *
    * The numbers are derived from crafting cost and how hard the material is to get, using the
    * raw materials as the anchor (iron ingot 10, gold 14, diamond 80, netherite ingot 1000).
    * Armour uses the vanilla recipe cost - helmet 5 units, chestplate 8, leggings 7, boots 4 -
    * so a full netherite set is genuinely expensive and leather stays cheap.
    */
   private static void registerWorth() {
      /* ---- raw materials: the anchors everything else is priced from ---- */
      w(Items.DIRT, 1); w(Items.COBBLESTONE, 1); w(Items.STONE, 2); w(Items.NETHERRACK, 1);
      w(Items.DEEPSLATE, 2); w(Items.SANDSTONE, 2); w(Items.SMOOTH_STONE, 3); w(Items.GLASS, 2);
      w(Items.BRICKS, 8); w(Items.OBSIDIAN, 25); w(Items.OAK_LOG, 4); w(Items.OAK_PLANKS, 1);
      w(Items.STICK, 1); w(Items.FLINT, 2); w(Items.STRING, 3); w(Items.LEATHER, 6);
      w(Items.COAL, 3); w(Items.CHARCOAL, 2);
      w(Items.RAW_COPPER, 3); w(Items.COPPER_INGOT, 5);
      w(Items.RAW_IRON, 6); w(Items.IRON_INGOT, 10); w(Items.IRON_NUGGET, 1);
      w(Items.RAW_GOLD, 8); w(Items.GOLD_INGOT, 14); w(Items.GOLD_NUGGET, 2);
      w(Items.REDSTONE, 4); w(Items.LAPIS_LAZULI, 4); w(Items.QUARTZ, 5); w(Items.AMETHYST_SHARD, 8);
      w(Items.EMERALD, 30); w(Items.DIAMOND, 80);
      w(Items.NETHERITE_SCRAP, 220); w(Items.NETHERITE_INGOT, 1000);

      /* ---- storage blocks: 9x the ingot, minus a small bulk discount ---- */
      w(Items.COAL_BLOCK, 26); w(Items.COPPER_BLOCK, 44); w(Items.IRON_BLOCK, 88);
      w(Items.GOLD_BLOCK, 124); w(Items.REDSTONE_BLOCK, 34); w(Items.LAPIS_BLOCK, 34);
      w(Items.QUARTZ_BLOCK, 20); w(Items.EMERALD_BLOCK, 265); w(Items.DIAMOND_BLOCK, 710);
      w(Items.NETHERITE_BLOCK, 8800); w(Items.BOOKSHELF, 20);

      /* ---- swords: 2 material units + stick ---- */
      w(Items.WOODEN_SWORD, 4); w(Items.STONE_SWORD, 6); w(Items.IRON_SWORD, 22);
      w(Items.GOLDEN_SWORD, 30); w(Items.DIAMOND_SWORD, 165); w(Items.NETHERITE_SWORD, 1180);
      w(Items.BOW, 20); w(Items.CROSSBOW, 45); w(Items.TRIDENT, 600); w(Items.MACE, 900);
      w(Items.ARROW, 2); w(Items.SPECTRAL_ARROW, 6); w(Items.TIPPED_ARROW, 12);

      /* ---- tools: pickaxe/axe 3 units, shovel 1, hoe 2 ---- */
      w(Items.WOODEN_PICKAXE, 6); w(Items.STONE_PICKAXE, 9); w(Items.IRON_PICKAXE, 34);
      w(Items.GOLDEN_PICKAXE, 46); w(Items.DIAMOND_PICKAXE, 245); w(Items.NETHERITE_PICKAXE, 1260);
      w(Items.WOODEN_AXE, 6); w(Items.STONE_AXE, 9); w(Items.IRON_AXE, 34);
      w(Items.DIAMOND_AXE, 245); w(Items.NETHERITE_AXE, 1260);
      w(Items.IRON_SHOVEL, 12); w(Items.DIAMOND_SHOVEL, 85);
      w(Items.IRON_HOE, 22); w(Items.DIAMOND_HOE, 165);
      w(Items.SHEARS, 20); w(Items.FLINT_AND_STEEL, 12); w(Items.FISHING_ROD, 10);
      w(Items.BRUSH, 26); w(Items.SPYGLASS, 26); w(Items.COMPASS, 44); w(Items.CLOCK, 60);

      /* ---- 4.1 - armour, priced per recipe: helmet 5 / chest 8 / legs 7 / boots 4 ---- */
      w(Items.LEATHER_HELMET, 30); w(Items.LEATHER_CHESTPLATE, 48);
      w(Items.LEATHER_LEGGINGS, 42); w(Items.LEATHER_BOOTS, 24);
      w(Items.CHAINMAIL_HELMET, 60); w(Items.CHAINMAIL_CHESTPLATE, 96);
      w(Items.CHAINMAIL_LEGGINGS, 84); w(Items.CHAINMAIL_BOOTS, 48);
      w(Items.IRON_HELMET, 50); w(Items.IRON_CHESTPLATE, 80);
      w(Items.IRON_LEGGINGS, 70); w(Items.IRON_BOOTS, 40);
      w(Items.GOLDEN_HELMET, 70); w(Items.GOLDEN_CHESTPLATE, 112);
      w(Items.GOLDEN_LEGGINGS, 98); w(Items.GOLDEN_BOOTS, 56);
      w(Items.DIAMOND_HELMET, 400); w(Items.DIAMOND_CHESTPLATE, 640);
      w(Items.DIAMOND_LEGGINGS, 560); w(Items.DIAMOND_BOOTS, 320);
      w(Items.NETHERITE_HELMET, 1400); w(Items.NETHERITE_CHESTPLATE, 1640);
      w(Items.NETHERITE_LEGGINGS, 1560); w(Items.NETHERITE_BOOTS, 1320);
      w(Items.TURTLE_HELMET, 250); w(Items.SHIELD, 20);

      /* ---- throwables / consumables ---- */
      w(Items.GOLDEN_APPLE, 120); w(Items.FIRE_CHARGE, 6); w(Items.SNOWBALL, 1);
      w(Items.EGG, 2); w(Items.TNT, 40); w(Items.ENDER_EYE, 24); w(Items.FIREWORK_ROCKET, 6);
      w(Items.ENDER_PEARL, 18); w(Items.WIND_CHARGE, 6); w(Items.EXPERIENCE_BOTTLE, 20);

      /* ---- utility ---- */
      w(Items.CHEST, 8); w(Items.BARREL, 10); w(Items.ENDER_CHEST, 220); w(Items.HOPPER, 60);
      w(Items.SHULKER_BOX, 540); w(Items.ELYTRA, 2000); w(Items.SADDLE, 60);
      w(Items.NAME_TAG, 45); w(Items.LEAD, 8); w(Items.BUCKET, 30);
      w(Items.WATER_BUCKET, 32); w(Items.LAVA_BUCKET, 40);
      w(Items.MINECART, 50); w(Items.RAIL, 4); w(Items.POWERED_RAIL, 20);

      /* ---- food ---- */
      w(Items.BREAD, 4); w(Items.COOKED_BEEF, 6); w(Items.COOKED_PORKCHOP, 6);
      w(Items.COOKED_CHICKEN, 4); w(Items.COOKED_MUTTON, 5); w(Items.COOKED_SALMON, 5);
      w(Items.COOKED_COD, 4); w(Items.BAKED_POTATO, 3); w(Items.CARROT, 2);
      w(Items.GOLDEN_CARROT, 20); w(Items.APPLE, 3); w(Items.CAKE, 30);
      w(Items.PUMPKIN_PIE, 8); w(Items.COOKIE, 2); w(Items.MELON_SLICE, 1);
      w(Items.SWEET_BERRIES, 2); w(Items.HONEY_BOTTLE, 8); w(Items.MILK_BUCKET, 34);

      /* ---- redstone ---- */
      w(Items.REDSTONE_TORCH, 6); w(Items.REPEATER, 24); w(Items.COMPARATOR, 30);
      w(Items.PISTON, 26); w(Items.STICKY_PISTON, 36); w(Items.OBSERVER, 32);
      w(Items.DISPENSER, 40); w(Items.DROPPER, 12); w(Items.LEVER, 3);
      w(Items.TRIPWIRE_HOOK, 12); w(Items.DAYLIGHT_DETECTOR, 34); w(Items.TARGET, 20);
      w(Items.REDSTONE_LAMP, 30); w(Items.NOTE_BLOCK, 22); w(Items.SLIME_BLOCK, 45);
      w(Items.HONEY_BLOCK, 40);

      /* ---- decoration ---- */
      w(Items.TORCH, 1); w(Items.LANTERN, 12); w(Items.SOUL_LANTERN, 16); w(Items.CANDLE, 6);
      w(Items.GLOWSTONE, 16); w(Items.SEA_LANTERN, 40); w(Items.FLOWER_POT, 4);
      w(Items.PAINTING, 10); w(Items.ITEM_FRAME, 12); w(Items.CHISELED_BOOKSHELF, 14);
      w(Items.OAK_SIGN, 3); w(Items.WHITE_BED, 12); w(Items.ARMOR_STAND, 12);
      w(Items.DECORATED_POT, 16); w(Items.AMETHYST_CLUSTER, 30); w(Items.LODESTONE, 110);

      /* ---- rare / boss loot ---- */
      w(Items.ENCHANTED_GOLDEN_APPLE, 1200); w(Items.TOTEM_OF_UNDYING, 900);
      w(Items.NETHER_STAR, 2500); w(Items.DRAGON_EGG, 5000); w(Items.DRAGON_HEAD, 3000);
      w(Items.BEACON, 2600); w(Items.CONDUIT, 1400); w(Items.HEART_OF_THE_SEA, 800);
      w(Items.ENCHANTED_BOOK, 150); w(Items.WITHER_SKELETON_SKULL, 400);
      w(Items.END_CRYSTAL, 300); w(Items.SHULKER_SHELL, 260);
   }

   private static String idOf(Item item) {
      return BuiltInRegistries.ITEM.getKey(item).toString();
   }

   public static long getBalance(ServerPlayer serverPlayer) {
      return BALANCES.getOrDefault(serverPlayer.getUUID(), 0L);
   }

   public static void addBalance(ServerPlayer serverPlayer, long l) {
      BALANCES.merge(serverPlayer.getUUID(), l, Long::sum);
   }

   public static boolean removeBalance(ServerPlayer serverPlayer, long l) {
      long l2 = getBalance(serverPlayer);
      if (l2 < l) {
         return false;
      } else {
         BALANCES.put(serverPlayer.getUUID(), l2 - l);
         return true;
      }
   }

   /** Every live listing in the world. Pre-sized, so the list never grows through three copies. */
   public static List<ShopManager.ShopEntry> visibleEntries() {
      ArrayList<ShopManager.ShopEntry> arrayList = new ArrayList<>(listingCount());

      for (ShopManager.Category category : CATEGORIES) {
         arrayList.addAll(STATE.get(category).listings.values());
      }

      return arrayList;
   }

   /** How many listings are on the shelves right now, across every category. */
   public static int listingCount() {
      int total = 0;
      for (ShopManager.Category category : CATEGORIES) {
         total += STATE.get(category).listings.size();
      }
      return total;
   }

   /** How many categories there are, without cloning the enum's array to ask. */
   public static int categoryCount() {
      return CATEGORIES.length;
   }

   /** @see #version */
   public static int version() {
      return version;
   }

   public static ShopManager.ShopEntry entry(String string) {
      for (ShopManager.Category category : CATEGORIES) {
         ShopManager.ShopEntry shopEntry = STATE.get(category).listings.get(string);
         if (shopEntry != null) {
            return shopEntry;
         }
      }

      return null;
   }

   public static ShopManager.CategoryState state(ShopManager.Category category) {
      return STATE.get(category);
   }

   /**
    * 2.2 (2.7.0) - the one and only restock state there is now.
    *
    * <p>True while the shelves are being refilled, however that refill was started: the shop's own
    * schedule, the last item selling out, or a player pressing Restock. There is no longer an
    * "armed, waiting for iron" state in front of it - see {@link #beginManualRestock}.
    */
   public static boolean isRestocking(ShopManager.Category category) {
      return STATE.get(category).restocking;
   }

   /** Seconds until this refill finishes on its own. Meaningful only while restocking. */
   public static int restockSecondsLeft(ShopManager.Category category) {
      return Math.max(0, STATE.get(category).restockSecondsLeft);
   }

   /**
    * True when a player started this refill, false when the shop or a sold-out shelf started it.
    *
    * <p>3.3.0 - this is no longer what picks the duration; {@link #restockDurationSeconds} reads the
    * {@link RestockReason} instead, because MANUAL and SOLD_OUT now share a clock. This still means
    * exactly what it says - the Restock button was pressed - and it is kept for the sync row, where
    * it has travelled since 2.6.0.
    */
   public static boolean isManualRestock(ShopManager.Category category) {
      return STATE.get(category).manualRestock;
   }

   /**
    * 2.2 (2.7.0) - is the optional iron shortcut available right now?
    *
    * <p>Exactly while a refill is running, and this single method decides all three of the things
    * that have to agree about it: whether the iron slot exists server-side
    * ({@link ShopMenu}'s {@code Slot.isActive}), whether the client draws it and its Submit button,
    * and whether the server accepts a submit packet.
    */
   public static boolean isIronSkipOpen(ShopManager.Category category) {
      return STATE.get(category).restocking;
   }

   /**
    * 3.2 - true whenever buying from this category must be refused.
    *
    * <p>A restock in progress is now the only such state, so this is deliberately a thin alias of
    * {@link #isRestocking} rather than a second flag that could drift out of step with it. Both the
    * client (greys the Buy buttons out and blocks the click) and the server (refuses the buy
    * packet before touching a coin) call this exact method.
    */
   public static boolean isRestockBusy(ShopManager.Category category) {
      return STATE.get(category).restocking;
   }

   public static ShopManager.RestockReason restockReason(ShopManager.Category category) {
      return STATE.get(category).restockReason;
   }

   public static int secondsUntilAutoRestock(ShopManager.Category category) {
      return Math.max(0, STATE.get(category).nextAutoRestockSeconds);
   }

   /**
    * 2.2 (2.7.0) - bit i set = category i currently has its optional iron slot open, i.e. it is
    * restocking. Mirrored to the client in every sync so both sides agree which slots exist.
    */
   public static int ironSlotMask() {
      int mask = 0;
      for (ShopManager.Category category : CATEGORIES) {
         if (STATE.get(category).restocking) {
            mask |= 1 << category.ordinal();
         }
      }
      return mask;
   }

   /**
    * @return true when this purchase emptied the category and therefore started a restock, so the
    *         caller knows the shared state changed for everyone and not just for the buyer
    */
   public static boolean consumeListing(ShopManager.ShopEntry shopEntry) {
      ShopManager.CategoryState categoryState = STATE.get(shopEntry.category);
      if (categoryState.listings.remove(shopEntry.itemId) != null) {
         version++;
      }
      if (!categoryState.listings.isEmpty()) {
         return false;
      }
      /*
       * Nothing left to sell, so there is nothing to wait for: start refilling immediately.
       *
       * 3.3.0 - SOLD_OUT is a TRIGGERED refill, so this runs the longer clock (60s) rather than the
       * 30s the schedule gets. The shelf did not empty on its own: players bought it out, which is
       * both the moment demand is highest and the one moment a refill is genuinely deserved rather
       * than imposed - see restockDurationSeconds().
       */
      return beginRestock(shopEntry.category, ShopManager.RestockReason.SOLD_OUT);
   }

   /**
    * 2.2 (2.7.0) - the AUTO flow: the shop's own schedule came due, so it refills itself. Free, no
    * iron, no player involvement.
    */
   public static boolean beginAutoRestock(ShopManager.Category category) {
      return beginRestock(category, ShopManager.RestockReason.SCHEDULED);
   }

   /**
    * 2.2 (2.7.0) - the MANUAL flow, and it is now a single step.
    *
     * <p>Pressing Restock <b>starts the refill immediately</b>. It does not ask for iron and it does
     * not wait for anything.
     *
     * <p>3.1.0 - it runs the longer clock, not the one the schedule gets. Up to 3.0.4 both flows
     * shared one duration. Since 3.3.0 that longer clock is
     * {@link #TRIGGERED_RESTOCK_DURATION_SECONDS} - 60s, down from 180s - and it is shared with
     * SOLD_OUT. See {@link #restockDurationSeconds} for why the two demand-driven paths pay more
     * than the scheduled one.
    *
    * <p>Through 2.6.0 this instead put the category into an "armed" state that sat there doing
    * nothing until the player had loaded 4 iron into a slot and pressed Submit, so iron was
    * mandatory before a manual restock could even begin. Iron is now purely a shortcut through a
    * refill that is already running - see {@link #finishRestockNow}.
    *
    * @return false when nothing changed because a refill was already running
    */
   public static boolean beginManualRestock(ShopManager.Category category) {
      return beginRestock(category, ShopManager.RestockReason.MANUAL);
   }

   /**
    * 2.2 (2.7.0) - the optional iron shortcut: skip whatever is left of a running refill and put
    * the stock back on the shelves right now.
    *
    * <p>Only ever reached from the Submit button, and only while a refill is actually in progress -
    * iron can never start one, and iron submitted when nothing is restocking is refused by the
    * caller before it is spent.
    *
    * @return false when there was no refill to finish
    */
   public static boolean finishRestockNow(ShopManager.Category category) {
      if (!STATE.get(category).restocking) {
         return false;
      }
      rollCategory(category);
      return true;
   }

   /**
    * Single entry point for every way a refill can start, so they cannot diverge.
    *
    * <p>3.3.0 - the {@code manual} parameter is gone. It used to be passed in beside the reason and
    * it had to agree with it, which is one place too many to state the same fact: the flag is now
    * derived here, from the only thing that can disagree with nothing.
    */
   private static boolean beginRestock(ShopManager.Category category, ShopManager.RestockReason reason) {
      ShopManager.CategoryState state = STATE.get(category);
      if (state.restocking) {
         return false;
      }
      state.restocking = true;
      state.manualRestock = reason == ShopManager.RestockReason.MANUAL;
      state.restockReason = reason;
      // 3.1.0 - the duration is a function of the trigger, and this is the only place it is set
      state.restockSecondsLeft = restockDurationSeconds(reason);
      version++;
      return true;
   }

   /**
    * Re-rolls one category's shelves.
    *
    * <p>1.2 (2.9.0) - this used to copy the whole pool into a new ArrayList and
    * {@code Collections.shuffle} it, just to read the first six. It now runs a partial
    * Fisher-Yates over a reused index array: only the six draws are shuffled, the pool itself is
    * never copied, and a refill allocates nothing but the six listings it produces.
    */
   private static void rollCategory(ShopManager.Category category) {
      ShopManager.CategoryState state = STATE.get(category);
      state.listings.clear();
      String[] pool = POOLS.get(category);
      int poolSize = pool == null ? 0 : pool.length;
      int count = Math.min(LISTINGS_PER_CATEGORY, poolSize);

      for (int i = 0; i < poolSize; i++) {
         shuffleOrder[i] = i;
      }

      for (int i = 0; i < count; i++) {
         int pick = i + RANDOM.nextInt(poolSize - i);
         int chosen = shuffleOrder[pick];
         shuffleOrder[pick] = shuffleOrder[i];
         shuffleOrder[i] = chosen;

         String itemId = pool[chosen];
         int base = buyPriceFor(itemId);
         // +/-8% haggling noise so two restocks never look identical, but never below the sell price
         int jitter = Math.max(1, base / 12);
         int buy = Math.max(sellPriceFor(itemId) + 1, base + RANDOM.nextInt(jitter * 2 + 1) - jitter);
         state.listings.put(itemId, new ShopManager.ShopEntry(itemId, category, buy, sellPriceFor(itemId)));
      }

      version++;
      state.restocking = false;
      state.restockSecondsLeft = 0;
      state.manualRestock = false;
      state.restockReason = ShopManager.RestockReason.NONE;
      state.nextAutoRestockSeconds = rollAutoInterval();
   }

   /* ------------------------------------------------------------------ *
    *  2.2 (2.7.0) - the restock state machine, one step per second.
    *
    *  A category is either IDLE or RESTOCKING. That is the whole model now;
    *  the "armed, waiting for iron" state in between is gone.
    *
    *    IDLE --auto timer hits 0-------------> RESTOCKING  (free, scheduled, 30s)
    *    IDLE --last listing bought-----------> RESTOCKING  (free, sold out,  60s)
    *    IDLE --player presses Restock--------> RESTOCKING  (free, immediate, 60s)
    *    RESTOCKING --its own clock runs out--> IDLE, stock re-rolled
    *    RESTOCKING --player submits iron-----> IDLE, stock re-rolled NOW
    *
    *  3.1.0 - every route into RESTOCKING still costs nothing, but they no
    *  longer run the SAME clock: the duration is chosen from the trigger by
    *  restockDurationSeconds(). Pressing the button buys an early start at the
    *  price of a longer lock. Iron still only ever does one thing: cut a
    *  refill that is already running short, whichever clock it is on.
    *
    *  3.3.0 - the three routes above are now 30s / 60s / 60s: the two that
    *  DEMAND caused share the long clock and only the one nobody asked for is
    *  short. The transitions out are untouched.
    *
    *  4.1 - this state lives once, on the server, for the whole world. The
    *  method returns the transitions it made so the caller can push them out
    *  to every player watching, instead of each client discovering them
    *  separately on its own next sync.
    * ------------------------------------------------------------------ */
   public static List<ShopManager.RestockEvent> tickOncePerSecond() {
      List<ShopManager.RestockEvent> events = null;
      // both countdowns move every second, so what the clients see is different every second
      version++;

      for (ShopManager.Category category : CATEGORIES) {
         ShopManager.CategoryState state = STATE.get(category);

         /* --- restocking: run the clock down, then re-roll the shelves --- */
         if (state.restocking) {
            state.restockSecondsLeft--;
            if (state.restockSecondsLeft <= 0) {
               // rollCategory clears the reason, so the finished refill's own trigger is read first
               ShopManager.RestockReason wasReason = state.restockReason;
               rollCategory(category);
               events = add(events, new ShopManager.RestockEvent(category, false, wasReason));
            }
            continue;
         }

         /* --- idle: sold out is not something to wait for, so refill at once --- */
         if (state.listings.isEmpty()) {
            if (beginRestock(category, ShopManager.RestockReason.SOLD_OUT)) {
               events = add(events, new ShopManager.RestockEvent(category, true, ShopManager.RestockReason.SOLD_OUT));
            }
            continue;
         }

         /* --- idle: the shop's own schedule --- */
         if (state.nextAutoRestockSeconds <= 0) {
            // no phase of its own yet, so scatter it rather than arming it in step with the others
            state.nextAutoRestockSeconds = rollFirstAutoInterval();
         }
         if (--state.nextAutoRestockSeconds <= 0 && beginAutoRestock(category)) {
            events = add(events, new ShopManager.RestockEvent(category, true, ShopManager.RestockReason.SCHEDULED));
         }
      }

      if (++tickCounter % 60 == 0) {
         save();
      }

      return events == null ? Collections.emptyList() : events;
   }

   /** Allocates the event list only on the seconds where something actually happened. */
   private static List<ShopManager.RestockEvent> add(List<ShopManager.RestockEvent> list, ShopManager.RestockEvent event) {
      List<ShopManager.RestockEvent> out = list == null ? new ArrayList<>(2) : list;
      out.add(event);
      return out;
   }

   /**
    * 2.1 / 4.1 - one restock transition, so the server can tell <b>every</b> player about it.
    *
    * <p>An automatic restock used to happen in total silence, which is why stock appeared to change
    * for no reason: nobody had pressed anything, so nobody knew why. Each start and finish is now
    * reported and announced.
    *
    * <p>3.3.0 - the separate {@code manual} flag is gone: it was the reason expressed a second,
    * lossier way, and a message that wants to quote a length needs the trigger, not a boolean. A
    * FINISH event now also carries the trigger the refill it ends was on, where it used to carry
    * {@link RestockReason#NONE}.
    *
    * @param started true when a refill began, false when one finished
    * @param reason  what triggered the refill: which also says how long it runs, via
    *                {@link #restockDurationSeconds}
    */
   public record RestockEvent(ShopManager.Category category, boolean started, ShopManager.RestockReason reason) {
      /** True only for the Restock button, for messages that name the player who pressed it. */
      public boolean manual() {
         return this.reason == ShopManager.RestockReason.MANUAL;
      }
   }

   /**
    * Writes the shop to the world it was bound to by {@link #openWorld}.
    *
    * <p>3.0.0 - a null {@code saveFile} means no world is open. That is a normal state on a client
    * sitting at the title screen, and writing anywhere at all in that state is what the old shared
    * config path used to do.
    */
   public static void save() {
      if (saveFile == null) {
         return;
      }

      try {
         JsonObject jsonObject = new JsonObject();
         JsonObject jsonObject2 = new JsonObject();

         for (ShopManager.Category category : CATEGORIES) {
            ShopManager.CategoryState categoryState = STATE.get(category);
            JsonObject jsonObject3 = new JsonObject();
            jsonObject3.addProperty("restocking", categoryState.restocking);
            jsonObject3.addProperty("restockSecondsLeft", categoryState.restockSecondsLeft);
            jsonObject3.addProperty("manualRestock", categoryState.manualRestock);
            jsonObject3.addProperty("restockReason", categoryState.restockReason.name());
            jsonObject3.addProperty("nextAutoRestockSeconds", categoryState.nextAutoRestockSeconds);
            JsonObject jsonObject4 = new JsonObject();

            for (ShopManager.ShopEntry shopEntry : categoryState.listings.values()) {
               JsonObject jsonObject5 = new JsonObject();
               jsonObject5.addProperty("buy", shopEntry.buyPrice);
               jsonObject5.addProperty("sell", shopEntry.sellPrice);
               jsonObject4.add(shopEntry.itemId, jsonObject5);
            }

            jsonObject3.add("listings", jsonObject4);
            jsonObject2.add(category.name(), jsonObject3);
         }

         jsonObject.add("categories", jsonObject2);
         JsonObject jsonObject6 = new JsonObject();

         for (Entry<UUID, Long> entry : BALANCES.entrySet()) {
            jsonObject6.addProperty(entry.getKey().toString(), entry.getValue());
         }

         jsonObject.add("balances", jsonObject6);
         Files.createDirectories(saveFile.getParent());

         try (BufferedWriter bufferedWriter = Files.newBufferedWriter(saveFile, StandardCharsets.UTF_8)) {
            GSON.toJson(jsonObject, bufferedWriter);
         }
      } catch (IOException var141) {
         System.err.println("[merchantmind] Failed to save shop data: " + var141);
      }
   }

   /**
    * Reads this world's shop back.
    *
    * <p>3.0.0 - there is exactly one candidate now, {@link #saveFile}, and it is inside the world
    * directory. The old fallback chain that reached into {@code config/} lives in
    * {@link #importSharedSaveOnce} instead, where it can only fire once, for one world.
    */
   public static void load() {
      Path source = saveFile;
      if (source != null && Files.exists(source)) {
         try (BufferedReader bufferedReader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            JsonObject jsonObject2 = JsonParser.parseReader(bufferedReader).getAsJsonObject();
            if (jsonObject2.has("categories")) {
               JsonObject jsonObject = jsonObject2.getAsJsonObject("categories");

               for (ShopManager.Category category : CATEGORIES) {
                  String key = jsonObject.has(category.name()) ? category.name() : category.legacyKey();
                  if (key != null && jsonObject.has(key)) {
                     JsonObject jsonObject3 = jsonObject.getAsJsonObject(key);
                     ShopManager.CategoryState categoryState = STATE.get(category);
                     categoryState.restocking = jsonObject3.has("restocking") && jsonObject3.get("restocking").getAsBoolean();
                     categoryState.restockSecondsLeft = jsonObject3.has("restockSecondsLeft") ? jsonObject3.get("restockSecondsLeft").getAsInt() : 0;
                     categoryState.manualRestock = jsonObject3.has("manualRestock") && jsonObject3.get("manualRestock").getAsBoolean();
                     categoryState.restockReason = readReason(jsonObject3);
                     /*
                      * A saved schedule is kept as it is even if it is longer than this build's
                      * interval: it finishes that one cycle and re-rolls into the new range, which
                      * is how every change to these constants has behaved. A save with no schedule
                      * at all gets a scattered first cycle, not a full one - see
                      * rollFirstAutoInterval().
                      */
                     categoryState.nextAutoRestockSeconds = jsonObject3.has("nextAutoRestockSeconds")
                        ? Math.max(1, jsonObject3.get("nextAutoRestockSeconds").getAsInt())
                        : rollFirstAutoInterval();
                     /*
                      * 2.2 (2.7.0) - the "armed, waiting for iron" state that worlds saved by 2.5.x
                      * and 2.6.x could be in no longer exists. Those flags (restockArmed /
                      * armSecondsLeft / restockDue / restockPending) are deliberately NOT read: a
                      * category that was parked waiting for iron simply comes back idle, with its
                      * auto timer running, which is a valid state in the new model. Nothing is lost
                      * but a half-finished transaction, and no iron was ever taken for it.
                      *
                       * Clamp the refill clock as well - a save from a build with a different
                       * refill duration must not leave a category counting down from longer than
                       * this build's own refill takes.
                       *
                       * 3.1.0 - the ceiling is now per-mode, and the restock reason is read just
                       * above precisely so it is available here. Every clamp in this mod's history
                       * has been downward and nothing is lost by one: the refill finishes sooner
                       * than the save recorded.
                       *
                       * 3.3.0 - the ceiling is chosen by the trigger, so it needs the reason rather
                       * than the manual flag. Saves are not all equally informative, so this asks
                       * in order of reliability:
                       *
                       *   - a recorded reason (2.7.0+) is used as it is;
                       *   - a save with no reason but with manualRestock true (2.6.x-ish) is treated
                       *     as MANUAL, which restores the clock that build was running;
                       *   - anything older defaults to SCHEDULED, the shorter ceiling, which is the
                       *     safe direction.
                       *
                       * Concretely: a 3.2.0 world saved mid-manual refill comes back with at most
                       * 60s left instead of its recorded 180, and one saved mid-scheduled refill
                       * with at most 30s instead of 60.
                       */
                      if (categoryState.restocking) {
                         ShopManager.RestockReason clampReason = categoryState.restockReason != ShopManager.RestockReason.NONE
                            ? categoryState.restockReason
                            : categoryState.manualRestock ? ShopManager.RestockReason.MANUAL : ShopManager.RestockReason.SCHEDULED;
                         int ceiling = restockDurationSeconds(clampReason);
                         categoryState.restockSecondsLeft = Math.max(1, Math.min(categoryState.restockSecondsLeft, ceiling));
                      }
                     categoryState.listings.clear();
                     if (jsonObject3.has("listings")) {
                        JsonObject jsonObject4 = jsonObject3.getAsJsonObject("listings");

                        for (String string : jsonObject4.keySet()) {
                           JsonObject jsonObject5 = jsonObject4.getAsJsonObject(string);
                           categoryState.listings
                              .put(string, new ShopManager.ShopEntry(string, category, jsonObject5.get("buy").getAsInt(), jsonObject5.get("sell").getAsInt()));
                        }
                     }
                  }
               }
            }

            if (jsonObject2.has("balances")) {
               JsonObject jsonObject = jsonObject2.getAsJsonObject("balances");

               for (String string : jsonObject.keySet()) {
                  try {
                     BALANCES.put(UUID.fromString(string), jsonObject.get(string).getAsLong());
                  } catch (IllegalArgumentException var14) {
                  }
               }
            }
         } catch (IOException var161) {
            System.err.println("[merchantmind] Failed to load shop data: " + var161);
         }
      }
   }

   public static enum Category {
      WEAPON("Weapon"),
      ARMOR("Armor"),
      TOOLS("Tools"),
      THROWABLES("Throwables"),
      RESOURCES("Resources"),
      BLOCKS("Blocks"),
      UTILITY("Utility"),
      FOOD("Food"),
      REDSTONE("Redstone"),
      DECORATION("Decor"),
      RARE("Rare");

      public final String display;

      private Category(String string2) {
         this.display = string2;
      }

      /** Key this category used in shop.json before 2.2.0, or null if unchanged. */
      public String legacyKey() {
         return this == THROWABLES ? "COMBAT" : null;
      }
   }

   private static ShopManager.RestockReason readReason(JsonObject json) {
      return json.has("restockReason")
         ? ShopManager.RestockReason.fromKey(json.get("restockReason").getAsString())
         : ShopManager.RestockReason.NONE;
   }

   /**
    * What triggered a category's refill.
    *
    * <p>Up to 3.2.0 this was decoration: it existed "purely so the UI can say something useful",
    * while the duration was decided by a boolean beside it. Since 3.3.0 it is the model - it decides
    * how long the refill takes ({@link #triggered}), it is what the save clamp reads, and it is what
    * the client scales its progress bar by - so the three of them cannot disagree about a refill the
    * way a boolean and an enum could.
    */
   public static enum RestockReason {
      NONE("", false),
      MANUAL("a player asked for it", true),
      SOLD_OUT("sold out", true),
      SCHEDULED("scheduled restock", false);

      public final String display;
      /**
       * True when this refill happened because of DEMAND - a player asked for it, or players bought
       * the shelf out - and so runs {@link #TRIGGERED_RESTOCK_DURATION_SECONDS} rather than the
       * shorter {@link #SCHEDULED_RESTOCK_DURATION_SECONDS}. This one field is the whole of the
       * 3.3.0 rule; see {@link #restockDurationSeconds}.
       */
      public final boolean triggered;

      private RestockReason(String display, boolean triggered) {
         this.display = display;
         this.triggered = triggered;
      }

      /**
       * 3.3.0 - {@link #name()} back to a constant, for the two places a reason arrives as text: the
       * save file and the sync row. Unknown or empty text is {@link #NONE}, never an exception, so a
       * hand-edited save or a mismatched client cannot take the shop down.
       *
       * <p>A switch rather than {@code valueOf} because this is read by the client's render path:
       * valueOf throws on anything unknown, and an exception per frame is not a way to say "no".
       */
      public static ShopManager.RestockReason fromKey(String key) {
         return switch (key == null ? "" : key) {
            case "MANUAL" -> MANUAL;
            case "SOLD_OUT" -> SOLD_OUT;
            case "SCHEDULED" -> SCHEDULED;
            default -> NONE;
         };
      }
   }

   public static final class CategoryState {
      /**
       * The shelves, in the order they are drawn.
       *
       * <p>1.2 (2.9.0) - sized for exactly {@link #LISTINGS_PER_CATEGORY} entries at load factor 1,
       * so a refill fills the table it was given instead of allocating a second, larger one on the
       * last insertion. Insertion order is what the UI shows, so this one map stays a LinkedHashMap.
       */
      public final Map<String, ShopManager.ShopEntry> listings = new LinkedHashMap<>(LISTINGS_PER_CATEGORY, 1.0F);
      /** 2.2 (2.7.0) - shelves being refilled. The only restock state there is. */
      public boolean restocking = false;
      /** seconds until this refill finishes by itself; RESTOCK_IRON_COST iron can cut it short */
      public int restockSecondsLeft = 0;
      /**
       * 2.1 - a player started this refill (vs the shop's own schedule), so the UI can say which.
       *
       * <p>3.3.0 - display and wire compatibility only. It no longer decides anything: the duration,
       * the save clamp and the client's progress bar all read {@link #restockReason} now, because
       * MANUAL and SOLD_OUT share a clock and a boolean cannot say that. Written by
       * {@code beginRestock} from the reason, so the two cannot disagree.
       */
      public boolean manualRestock = false;
      /** 3.3.0 - the trigger, and therefore the duration. Saved, synced, and read by the clamp. */
      public ShopManager.RestockReason restockReason = ShopManager.RestockReason.NONE;
      /** 2.2 - seconds until this category asks for a restock on its own */
      public int nextAutoRestockSeconds = 0;

      public CategoryState() {
      }
   }

   public static final class ShopEntry {
      public final String itemId;
      public final ShopManager.Category category;
      public final int buyPrice;
      public final int sellPrice;

      public ShopEntry(String string, ShopManager.Category category, int n, int n2) {
         this.itemId = string;
         this.category = category;
         this.buyPrice = n;
         this.sellPrice = n2;
      }
   }
}

package com.merchantmind;

import com.merchantmind.ai.PriceAppraiser;
import com.merchantmind.ai.TradeAdvisor;
import com.merchantmind.network.ShopPackets;
import com.merchantmind.shop.ShopManager;
import com.merchantmind.shop.ShopMenu;
import com.merchantmind.shop.ShopRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MerchantMind implements ModInitializer {
   public static final String MOD_ID = "merchantmind";
   public static final Logger LOGGER = LoggerFactory.getLogger("merchantmind");
   /**
    * Stack traces are logged once per failing site, then downgraded, so a bug cannot spam the log.
    * Concurrent because ENTITY_LOAD can be fired from a chunk-loading worker, not only the main thread.
    */
   private static final Set<String> FAILURES = ConcurrentHashMap.newKeySet();
   /** Hard ceiling on queued callbacks - if this is ever hit something is scheduling in a loop. */
   private static final int MAX_DELAYED = 256;
   /** Hard ceiling on entities waiting to be discarded, so a pathological world cannot grow the queue forever. */
   private static final int MAX_PENDING_DISCARDS = 8192;
   /**
    * 4.1 - skeleton / baby zombie spawn suppression.
    *
    * <p>Fraction of loaded skeletons and baby zombies that are KEPT; the rest are culled the tick
    * after they load. 0.17 = 17% survive, i.e. those two mobs appear about 83% less often than
    * vanilla. Unchanged in 2.6.0.
    */
   private static final float KEEP_CHANCE = 0.17F;
   /**
    * 4.2 - arrow reflect. Damage an arrow does to a player is dealt back to the shooter multiplied
    * by this. Spec value is 3x; it had drifted to 2x and was restored in 2.6.0.
    */
   private static final float ARROW_REFLECT_MULTIPLIER = 3.0F;
   private static final Random RANDOM = new Random();

   /**
    * Charge-and-Slam tuning: hold this long for the maximum bonus.
    *
    * <p>History: 60 ticks originally, 45 in 2.4.0, back to 60 in 2.5.0, 30 (1.5s) from 2.5.1, and
    * 38 (1.9s) in 2.9.0. Everything downstream - the damage curve, the charge percentage, the HUD
    * bar and the screen-stretch overlay - is expressed as a ratio of this constant, so it is the
    * single number that controls the pace and nothing else needs retuning when it moves.
    *
    * <p>2.9.0 - 30 ticks charged too fast to be a commitment: a 1.5s hold reached full power, and
    * because the curve is normalised to this constant, every fraction of a hold was worth more
    * than it should have been. 38 is a deliberately small step - a quarter longer, not double -
    * so the wind-up reads as a wind-up without turning the slam into a different move. A full
    * charge still pays exactly what it always did ({@link #SLAM_MAX_BONUS}); only the time it takes
    * to get there moved, and with it where a partial hold sits on the curve.
    */
   public static final int SLAM_MAX_TICKS = 38;
   private static final float SLAM_MIN_BONUS = 0.4F;
   /**
    * 3.0.0 - public because the charge HUD draws a second, shorter bar for the bonus itself and
    * needs the ceiling to scale it. The bonus curve is deliberately not linear in charge, so a bar
    * showing "how much damage" is genuinely different information from one showing "how charged",
    * and it cannot be derived client-side without this number.
    */
   public static final float SLAM_MAX_BONUS = 14.0F;
   /** Jump critical bonus on hostile mobs. */
   private static final float CRIT_BONUS_FRACTION = 0.15F;
   private static final float CRIT_BONUS_MIN = 1.0F;
   private static final float CRIT_BONUS_MAX = 3.0F;

   /**
    * 1.2 (2.9.0) - the category list and its sync keys, once.
    *
    * <p>{@code values()} clones its array on every call, and the status-row key was built by string
    * concatenation for every category, for every player, on every sync. Both are constants.
    */
   private static final ShopManager.Category[] CATEGORIES = ShopManager.Category.values();
   private static final String[] CATEGORY_KEYS = categoryKeys();

   private static String[] categoryKeys() {
      String[] keys = new String[CATEGORIES.length];
      for (int i = 0; i < CATEGORIES.length; i++) {
         keys[i] = "merchantmind:__cat_" + CATEGORIES[i].name();
      }
      return keys;
   }

   private int tickCounter = 0;
   /** 1.2 (2.9.0) - the shop rows shared by every player's sync, and the state version they match. */
   private List<ShopPackets.ShopEntryData> shopRows = List.of();
   private int shopRowsVersion = -1;
   private final List<MerchantMind.DelayedTask> delayed = new ArrayList<>();
   private final Map<UUID, Boolean> pearlInFlight = new HashMap<>();
   /** server tick at which the player started holding the slam key */
   private final Map<UUID, Integer> slamChargeStart = new ConcurrentHashMap<>();
   /**
    * 4.1 - entities selected for removal by ENTITY_LOAD. They are NOT discarded inside the event:
    * that callback runs while the level's entity manager is still adding the entity (and, while a
    * world is loading, for thousands of entities in a row), and removing an entity from inside that
    * callback re-enters the same entity list. The discard is deferred to the end of the next server
    * tick instead, where it is safe and individually guarded.
    */
   private final ConcurrentLinkedQueue<Entity> pendingDiscards = new ConcurrentLinkedQueue<>();
   /** Size of {@link #pendingDiscards}; the queue's own size() is O(n) and this is read per loaded entity. */
   private final AtomicInteger pendingDiscardCount = new AtomicInteger();
   private volatile boolean applyingBonusDamage = false;

   private void schedule(int ticks, Runnable run) {
      if (this.delayed.size() >= MAX_DELAYED) {
         LOGGER.error("[{}] delayed-task queue hit {} entries - dropping new task to avoid a runaway loop", MOD_ID, MAX_DELAYED);
         return;
      }
      this.delayed.add(new MerchantMind.DelayedTask(this.tickCounter + Math.max(1, ticks), run));
   }

   @Override
   public void onInitialize() {
      ShopRegistry.init();
      ShopManager.init();
      this.registerNetworking();

      /*
       * 3.0.0 - the shop is per-world state and is now bound to the world that is actually open.
       *
       * SERVER_STARTING runs before the first tick and before anyone can connect, so the shop is
       * always loaded from the right place before it can be read. SERVER_STOPPING writes it back
       * and wipes memory, so opening a second world in the same session cannot inherit the first
       * one's shelves, clocks or balances. ShopManager.init() above no longer touches any of this.
       */
      ServerLifecycleEvents.SERVER_STARTING.register(server -> guard("shop open world", () -> ShopManager.openWorld(server)));
      ServerLifecycleEvents.SERVER_STOPPING.register(server -> guard("shop close world", ShopManager::closeWorld));

      // All three of these run on the server thread inside the tick loop, so every one of them has
      // to be exception-proof: an escape from any of them takes the whole world down with
      // "Exception ticking world" / a frozen client on the loading screen.
      ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
      ServerEntityEvents.ENTITY_LOAD.register(this::onEntityLoad);
      ServerLivingEntityEvents.AFTER_DAMAGE.register(this::onAfterDamage);
   }

   /* ==================================================================== *
    *  tick
    * ==================================================================== */

   private void onServerTick(MinecraftServer server) {
      this.tickCounter++;

      // 1.2 + 1.3: checked EVERY tick so there is no delay at all.
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         if (!isOnline(player)) {
            continue;
         }
         guard("pearl heal", player, () -> this.tickPearlHeal(player));
         guard("pearl refill", player, () -> this.enforceExactlyOne(player, Items.ENDER_PEARL));
         guard("wind charge refill", player, () -> this.enforceExactlyOne(player, Items.WIND_CHARGE));
      }

      guard("deferred discards", this::drainPendingDiscards);
      this.runDueTasks();

      if (this.tickCounter % 20 == 0) {
         guard("shop tick", null, () -> this.tickShop(server));
      }
   }

   /* ==================================================================== *
    *  4.1 - the shop is ONE shared thing, owned by the server
    * ==================================================================== */

   /**
    * 4.1 - advances the shared shop state once a second and pushes the result to everybody.
    *
    * <p>{@link ShopManager} holds the only copy of stock and restock state for the whole world, and
    * this is the only thing that advances it. Every player with the shop open is then sent the same
    * snapshot in the same tick, so two players watching the same category always read the same
    * countdown - they are not each running their own clock.
    *
    * <p>Restock transitions are announced as well: an automatic restock used to happen in complete
    * silence, so stock appeared to change for no reason at all.
    */
   private void tickShop(MinecraftServer server) {
      List<ShopManager.RestockEvent> events = ShopManager.tickOncePerSecond();

      for (ShopManager.RestockEvent event : events) {
         // 3.1.0 - the length quoted is the one this refill is actually on, not a single global value
         // 3.3.0 - and it is read from the trigger, so a sold-out refill quotes its 60s and not the
         //         schedule's 30s the way it did while one boolean was standing in for three triggers
         String note = event.started()
            ? "⟳ " + event.category().display + " is restocking (" + event.reason().display + ") — "
               + ShopManager.restockDelayLabel(event.reason()) + ", or submit " + ShopManager.RESTOCK_IRON_COST + " iron to finish now."
            : "✔ " + event.category().display + " restocked — new stock is in.";
         this.announceToShoppers(server, note);
      }

      this.broadcastShopSync(server, "");
   }

   /** Every online player who currently has the shop open. They are the ones who need a sync. */
   private void broadcastShopSync(MinecraftServer server, String message) {
      if (server == null) {
         return;
      }
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         if (isOnline(player) && player.containerMenu instanceof ShopMenu) {
            guard("shop sync", player, () -> this.sendSync(player, message));
         }
      }
   }

   /**
    * 4.1 - pushes shared state out the instant it changes, instead of letting each client find out
    * on its own next heartbeat.
    *
    * <p>This is what makes "player A presses Restock, player B sees the countdown immediately" true.
    * Called from every handler that mutates a category.
    */
   private void broadcastAfterChange(ServerPlayer actor, String message) {
      MinecraftServer server = actor.level().getServer();
      if (server == null) {
         // single player with no server reference should be impossible; still, never drop the sync
         this.sendSync(actor, message);
         return;
      }
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         if (!isOnline(player) || !(player.containerMenu instanceof ShopMenu)) {
            continue;
         }
         // only the player who acted gets the wording aimed at them; everyone else just re-syncs
         String text = player == actor ? message : "";
         guard("shop sync", player, () -> this.sendSync(player, text));
      }
   }

   /** A one-line note in chat for everyone with the shop open. Used for restock start/finish. */
   private void announceToShoppers(MinecraftServer server, String note) {
      if (server == null) {
         return;
      }
      Component line = Component.literal("§6[Shop] §f" + note);
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         if (isOnline(player) && player.containerMenu instanceof ShopMenu) {
            guard("shop announce", player, () -> player.sendSystemMessage(line));
         }
      }
   }

   /**
    * 1.1 - the old version iterated {@code delayed} with an Iterator and ran the callbacks
    * INSIDE the loop. Any callback that scheduled another task mutated the list mid-iteration,
    * which makes the very next {@code it.hasNext()} throw ConcurrentModificationException and
    * takes the whole server tick down with it. Tasks are now drained into a snapshot first,
    * so callbacks may schedule freely, and every callback is individually guarded.
    */
   private void runDueTasks() {
      if (this.delayed.isEmpty()) {
         return;
      }

      List<MerchantMind.DelayedTask> due = null;
      for (int i = this.delayed.size() - 1; i >= 0; i--) {
         MerchantMind.DelayedTask task = this.delayed.get(i);
         if (this.tickCounter >= task.fireTick()) {
            if (due == null) {
               due = new ArrayList<>();
            }
            due.add(task);
            this.delayed.remove(i);
         }
      }

      if (due != null) {
         for (MerchantMind.DelayedTask task : due) {
            guard("delayed task", null, task.run());
         }
      }
   }

   /** Runs mod logic so a single failure can never kill the server tick, and says where it broke. */
   private static void guard(String what, Runnable body) {
      guard(what, null, body);
   }

   /** Runs mod logic so a single failure can never kill the server tick, and says where it broke. */
   private static void guard(String what, ServerPlayer who, Runnable body) {
      try {
         body.run();
      } catch (Throwable error) {
         report(() -> what + (who == null ? "" : "/" + nameOf(who)), error);
      }
   }

   /**
    * Same as {@link #guard(String, Runnable)}, but the label is only built when something actually
    * failed. Used on the hot paths (entity load fires thousands of times while a world loads).
    */
   private static void guard(Supplier<String> what, Runnable body) {
      try {
         body.run();
      } catch (Throwable error) {
         report(what, error);
      }
   }

   /**
    * Last line of defence before an exception escapes into the server tick loop, so nothing in here
    * may throw - not the label, not the logger.
    */
   private static void report(Supplier<String> label, Throwable error) {
      String key;
      try {
         key = label.get();
      } catch (Throwable ignored) {
         key = "unknown site";
      }

      try {
         if (FAILURES.add(key)) {
            LOGGER.error(
               "[{}] '{}' failed - caught here so the server tick keeps running. This is a bug, please report it with this full stack trace",
               MOD_ID,
               key,
               error
            );
         } else {
            // Repeat failures: keep the message, drop the trace, so a per-tick bug cannot flood the log.
            LOGGER.warn("[{}] '{}' failed again: {}", MOD_ID, key, String.valueOf(error));
         }
      } catch (Throwable loggingFailure) {
         // Logging itself blew up - never let that reach the tick loop either.
      }
   }

   /**
    * 4.3 - {@code getGameProfile()} (and the name inside it) can be null for a player that is not
    * fully initialised yet, and this is called from inside a catch block, so it must never throw.
    */
   private static String nameOf(ServerPlayer player) {
      if (player == null) {
         return "?";
      }
      try {
         var profile = player.getGameProfile();
         String name = profile == null ? null : profile.name();
         return name == null || name.isEmpty() ? player.getStringUUID() : name;
      } catch (Throwable ignored) {
         return "?";
      }
   }

   /** Short, null-safe description of an entity for log messages. */
   private static String describe(Entity entity) {
      if (entity == null) {
         return "null";
      }
      try {
         return entity.getType() == null ? entity.getClass().getSimpleName() : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
      } catch (Throwable ignored) {
         return entity.getClass().getSimpleName();
      }
   }

   private static boolean isOnline(ServerPlayer player) {
      return player != null && !player.isRemoved() && player.connection != null && player.connection.isAcceptingMessages();
   }

   /**
    * 1.3 - brand new heal rule: the moment the player's thrown ender pearl entity is gone
    * (landed, hit something, despawned, near or far - it does not matter) they get healed.
    * No coordinate/distance logic of any kind.
    */
   private void tickPearlHeal(ServerPlayer player) {
      UUID id = player.getUUID();
      boolean hasPearl = false;

      if (player.level() instanceof ServerLevel level) {
         for (ThrownEnderpearl pearl : level.getEntities(EntityType.ENDER_PEARL, e -> !e.isRemoved())) {
            if (pearl.getOwner() == player) {
               hasPearl = true;
               break;
            }
         }
      }

      boolean wasInFlight = Boolean.TRUE.equals(this.pearlInFlight.put(id, hasPearl));
      if (wasInFlight && !hasPearl) {
         player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20, 254, false, true, true));
         player.heal(6.0F);
         player.sendOverlayMessage(Component.literal("✦ Pearl surge — healed"));
      }
   }

   /** Keeps exactly one of the given item on the player, re-issued instantly. */
   private void enforceExactlyOne(ServerPlayer player, Item item) {
      List<ItemStack> stacks = new ArrayList<>(player.getInventory().getNonEquipmentItems());
      stacks.add(player.getInventory().getItem(40));

      int total = 0;
      for (ItemStack stack : stacks) {
         if (stack.is(item)) {
            total += stack.getCount();
         }
      }

      if (total == 0) {
         player.getInventory().add(new ItemStack(item, 1));
      } else if (total > 1) {
         int excess = total - 1;
         for (ItemStack stack : stacks) {
            if (excess <= 0) {
               break;
            }
            if (!stack.isEmpty() && stack.is(item)) {
               int take = Math.min(excess, stack.getCount());
               stack.shrink(take);
               excess -= take;
            }
         }
      }
   }

   /* ==================================================================== *
    *  4.1 - entity load  (never throws into the tick loop, never discards inline)
    * ==================================================================== */

   /**
    * ENTITY_LOAD is fired once per entity while a world/chunk is being loaded, so it can run
    * thousands of times inside a single tick. Two things used to make it dangerous:
    *
    * <ol>
    *   <li>nothing caught exceptions, so a single bad entity took the whole server tick loop down;</li>
    *   <li>{@code entity.discard()} was called from inside the load callback, i.e. while the level's
    *       entity manager was still busy adding that very entity.</li>
    * </ol>
    *
    * The body is now fully wrapped in {@link #guard} and the removal is queued instead of executed.
    */
   private void onEntityLoad(Entity entity, ServerLevel level) {
      guard(() -> "entity load/" + describe(entity), () -> this.handleEntityLoad(entity, level));
   }

   private void handleEntityLoad(Entity entity, ServerLevel level) {
      // 4.3 - during chunk load an entity can still be half-initialised: check everything.
      if (entity == null || level == null || entity.isRemoved()) {
         return;
      }

      boolean skeleton = entity instanceof Skeleton;
      boolean babyZombie = entity instanceof Zombie zombie && zombie.isBaby();
      if (!skeleton && !babyZombie) {
         return;
      }
      if (RANDOM.nextFloat() <= KEEP_CHANCE) {
         return;
      }

      if (this.pendingDiscardCount.get() >= MAX_PENDING_DISCARDS) {
         if (FAILURES.add("discard queue full")) {
            LOGGER.error(
               "[{}] discard queue hit {} entries - skipping further removals this load. Entities are loading faster than they can be culled",
               MOD_ID,
               MAX_PENDING_DISCARDS
            );
         }
         return;
      }

      this.pendingDiscards.add(entity);
      this.pendingDiscardCount.incrementAndGet();
   }

   /**
    * Runs at the end of a server tick, when the entity lists are no longer being mutated by the
    * loader. Bounded per tick so a world that streams entities in faster than they are culled can
    * never turn this into an endless loop inside one tick.
    */
   private void drainPendingDiscards() {
      int budget = 1024;
      Entity entity;
      while (budget-- > 0 && (entity = this.pendingDiscards.poll()) != null) {
         this.pendingDiscardCount.decrementAndGet();
         Entity target = entity;
         guard(() -> "deferred discard/" + describe(target), () -> {
            if (target.isRemoved()) {
               return;
            }
            // 4.3 - level() can still be null / not a ServerLevel if the entity never finished loading.
            if (!(target.level() instanceof ServerLevel)) {
               LOGGER.debug("[{}] skipping discard of {} - it is not on a server level (any more)", MOD_ID, describe(target));
               return;
            }
            target.discard();
         });
      }
   }

   /* ==================================================================== *
    *  combat
    * ==================================================================== */

   /**
    * 4.2 - AFTER_DAMAGE runs inside the entity tick, i.e. inside the server tick loop. Anything
    * thrown here (a bad cast, a null level, a null game profile on a player that is still logging in)
    * used to propagate straight out and kill the tick. The whole body is guarded now.
    */
   private void onAfterDamage(LivingEntity victim, DamageSource source, float baseAmount, float amount, boolean blocked) {
      guard(() -> "after damage/" + describe(victim), () -> this.handleAfterDamage(victim, source, amount, blocked));
   }

   private void handleAfterDamage(LivingEntity victim, DamageSource source, float amount, boolean blocked) {
      if (this.applyingBonusDamage) {
         return;
      }
      // 4.3 - both can be null while a world is still loading / an entity is being removed.
      if (victim == null || source == null || victim.isRemoved()) {
         return;
      }

      // 3.1 - you do not get to shop while something is hitting you.
      if (amount > 0.0F && victim instanceof ServerPlayer shopper) {
         this.closeShopOnDamage(shopper);
      }

      /*
       * 4.2 - arrow reflect: an arrow that hits the player is paid back to whoever fired it, at
       * ARROW_REFLECT_MULTIPLIER times the damage it did.
       *
       * 2.6.0 - restored to 3.0. The system itself never stopped working, but the multiplier had
       * drifted to 2.0 and no release note ever explained why, so the spec value is put back and
       * given a name here rather than being a literal buried in the damage handler.
       */
      if (!blocked && amount > 0.0F && victim instanceof ServerPlayer hurtPlayer && source.getDirectEntity() instanceof AbstractArrow) {
         if (source.getEntity() instanceof LivingEntity shooter && shooter != hurtPlayer && !shooter.isRemoved()) {
            if (hurtPlayer.level() instanceof ServerLevel reflectLevel) {
               shooter.hurtServer(reflectLevel, reflectLevel.damageSources().thorns(hurtPlayer), amount * ARROW_REFLECT_MULTIPLIER);
            } else {
               LOGGER.debug("[{}] arrow reflect skipped - {} is not on a server level", MOD_ID, nameOf(hurtPlayer));
            }
         }
      }

      if (blocked || amount <= 0.0F) {
         return;
      }
      if (!(source.getEntity() instanceof ServerPlayer attacker) || victim == attacker || attacker.isRemoved()) {
         return;
      }
      // 4.3 - victim.level() is null-safe here: instanceof on null simply fails the check.
      if (!source.is(DamageTypes.PLAYER_ATTACK) || !(victim.level() instanceof ServerLevel level)) {
         return;
      }

      float bonus = 0.0F;
      StringBuilder note = new StringBuilder();

      // 3.2 - small bonus for a real critical hit on a hostile mob while airborne
      if (victim instanceof Enemy && isJumpCritical(attacker)) {
         float critBonus = Math.min(CRIT_BONUS_MAX, Math.max(CRIT_BONUS_MIN, amount * CRIT_BONUS_FRACTION));
         bonus += critBonus;
         note.append("✦ Jump crit +").append(oneDecimal(critBonus));
      }

      // 3.3 - charge-and-slam: bonus scales straight off how long the key was held
      UUID attackerId = attacker.getUUID();
      Integer chargeStart = attackerId == null ? null : this.slamChargeStart.get(attackerId);
      ItemStack weapon = attacker.getMainHandItem();
      if (weapon == null) {
         weapon = ItemStack.EMPTY;
      }
      if (chargeStart != null && (weapon.is(ItemTags.SWORDS) || weapon.is(ItemTags.AXES))) {
         int heldTicks = Math.max(0, this.tickCounter - chargeStart);
         float slam = slamBonusFor(heldTicks);
         bonus += slam;
         if (note.length() > 0) {
            note.append("   ");
         }
         note.append("⚡ SLAM +").append(oneDecimal(slam)).append(" (").append(chargePercent(heldTicks)).append("% charge)");
         // consume the charge: holding the key keeps charging again from now
         this.slamChargeStart.put(attackerId, this.tickCounter);
         /*
          * 3.0.0 - tell the client its charge is gone, so the HUD gauge drops to empty in the same
          * moment the damage lands instead of continuing to advertise a charge that was just spent.
          * canSend() keeps this silent for any client that does not know the packet.
          */
         if (isOnline(attacker) && ServerPlayNetworking.canSend(attacker, ShopPackets.SlamConsumedS2CPayload.TYPE)) {
            ServerPlayNetworking.send(attacker, new ShopPackets.SlamConsumedS2CPayload());
         }
      }

      if (bonus > 0.0F) {
         // The re-entrancy flag must be cleared even if hurtServer throws, hence the finally.
         this.applyingBonusDamage = true;

         try {
            victim.invulnerableTime = 0;
            victim.hurtServer(level, level.damageSources().playerAttack(attacker), bonus);
         } finally {
            this.applyingBonusDamage = false;
         }

         if (isOnline(attacker)) {
            attacker.sendOverlayMessage(Component.literal(note.toString()));
         }
      }
   }

   /** Same rule vanilla uses for a critical hit. */
   private static boolean isJumpCritical(ServerPlayer player) {
      return player.fallDistance > 0.0
         && !player.onGround()
         && !player.onClimbable()
         && !player.isInWater()
         && !player.hasEffect(MobEffects.BLINDNESS)
         && !player.isPassenger()
         && !player.isSprinting();
   }

   /** Damage scales with hold time only - never with fall distance. */
   public static float slamBonusFor(int heldTicks) {
      float ratio = Math.min(1.0F, Math.max(0.0F, heldTicks / (float)SLAM_MAX_TICKS));
      // Curve shape is independent of the charge length: ratio is already normalised, so the 1.6
      // exponent keeps the same slow-start / strong-finish feel at any SLAM_MAX_TICKS.
      return SLAM_MIN_BONUS + (float)Math.pow(ratio, 1.6) * (SLAM_MAX_BONUS - SLAM_MIN_BONUS);
   }

   private static int chargePercent(int heldTicks) {
      return Math.round(100.0F * Math.min(1.0F, heldTicks / (float)SLAM_MAX_TICKS));
   }

   private static String oneDecimal(float value) {
      return String.format(java.util.Locale.ROOT, "%.1f", value);
   }

   /* ==================================================================== *
    *  networking
    * ==================================================================== */

   private void registerNetworking() {
      PayloadTypeRegistry.serverboundPlay().register(ShopPackets.RequestSyncC2SPayload.TYPE, ShopPackets.RequestSyncC2SPayload.CODEC);
      PayloadTypeRegistry.serverboundPlay().register(ShopPackets.BuyC2SPayload.TYPE, ShopPackets.BuyC2SPayload.CODEC);
      PayloadTypeRegistry.serverboundPlay().register(ShopPackets.SellC2SPayload.TYPE, ShopPackets.SellC2SPayload.CODEC);
      PayloadTypeRegistry.serverboundPlay().register(ShopPackets.RestockC2SPayload.TYPE, ShopPackets.RestockC2SPayload.CODEC);
      PayloadTypeRegistry.serverboundPlay().register(ShopPackets.TradeC2SPayload.TYPE, ShopPackets.TradeC2SPayload.CODEC);
      PayloadTypeRegistry.serverboundPlay().register(ShopPackets.OpenShopC2SPayload.TYPE, ShopPackets.OpenShopC2SPayload.CODEC);
      PayloadTypeRegistry.serverboundPlay().register(ShopPackets.KillItemsC2SPayload.TYPE, ShopPackets.KillItemsC2SPayload.CODEC);
      PayloadTypeRegistry.serverboundPlay().register(ShopPackets.SlamChargeC2SPayload.TYPE, ShopPackets.SlamChargeC2SPayload.CODEC);
      PayloadTypeRegistry.clientboundPlay().register(ShopPackets.SyncS2CPayload.TYPE, ShopPackets.SyncS2CPayload.CODEC);
      PayloadTypeRegistry.clientboundPlay().register(ShopPackets.SlamConsumedS2CPayload.TYPE, ShopPackets.SlamConsumedS2CPayload.CODEC);

      // 5.1 - every one of these used to be a bare ctx.server().execute(...). Tasks handed to the
      // server run inside the main tick loop (MinecraftServer#pollTask), so an exception thrown by
      // one of them is reported as "Exception in server tick loop" and takes the world down, exactly
      // like a bad END_SERVER_TICK listener. They all go through onMainThread() now, which hops to
      // the main thread, re-checks that the player is still connected, and runs the body in guard().
      ServerPlayNetworking.registerGlobalReceiver(
         ShopPackets.RequestSyncC2SPayload.TYPE,
         (payload, ctx) -> this.onMainThread(ctx.server(), ctx.player(), "packet:request_sync", player -> this.sendSync(player, ""))
      );
      ServerPlayNetworking.registerGlobalReceiver(
         ShopPackets.OpenShopC2SPayload.TYPE, (payload, ctx) -> this.onMainThread(ctx.server(), ctx.player(), "packet:open_shop", this::openShop)
      );
      ServerPlayNetworking.registerGlobalReceiver(
         ShopPackets.BuyC2SPayload.TYPE,
         (payload, ctx) -> this.onMainThread(ctx.server(), ctx.player(), "packet:buy", player -> this.handleBuy(player, payload))
      );
      ServerPlayNetworking.registerGlobalReceiver(
         ShopPackets.SellC2SPayload.TYPE,
         (payload, ctx) -> this.onMainThread(ctx.server(), ctx.player(), "packet:sell", player -> this.handleSell(player, payload))
      );
      ServerPlayNetworking.registerGlobalReceiver(
         ShopPackets.RestockC2SPayload.TYPE,
         (payload, ctx) -> this.onMainThread(ctx.server(), ctx.player(), "packet:restock", player -> this.handleRestock(player, payload))
      );
      ServerPlayNetworking.registerGlobalReceiver(
         ShopPackets.TradeC2SPayload.TYPE,
         (payload, ctx) -> this.onMainThread(ctx.server(), ctx.player(), "packet:trade", player -> this.handleTrade(player, payload))
      );
      ServerPlayNetworking.registerGlobalReceiver(
         ShopPackets.KillItemsC2SPayload.TYPE, (payload, ctx) -> this.onMainThread(ctx.server(), ctx.player(), "packet:kill_items", this::handleKillItems)
      );
      ServerPlayNetworking.registerGlobalReceiver(
         ShopPackets.SlamChargeC2SPayload.TYPE,
         (payload, ctx) -> this.onMainThread(ctx.server(), ctx.player(), "packet:slam_charge", player -> {
            UUID id = player.getUUID();
            if (id == null) {
               return;
            }
            if (payload.charging()) {
               this.slamChargeStart.put(id, this.tickCounter);
            } else {
               this.slamChargeStart.remove(id);
            }
         })
      );
   }

   /**
    * Hops a packet handler onto the main server thread and runs it inside {@link #guard}.
    *
    * <p>A packet arrives on a netty thread, so the handler must be re-scheduled onto the server
    * thread before touching any world state. The task then executes inside the tick loop, which is
    * why the body must be exception-proof: an escape there is indistinguishable from a crashing
    * tick listener and kills the integrated server.
    */
   private void onMainThread(MinecraftServer server, ServerPlayer player, String what, Consumer<ServerPlayer> body) {
      if (server == null || player == null) {
         LOGGER.debug("[{}] dropping '{}' - no server or no player attached to the packet", MOD_ID, what);
         return;
      }

      try {
         server.execute(() -> guard(what, player, () -> {
            // The player can log out between the packet arriving and this task running.
            if (!isOnline(player)) {
               LOGGER.debug("[{}] dropping '{}' - {} is no longer connected", MOD_ID, what, nameOf(player));
               return;
            }
            body.accept(player);
         }));
      } catch (Throwable error) {
         // server.execute() itself can reject the task while the server is shutting down.
         report(() -> what + " (scheduling)", error);
      }
   }

   /* ==================================================================== *
    *  3.1 - clear dropped items
    * ==================================================================== */

   private void handleKillItems(ServerPlayer player) {
      if (!(player.level() instanceof ServerLevel level)) {
         return;
      }
      List<? extends ItemEntity> items = level.getEntities(EntityType.ITEM, entity -> !entity.isRemoved());
      int removed = 0;
      for (ItemEntity item : items) {
         item.discard();
         removed++;
      }
      Component msg = Component.literal("🗑 Cleared " + removed + " dropped item" + (removed == 1 ? "" : "s"));
      player.sendOverlayMessage(msg);
      player.sendSystemMessage(Component.literal("[Merchant Mind] ").append(msg));
   }

   /* ==================================================================== *
    *  shop
    * ==================================================================== */

   private void openShop(ServerPlayer player) {
      player.openMenu(new ExtendedMenuProvider<Boolean>() {
         @Override
         public Boolean getScreenOpeningData(ServerPlayer target) {
            return Boolean.TRUE;
         }

         @Override
         public Component getDisplayName() {
            return Component.literal("Quest Shop");
         }

         @Override
         public AbstractContainerMenu createMenu(int id, Inventory inventory, Player owner) {
            return new ShopMenu(id, inventory);
         }
      });
      this.sendSync(player, "");
   }

   private ShopMenu menuOf(ServerPlayer player) {
      return player.containerMenu instanceof ShopMenu menu ? menu : null;
   }

   /**
    * 3.1 - slam the shop shut the moment the player takes a hit.
    *
    * <p>The close is deferred onto the server executor instead of happening inline. We are inside
    * the victim's own tick here, and {@code closeContainer()} runs {@link ShopMenu#removed} - which
    * hands every staked item back into the inventory and can drop entities into the level. Doing
    * that in the middle of the entity iteration is how you get a ConcurrentModificationException;
    * one tick later it is just an ordinary container close.
    */
   private void closeShopOnDamage(ServerPlayer player) {
      if (this.menuOf(player) == null) {
         return;
      }
      MinecraftServer server = player.level().getServer();
      if (server == null) {
         return;
      }
      server.execute(() -> guard(() -> "close shop on damage/" + nameOf(player), () -> {
         // re-check: the player may have closed it themselves, or died, in the meantime
         if (!player.isRemoved() && this.menuOf(player) != null) {
            player.closeContainer();
            // true = action bar, so it does not spam the chat log during a fight
            player.sendSystemMessage(Component.literal("§cYou were hit — the shop closed."), true);
         }
      }));
   }

   private void handleBuy(ServerPlayer player, ShopPackets.BuyC2SPayload payload) {
      ShopManager.ShopEntry entry = ShopManager.entry(payload.itemId());
      if (entry == null) {
         this.sendSync(player, "That item just sold out.");
         return;
      }

      /*
       * 3.2 (2.6.0) - the authoritative half of the buy lock, and the one that matters.
       *
       * The client greys the Buy buttons out and refuses the click, but that is only cosmetic:
       * a BuyC2SPayload is trivial to send by hand, and this method is the single place every
       * purchase passes through. It is checked BEFORE any coin is touched, before the listing is
       * consumed, and it reads exactly the same ShopManager.isRestockBusy() the client reads - so
       * "restocking" can never mean one thing on one side and something else on the other.
       */
      if (ShopManager.isRestockBusy(entry.category)) {
         this.sendSync(player, entry.category.display + " is restocking — buying is locked until it finishes.");
         return;
      }

      long price = entry.buyPrice;
      if (!ShopManager.removeBalance(player, price)) {
         this.sendSync(player, "Not enough coins (" + price + "c needed).");
         return;
      }

      Optional<Item> item = this.itemFromId(payload.itemId());
      if (item.isEmpty()) {
         ShopManager.addBalance(player, price);
         this.sendSync(player, "That item no longer exists.");
         return;
      }

      boolean soldOut = ShopManager.consumeListing(entry);
      ItemStack stack = new ItemStack((ItemLike)item.get(), 1);
      if (!player.getInventory().add(stack)) {
         player.drop(stack, false);
      }

      /*
       * 4.1 - the stock just changed for the whole world, not only for the buyer. Everyone with the
       * shop open is re-synced in this tick, so a second player cannot keep seeing - or clicking -
       * a listing that no longer exists.
       */
      this.broadcastAfterChange(player, "Bought " + this.shortName(payload.itemId()) + " for " + price + "c.");
      if (soldOut) {
         MinecraftServer server = player.level().getServer();
         this.announceToShoppers(
            server,
            /*
             * 3.3.0 - a sold-out shelf runs the same clock as the Restock button, not the schedule's
             * shorter one: players emptied it, so it is demand, not the shop closing on its own. The
             * label is asked for by trigger so this line cannot drift from what the server timed.
             */
            "⟳ " + entry.category.display + " sold out and is restocking — "
               + ShopManager.restockDelayLabel(ShopManager.RestockReason.SOLD_OUT)
               + ", or submit " + ShopManager.RESTOCK_IRON_COST + " iron to finish now."
         );
      }
   }

   /* ==================================================================== *
    *  2.2 (2.7.0) - restock
    *
    *  Two requests share this packet and they are no longer two steps of one
    *  transaction - they are independent, and either can be used without the
    *  other:
    *
    *    useIron == false   "⟳ Restock"   start a refill NOW, free, on the
    *                                     triggered duration (3.3.0: 1m, twice
    *                                     the scheduled refill). Does not ask
    *                                     for iron.
    *    useIron == true    "Submit"      spend RESTOCK_IRON_COST iron to finish
    *                                     a refill that is ALREADY running.
    *
    *  Through 2.6.0 the first of these only "armed" the category and the
    *  refill could not begin until the second had been done too, which made
    *  iron mandatory for a manual restock. It is optional now.
    *
    *  4.1 - both paths mutate shared world state, so both end in a broadcast
    *  to every player with the shop open, not just a reply to the actor.
    * ==================================================================== */
   private void handleRestock(ServerPlayer player, ShopPackets.RestockC2SPayload payload) {
      ShopManager.Category category;
      try {
         category = ShopManager.Category.valueOf(payload.category());
      } catch (IllegalArgumentException e) {
         this.sendSync(player, "Unknown category.");
         return;
      }

      ShopMenu menu = this.menuOf(player);
      if (menu == null) {
         this.sendSync(player, "Open the shop first.");
         return;
      }

      if (!payload.useIron()) {
         this.startManualRestock(player, menu, category);
      } else {
         this.submitIronSkip(player, menu, category);
      }
   }

   /**
    * "⟳ Restock": begins the refill straight away.
    *
    * <p>3.1.0 - on its own, longer clock ({@link ShopManager#TRIGGERED_RESTOCK_DURATION_SECONDS}),
    * where up to 3.0.4 it shared one duration with the automatic refill. 3.3.0 shortened that clock
    * to 60s and gave the same one to a sold-out shelf.
    */
   private void startManualRestock(ServerPlayer player, ShopMenu menu, ShopManager.Category category) {
      if (!ShopManager.beginManualRestock(category)) {
         // already running - tell them how long is left rather than silently doing nothing
         this.sendSync(player, category.display + " is already restocking (" + ShopManager.restockSecondsLeft(category) + "s left).");
         return;
      }

      // the iron slot opens as the refill starts, so the mask has to go out with this same sync
      menu.ironSlotMask = ShopManager.ironSlotMask();
      menu.broadcastChanges();

      this.broadcastAfterChange(
         player,
         // 3.1.0 - a manual restock is the LONG clock, so say so where the player pressed the button
         "Restocking " + category.display + " — " + ShopManager.restockDelayLabel(ShopManager.RestockReason.MANUAL)
            + ". Optional: submit " + ShopManager.RESTOCK_IRON_COST + " iron to finish now."
      );
      this.announceToShoppers(
         player.level().getServer(),
         "⟳ " + nameOf(player) + " started a restock of " + category.display + " — "
            + ShopManager.restockDelayLabel(ShopManager.RestockReason.MANUAL) + "."
      );
   }

   /**
    * "Submit": the optional shortcut. Spends {@link ShopManager#RESTOCK_IRON_COST} iron to end a
    * refill that is already running.
    *
    * <p>The iron is only ever taken when there is a refill to shorten, and only after the count has
    * been verified from the server's own copy of the slot - so nothing can be charged for nothing.
    */
   private void submitIronSkip(ServerPlayer player, ShopMenu menu, ShopManager.Category category) {
      if (!ShopManager.isIronSkipOpen(category)) {
         this.sendSync(player, category.display + " is not restocking — press ⟳ Restock first.");
         return;
      }

      SimpleContainer iron = menu.ironFor(category);
      ItemStack stack = iron.getItem(0);
      int have = stack.is(Items.IRON_INGOT) ? stack.getCount() : 0;
      if (have < ShopManager.RESTOCK_IRON_COST) {
         this.sendSync(
            player,
            "Need " + ShopManager.RESTOCK_IRON_COST + " iron in the " + category.display + " slot — you have " + have + "."
         );
         return;
      }

      if (!ShopManager.finishRestockNow(category)) {
         // lost the race: the refill finished on its own between the click and this tick
         this.sendSync(player, category.display + " finished restocking on its own — your iron was not taken.");
         return;
      }

      stack.shrink(ShopManager.RESTOCK_IRON_COST);
      iron.setItem(0, stack.isEmpty() ? ItemStack.EMPTY : stack);
      iron.setChanged();

      // the refill is over, so the slot closes again on this very sync
      menu.ironSlotMask = ShopManager.ironSlotMask();
      menu.broadcastChanges();

      this.broadcastAfterChange(player, "Submitted " + ShopManager.RESTOCK_IRON_COST + " iron — " + category.display + " restocked instantly.");
      this.announceToShoppers(
         player.level().getServer(),
         "✔ " + nameOf(player) + " paid " + ShopManager.RESTOCK_IRON_COST + " iron — " + category.display + " restocked instantly."
      );
   }

   private void handleTrade(ServerPlayer player, ShopPackets.TradeC2SPayload payload) {
      ShopMenu menu = this.menuOf(player);
      if (menu == null) {
         this.sendSync(player, "Open the shop first.");
         return;
      }

      /*
       * 4.3 - a trade is a barter, so BOTH sides are measured in the same currency: the item's
       * true worth. The old code valued the player's input at the shop's sell rate (45% of worth)
       * and the return item at the shop's buy rate (125% of worth), which quietly took ~65% off
       * every swap.
       */
      Map<Item, Integer> offered = new LinkedHashMap<>();
      boolean blockedItem = false;
      long offeredWorth = 0L;

      for (int i = 0; i < ShopMenu.TRADE_SLOTS; i++) {
         ItemStack input = menu.tradeInput.getItem(i);
         if (input.isEmpty()) {
            continue;
         }
         String inputId = idOf(input.getItem());
         if (ShopManager.TRADE_BLOCKED.contains(inputId)) {
            blockedItem = true;
         } else {
            offered.merge(input.getItem(), input.getCount(), Integer::sum);
            offeredWorth += (long)ShopManager.worthOf(input.getItem()) * input.getCount();
         }
      }

      if (offered.isEmpty()) {
         this.syncTrade(player, blockedItem ? "Ender pearls and wind charges can't be traded." : "Put items in the trade slots first.", false, "", 0);
         return;
      }

      /*
       * 3.0.1 - every candidate now carries its real stack limit as well as its worth.
       *
       * The advisor has no access to the item registry, and it needs the limit to decide how many
       * of something a trade may hand back: without it, budget/unitWorth happily asked for nine
       * diamond swords in one slot. An id that does not resolve is given a limit of 1, which is the
       * safe direction to be wrong in.
       */
      List<TradeAdvisor.Candidate> candidates = new ArrayList<>();
      for (ShopManager.ShopEntry entry : ShopManager.visibleEntries()) {
         int maxStack = this.itemFromId(entry.itemId).map(Item::getDefaultMaxStackSize).orElse(1);
         candidates.add(new TradeAdvisor.Candidate(entry.itemId, ShopManager.worthOf(entry.itemId), maxStack));
      }

      TradeAdvisor.Verdict verdict = TradeAdvisor.analyze(offered, offeredWorth, payload.targetItemId(), candidates);

      if (!payload.commit()) {
         this.schedule(16, () -> this.syncTrade(player, verdict.reasoning(), verdict.approved(), verdict.resultItemId(), verdict.quantity()));
         return;
      }

      if (verdict.quantity() <= 0 || verdict.resultItemId().isEmpty()) {
         this.syncTrade(player, "Nothing here your goods can afford — add more or cycle target.", false, verdict.resultItemId(), verdict.quantity());
         return;
      }

      Optional<Item> result = this.itemFromId(verdict.resultItemId());
      ShopManager.ShopEntry entry = ShopManager.entry(verdict.resultItemId());
      if (result.isEmpty() || entry == null) {
         this.syncTrade(player, "That item just sold out — re-analyze.", false, "", 0);
         return;
      }

      long consumedWorth = 0L;
      for (int i = 0; i < ShopMenu.TRADE_SLOTS; i++) {
         ItemStack input = menu.tradeInput.getItem(i);
         if (!input.isEmpty() && !ShopManager.TRADE_BLOCKED.contains(idOf(input.getItem()))) {
            consumedWorth += (long)ShopManager.worthOf(input.getItem()) * input.getCount();
            menu.tradeInput.setItem(i, ItemStack.EMPTY);
         }
      }

      /*
       * 3.0.1 - last line of defence on the payout itself.
       *
       * TradeAdvisor already refuses to ask for more of an item than it can stack, so this clamp
       * should never bite. It is here because the failure it prevents - handing the player an
       * ItemStack holding nine swords - is a malformed item rather than a bad deal, and the one
       * place that constructs the stack is the right place to make that impossible. The worth the
       * clamp frees up is not lost: the change below is computed from the qty actually paid out.
       */
      int qty = Math.max(1, Math.min(verdict.quantity(), result.get().getDefaultMaxStackSize()));
      if (qty != verdict.quantity()) {
         LOGGER.warn("[{}] trade payout of {}x {} clamped to {} - it does not stack that high", MOD_ID, verdict.quantity(), verdict.resultItemId(), qty);
      }

      ItemStack payout = new ItemStack((ItemLike)result.get(), qty);
      if (!player.getInventory().add(payout)) {
         player.drop(payout, false);
      }

      // whatever the returned bundle could not absorb comes back as coins, same worth scale
      long change = TradeAdvisor.budgetFor(consumedWorth) - (long)qty * ShopManager.worthOf(entry.itemId);
      if (change > 0L) {
         ShopManager.addBalance(player, change);
      }

      ShopManager.consumeListing(entry);
      menu.broadcastChanges();
      this.syncTrade(
         player,
         "Traded for " + qty + "x " + this.shortName(verdict.resultItemId()) + (change > 0L ? " (+" + change + "c change)." : "."),
         false,
         "",
         0
      );
   }

   private void handleSell(ServerPlayer player, ShopPackets.SellC2SPayload payload) {
      ShopMenu menu = this.menuOf(player);
      if (menu == null) {
         this.sendSync(player, "Open the shop first.");
         return;
      }

      Map<Item, Integer> offered = new LinkedHashMap<>();
      for (int i = 0; i < ShopMenu.SELL_SLOTS; i++) {
         ItemStack stack = menu.sellInput.getItem(i);
         if (!stack.isEmpty()) {
            offered.merge(stack.getItem(), stack.getCount(), Integer::sum);
         }
      }

      if (offered.isEmpty()) {
         this.syncSell(player, "Put items in the sell slots first.", 0L);
         return;
      }

      PriceAppraiser.Quote quote = PriceAppraiser.appraise(offered, ShopManager::sellValueFor, ShopManager::worthOf);

      if (!payload.commit()) {
         this.schedule(20, () -> this.syncSell(player, quote.reasoning(), quote.coins()));
      } else if (quote.coins() <= 0L) {
         this.syncSell(player, "Nothing worth buying here.", 0L);
      } else {
         for (int i = 0; i < ShopMenu.SELL_SLOTS; i++) {
            menu.sellInput.setItem(i, ItemStack.EMPTY);
         }
         ShopManager.addBalance(player, quote.coins());
         menu.broadcastChanges();
         this.syncSell(player, "Sold for " + quote.coins() + " coins.", 0L);
      }
   }

   /* ==================================================================== *
    *  sync
    * ==================================================================== */

   private void syncTrade(ServerPlayer player, String reasoning, boolean approved, String resultId, int qty) {
      this.sendSyncFull(player, "", reasoning, "", approved, resultId, qty, 0L, true, false);
   }

   private void syncSell(ServerPlayer player, String reasoning, long coins) {
      this.sendSyncFull(player, "", "", reasoning, false, "", 0, coins, false, true);
   }

   private void sendSync(ServerPlayer player, String message) {
      this.sendSyncFull(player, message, "", "", false, "", 0, 0L, false, false);
   }

   private void sendSyncFull(
      ServerPlayer player,
      String message,
      String tradeAi,
      String sellAi,
      boolean tradeApproved,
      String tradeResultId,
      int tradeResultQty,
      long sellQuote,
      boolean updateTradePanel,
      boolean updateSellPanel
   ) {
      // a queued callback can fire after the player left - never send to a dead connection
      if (!isOnline(player)) {
         return;
      }

      /*
       * 2.1 / 4.1 - keep the server's own copy of the iron-slot mask current on every sync, not
       * just when this player clicks something. A category can start restocking because the timer
       * came due or because somebody else bought the last item, and the server-side
       * Slot.isActive() must agree with the client the instant that happens - otherwise one client
       * hides a slot the server still accepts, or shows one it does not.
       */
      ShopMenu openMenu = this.menuOf(player);
      if (openMenu != null) {
         openMenu.ironSlotMask = ShopManager.ironSlotMask();
      }

      ServerPlayNetworking.send(
         player,
         new ShopPackets.SyncS2CPayload(
            this.shopRows(),
            ShopManager.getBalance(player),
            message,
            tradeAi,
            sellAi,
            tradeApproved,
            tradeResultId,
            tradeResultQty,
            sellQuote,
            updateTradePanel,
            updateSellPanel
         )
      );
   }

   /**
    * 1.2 (2.9.0) - the shop rows every player is sent, built once per change instead of once per
    * player per second.
    *
    * <p>The row list is the same for everybody - it is the world's shop, and only the balance and
    * the personal message differ per player - but it was rebuilt from scratch inside every single
    * send: ~77 listing rows plus 11 status rows plus 11 concatenated status keys, per player, every
    * second, for as long as anyone had the shop open. With four players that is over 350 records a
    * second thrown away immediately after being encoded.
    *
    * <p>{@link ShopManager#version()} changes whenever anything in those rows could have changed
    * (any stock or restock mutation, and once a second because the countdowns move), so caching
    * against it is exact rather than a guess: a stale row can never be sent. The list is immutable
    * and the records inside it are immutable, so handing the same instance to every player is safe
    * - the codec only reads it.
    */
   private List<ShopPackets.ShopEntryData> shopRows() {
      int version = ShopManager.version();
      if (version == this.shopRowsVersion) {
         return this.shopRows;
      }

      List<ShopPackets.ShopEntryData> rows = new ArrayList<>(ShopManager.listingCount() + CATEGORIES.length);
      for (ShopManager.ShopEntry entry : ShopManager.visibleEntries()) {
         rows.add(new ShopPackets.ShopEntryData(entry.itemId, entry.category.name(), entry.buyPrice, entry.sellPrice, 1, 0, 0, false, false, false, ""));
      }

      /*
       * 4.1 - one status row per category, read straight from ShopManager. This is the whole of the
       * shared shop state and it is the only place the client gets it from; the client keeps no
       * independent copy of stock, of restock state or of any countdown.
       */
      for (int i = 0; i < CATEGORIES.length; i++) {
         ShopManager.Category category = CATEGORIES[i];
         rows.add(
            new ShopPackets.ShopEntryData(
               CATEGORY_KEYS[i],
               category.name(),
               ShopManager.RESTOCK_IRON_COST,
               0,
               ShopManager.state(category).listings.size(),
               // while idle: when the shop will restock this category by itself
               ShopManager.secondsUntilAutoRestock(category),
               // while restocking: how long the refill still has to run
               ShopManager.restockSecondsLeft(category),
               ShopManager.isRestocking(category),
               ShopManager.isManualRestock(category),
               ShopManager.isIronSkipOpen(category),
               /*
                * 3.3.0 - the KEY, not the display text. The client has to know which of the two
                * clocks this refill is on to scale its progress bar, and the trigger is the thing
                * that says so; sending the enum's name means it can ask properly instead of matching
                * on a sentence. The field, its type and the row's shape are unchanged, so this is not
                * a new packet - the client turns the key back into the same display text it used to
                * be handed.
                */
               ShopManager.restockReason(category).name()
            )
         );
      }

      this.shopRows = Collections.unmodifiableList(rows);
      this.shopRowsVersion = version;
      return this.shopRows;
   }

   private Optional<Item> itemFromId(String id) {
      try {
         Identifier key = Identifier.parse(id);
         return BuiltInRegistries.ITEM.containsKey(key) ? Optional.of((Item)BuiltInRegistries.ITEM.getValue(key)) : Optional.empty();
      } catch (Exception e) {
         return Optional.empty();
      }
   }

   private static String idOf(Item item) {
      return BuiltInRegistries.ITEM.getKey(item).toString();
   }

   private String shortName(String id) {
      int i = id.indexOf(58);
      return i >= 0 ? id.substring(i + 1) : id;
   }

   private record DelayedTask(int fireTick, Runnable run) {
   }
}

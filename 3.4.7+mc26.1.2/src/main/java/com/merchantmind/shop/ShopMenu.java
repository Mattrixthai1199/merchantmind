package com.merchantmind.shop;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Shop container.
 *
 * Slot map:
 *   0  .. 4    trade inputs   (revealed one at a time)
 *   5  .. 9    sell inputs    (revealed one at a time)
 *   10 .. 10+N every shop category gets its OWN iron slot / own container / own id
 *   after that the 36 player inventory slots
 */
public class ShopMenu extends AbstractContainerMenu {
   public static final int TRADE_SLOTS = 5;
   public static final int SELL_SLOTS = 5;
   public static final int CATEGORY_COUNT = ShopManager.Category.values().length;

   public static final int TRADE_START = 0;
   public static final int SELL_START = TRADE_START + TRADE_SLOTS;
   public static final int IRON_START = SELL_START + SELL_SLOTS;
   public static final int CUSTOM_SLOTS = IRON_START + CATEGORY_COUNT;

   /* ------------------------------------------------------------------ *
    *  2.1 - layout, shared with ShopScreen.
    *
    *  The panel is a stack of BANDS that never overlap:
    *
    *      0 .. 20    header        (title, coins, close)
    *     23 .. 49    head block    (category icon, title, subtitle)
    *     52 ..112    main          (catalog rows | input slots + AI box)
    *    116 ..154    action        (TWO rows, see below)
    *    158 ..172    status bar
    *    175          "Inventory" label
    *    185 ..260    player inventory
    *
    *  The action band is split into two rows, and that split is the fix for
    *  the Trade button. Packing Analyze / Target / Trade into one row meant
    *  the commit button's x depended on how wide the other two labels
    *  rendered; a wide label (or a translation, or "Analyzing...") squeezed
    *  it, and the clamp that stopped it running off the panel pushed it
    *  straight under its neighbour instead:
    *
    *      ROW A (116..132)  secondary: Analyze / Target, or page < >
    *      ROW B (136..154)  ONE primary control, full content width
    *
    *  Row B is never shared. Trade, Sell and "Submit iron" each get the
    *  whole 96..330 span to themselves, so no label width can ever shrink
    *  or displace them. The iron slot is the only thing that also lives in
    *  row B and it is packed hard left, with the button starting after it.
    *
    *  Slots stay the dangerous ones: vanilla draws container slots AFTER
    *  the widget layer, so a slot sharing pixels with a button hides the
    *  button while the button still takes the click. No slot rectangle
    *  intersects any button rectangle, and ShopScreen asserts it at
    *  runtime for every state.
    * ------------------------------------------------------------------ */
   public static final int PANEL_W = 336;
   public static final int PANEL_H = 262;

   public static final int CONTENT_X = 94;
   public static final int CONTENT_R = 330;

   public static final int BAND_MAIN_Y = 52;
   public static final int BAND_MAIN_H = 60;

   /** Action band: two rows. Row B is 18 tall so a full slot box fits inside it. */
   public static final int BAND_ACTION_Y = 116;
   public static final int ACTION_ROW_A_Y = BAND_ACTION_Y;
   public static final int ACTION_ROW_B_Y = BAND_ACTION_Y + 20;
   public static final int BAND_ACTION_H = 38;

   /** trade / sell input row - top of the main band */
   public static final int INPUT_X0 = 98;
   public static final int INPUT_STEP = 22;
   public static final int INPUT_Y = BAND_MAIN_Y + 2;
   public static final int RESULT_X = 224;

   /** the iron slot is packed hard left in action row B; its button starts to the right of it */
   public static final int IRON_X = CONTENT_X + 2;
   public static final int IRON_Y = ACTION_ROW_B_Y + 1;

   public static final int INV_X = 131;
   public static final int INV_Y = 185;

   public static final int SET_NONE = -1;
   public static final int SET_TRADE = 0;
   public static final int SET_SELL = 1;

   public final SimpleContainer tradeInput = new SimpleContainer(TRADE_SLOTS);
   public final SimpleContainer sellInput = new SimpleContainer(SELL_SLOTS);
   /** One independent iron container per category - no shared ids between categories. */
   public final SimpleContainer[] ironInputs = new SimpleContainer[CATEGORY_COUNT];

   public int activeInputSet = SET_NONE;
   /** Ordinal of the category tab currently on screen, or -1. */
   public int activeCategory = 0;
   /**
    * 2.2 (2.7.0) / 4.1 - bit i set = category i is restocking, so its optional iron slot is open.
    *
    * <p>Never computed locally on either side: the server writes it straight from
    * {@link ShopManager#ironSlotMask()} on every sync and the client copies it out of the sync
    * payload. When a bit is clear the matching iron slot does not exist as far as either side is
    * concerned - it is not drawn, it refuses items and it rejects clicks.
    *
    * <p>Through 2.6.0 this tracked the "armed, waiting for iron" state, which was the gate in front
    * of a manual restock. Iron is now a shortcut through a refill that is already running, so the
    * slot is open exactly while one is.
    */
   public int ironSlotMask = 0;

   public boolean ironSlotOpen(int categoryOrdinal) {
      return categoryOrdinal >= 0 && (this.ironSlotMask & 1 << categoryOrdinal) != 0;
   }

   public boolean ironSlotOpen(ShopManager.Category category) {
      return this.ironSlotOpen(category.ordinal());
   }

   private final Player player;
   /** 1.1 (2.8.0) - this mod's own slots, for O(1) gating instead of a linear indexOf per frame. */
   private final java.util.Set<Slot> customSlots = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

   public ShopMenu(int syncId, Inventory inventory) {
      super(ShopRegistry.MENU_TYPE, syncId);
      this.player = inventory.player;

      for (int i = 0; i < TRADE_SLOTS; i++) {
         final int idx = i;
         this.addSlot(new Slot(this.tradeInput, i, INPUT_X0 + i * INPUT_STEP, INPUT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
               if (stack.isEmpty() || ShopManager.TRADE_BLOCKED.contains(ShopMenu.idOf(stack))) {
                  return false;
               }
               return ShopMenu.this.revealed(ShopMenu.this.tradeInput, idx);
            }

            @Override
            public boolean isActive() {
               return ShopMenu.this.activeInputSet == SET_TRADE && ShopMenu.this.revealed(ShopMenu.this.tradeInput, idx);
            }
         });
      }

      for (int i = 0; i < SELL_SLOTS; i++) {
         final int idx = i;
         this.addSlot(new Slot(this.sellInput, i, INPUT_X0 + i * INPUT_STEP, INPUT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
               return !stack.isEmpty() && ShopMenu.this.revealed(ShopMenu.this.sellInput, idx);
            }

            @Override
            public boolean isActive() {
               return ShopMenu.this.activeInputSet == SET_SELL && ShopMenu.this.revealed(ShopMenu.this.sellInput, idx);
            }
         });
      }

      for (int c = 0; c < CATEGORY_COUNT; c++) {
         final int cat = c;
         this.ironInputs[c] = new SimpleContainer(1);
         this.addSlot(new Slot(this.ironInputs[c], 0, IRON_X, IRON_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
               return stack.is(Items.IRON_INGOT) && this.isActive();
            }

            @Override
            public boolean isActive() {
               // 2.2 (2.7.0) - open exactly while this category is restocking, as the shortcut.
               return ShopMenu.this.activeInputSet == SET_NONE
                  && ShopMenu.this.activeCategory == cat
                  && ShopMenu.this.ironSlotOpen(cat);
            }
         });
      }

      this.addStandardInventorySlots(inventory, INV_X, INV_Y);

      /*
       * 1.1 (2.8.0) - an identity set of the mod's own slots, built once here.
       *
       * The screen has to ask "is this one of my gated slots?" for every slot it draws and for
       * every click. It used to answer with slots.indexOf(slot), a linear scan of all 57 slots, and
       * it did that once per slot per frame - ~3,000 reference comparisons every frame for a fact
       * that is fixed the moment the menu is built. The custom slots are exactly the first
       * CUSTOM_SLOTS entries, so they go in a set and the question becomes one hash lookup.
       */
      for (int i = 0; i < CUSTOM_SLOTS && i < this.slots.size(); i++) {
         this.customSlots.add(this.slots.get(i));
      }
   }

   /**
    * True for the trade / sell / iron slots this mod adds, false for the player's own inventory.
    *
    * <p>Identity-based on purpose: two Slot objects are never "equal but interchangeable" here, and
    * Slot does not override equals(), so identity is both correct and the cheapest thing available.
    */
   public boolean isCustomSlot(Slot slot) {
      return slot != null && this.customSlots.contains(slot);
   }

   /** A progressive input slot is only revealed once the previous one holds something. */
   boolean revealed(Container container, int idx) {
      return idx == 0 || !container.getItem(idx - 1).isEmpty();
   }

   /* ------------------------------------------------------------------ *
    *  Which slots are live is decided by the open tab, and the tab is
    *  chosen on the client. That state MUST reach the server through the
    *  same ordered channel as the clicks it affects.
    *
    *  The old build shipped it as a custom payload whose handler was
    *  re-queued with server.execute(), so it landed AFTER the click packet
    *  that followed it: the server still had the previous tab, every
    *  trade/sell click hit an inactive slot and was dropped, and the items
    *  never appeared. clickMenuButton travels on the vanilla container
    *  channel, which is processed in order with container clicks.
    * ------------------------------------------------------------------ */
   public static int encodeTab(int inputSet, int category) {
      return (inputSet + 1) * 64 + (category + 1);
   }

   @Override
   public boolean clickMenuButton(Player player, int id) {
      if (id < 0 || id >= 64 * 4) {
         return false;
      }
      int set = id / 64 - 1;
      int cat = id % 64 - 1;
      if (set < SET_NONE || set > SET_SELL || cat < -1 || cat >= CATEGORY_COUNT) {
         return false;
      }
      this.activeInputSet = set;
      this.activeCategory = cat;
      this.broadcastChanges();
      return true;
   }

   public SimpleContainer ironFor(ShopManager.Category category) {
      return this.ironInputs[category.ordinal()];
   }

   public int ironCount(ShopManager.Category category) {
      return this.ironFor(category).getItem(0).getCount();
   }

   private static String idOf(ItemStack stack) {
      return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
   }

   public boolean isCustomSlot(int slotId) {
      return slotId >= 0 && slotId < CUSTOM_SLOTS;
   }

   @Override
   public boolean stillValid(Player player) {
      return true;
   }

   /* ------------------------------------------------------------------ *
    *  Custom click handling for the mod's own slots.
    *  right click  -> move exactly ONE item (slot <-> cursor)
    *  left  click  -> move the WHOLE stack (slot <-> cursor)
    *  every slot accepts a full 64 stack.
    * ------------------------------------------------------------------ */
   @Override
   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (this.isCustomSlot(slotId) && input == ContainerInput.PICKUP) {
         Slot slot = this.slots.get(slotId);
         if (!slot.isActive()) {
            // Never drop a click silently: the client already moved the item locally, so without
            // a resync it would keep showing an item the server does not have.
            this.broadcastFullState();
            return;
         }

         if (button == 1) {
            this.clickOne(slot);
         } else {
            this.clickStack(slot);
         }

         this.compact(this.tradeInput);
         this.compact(this.sellInput);
         this.broadcastChanges();
         return;
      }

      super.clicked(slotId, button, input, player);

      if (this.isCustomSlot(slotId)) {
         this.compact(this.tradeInput);
         this.compact(this.sellInput);
         this.broadcastChanges();
      }
   }

   /** Right click: one item at a time, either direction. */
   private void clickOne(Slot slot) {
      ItemStack carried = this.getCarried();
      ItemStack inSlot = slot.getItem();

      if (carried.isEmpty()) {
         if (!inSlot.isEmpty()) {
            ItemStack one = inSlot.copyWithCount(1);
            inSlot.shrink(1);
            slot.set(inSlot.isEmpty() ? ItemStack.EMPTY : inSlot);
            slot.setChanged();
            this.setCarried(one);
         }
         return;
      }

      if (inSlot.isEmpty()) {
         if (slot.mayPlace(carried)) {
            slot.set(carried.copyWithCount(1));
            this.shrinkCarried(carried, 1);
         }
      } else if (ItemStack.isSameItemSameComponents(inSlot, carried) && inSlot.getCount() < this.limit(slot, inSlot)) {
         inSlot.grow(1);
         slot.setChanged();
         this.shrinkCarried(carried, 1);
      }
   }

   /** Left click: whole stack, either direction (and swap when the items differ). */
   private void clickStack(Slot slot) {
      ItemStack carried = this.getCarried();
      ItemStack inSlot = slot.getItem();

      if (carried.isEmpty()) {
         if (!inSlot.isEmpty()) {
            this.setCarried(inSlot.copy());
            slot.set(ItemStack.EMPTY);
            slot.setChanged();
         }
         return;
      }

      if (!slot.mayPlace(carried)) {
         return;
      }

      if (inSlot.isEmpty()) {
         int move = Math.min(this.limit(slot, carried), carried.getCount());
         slot.set(carried.copyWithCount(move));
         slot.setChanged();
         this.shrinkCarried(carried, move);
      } else if (ItemStack.isSameItemSameComponents(inSlot, carried)) {
         int move = Math.min(this.limit(slot, inSlot) - inSlot.getCount(), carried.getCount());
         if (move > 0) {
            inSlot.grow(move);
            slot.setChanged();
            this.shrinkCarried(carried, move);
         }
      } else {
         ItemStack swapped = inSlot.copy();
         slot.set(carried.copy());
         slot.setChanged();
         this.setCarried(swapped);
      }
   }

   /** Full stacks are allowed - capped only by the item's own max stack size. */
   private int limit(Slot slot, ItemStack stack) {
      return Math.min(slot.getMaxStackSize(stack), stack.getMaxStackSize());
   }

   private void shrinkCarried(ItemStack carried, int amount) {
      carried.shrink(amount);
      this.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
   }

   /** Keeps the progressive rows gap-free so slots reveal/hide predictably. */
   private void compact(Container container) {
      int write = 0;
      for (int read = 0; read < container.getContainerSize(); read++) {
         ItemStack stack = container.getItem(read);
         if (!stack.isEmpty()) {
            if (read != write) {
               container.setItem(write, stack);
               container.setItem(read, ItemStack.EMPTY);
            }
            write++;
         }
      }
   }

   @Override
   public ItemStack quickMoveStack(Player player, int index) {
      Slot slot = this.slots.get(index);
      if (slot == null || !slot.hasItem()) {
         return ItemStack.EMPTY;
      }

      ItemStack stack = slot.getItem();
      ItemStack original = stack.copy();
      int invStart = CUSTOM_SLOTS;
      int invEnd = this.slots.size();
      boolean moved;

      if (index < CUSTOM_SLOTS) {
         moved = this.moveItemStackTo(stack, invStart, invEnd, true);
      } else if (stack.is(Items.IRON_INGOT)
         && this.activeInputSet == SET_NONE
         && this.activeCategory >= 0
         && this.ironSlotOpen(this.activeCategory)) {
         int ironSlot = IRON_START + this.activeCategory;
         moved = this.moveItemStackTo(stack, ironSlot, ironSlot + 1, false);
      } else if (this.activeInputSet == SET_TRADE) {
         moved = this.moveItemStackTo(stack, TRADE_START, TRADE_START + TRADE_SLOTS, false);
      } else if (this.activeInputSet == SET_SELL) {
         moved = this.moveItemStackTo(stack, SELL_START, SELL_START + SELL_SLOTS, false);
      } else {
         moved = false;
      }

      if (!moved) {
         return ItemStack.EMPTY;
      }

      if (stack.isEmpty()) {
         slot.set(ItemStack.EMPTY);
      } else {
         slot.setChanged();
      }

      this.compact(this.tradeInput);
      this.compact(this.sellInput);
      return original;
   }

   @Override
   public void removed(Player player) {
      super.removed(player);
      this.returnContainer(player, this.tradeInput);
      this.returnContainer(player, this.sellInput);
      for (SimpleContainer iron : this.ironInputs) {
         this.returnContainer(player, iron);
      }
   }

   private void returnContainer(Player player, Container container) {
      for (int i = 0; i < container.getContainerSize(); i++) {
         ItemStack stack = container.getItem(i);
         if (!stack.isEmpty()) {
            if (!player.getInventory().add(stack)) {
               player.drop(stack, false);
            }
            container.setItem(i, ItemStack.EMPTY);
         }
      }
   }
}

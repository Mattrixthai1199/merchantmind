package com.merchantmind.shop;

import com.merchantmind.MerchantMindClient;
import com.merchantmind.client.ShopFx;
import com.merchantmind.network.ShopPackets;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class ShopScreen extends AbstractContainerScreen<ShopMenu> {
   /* ---------------- palette ---------------- */
   private static final int C_PANEL = 0xF0161A22;
   private static final int C_BODY = 0xF01D2230;
   private static final int C_BORDER = 0xFF3C4660;
   private static final int C_HEADER = 0xFF10141C;
   private static final int C_SIDE = 0xFF141922;
   private static final int C_ROW = 0xFF212836;
   private static final int C_ROW_ALT = 0xFF1B2230;
   private static final int C_ROW_HOVER = 0xFF2E3A50;
   private static final int C_TAB_ACTIVE = 0xFF35558C;
   private static final int C_TAB_HOVER = 0xFF232C3C;
   private static final int C_SLOT = 0xFF0E1218;
   private static final int C_SLOT_BORDER = 0xFF4A5670;
   private static final int C_GOLD = 0xFFFFD37A;
   private static final int C_TEXT = 0xFFE6EAF2;
   private static final int C_DIM = 0xFF8B95A8;
   private static final int C_GOOD = 0xFF7BE38F;
   private static final int C_BAD = 0xFFFF7B7B;
   private static final int C_ACCENT = 0xFF7FB3FF;

   /* ------------------------------------------------------------------ *
    *  2.1 - layout
    *
    *  Every number below is DERIVED from the band table in ShopMenu, never
    *  typed in twice. See ShopMenu for the band map and for why the action
    *  band has two rows.
    *
    *  The short version: the primary button of each tab (Trade, Sell,
    *  Submit iron) owns action ROW B outright, at full content width. It
    *  no longer shares a row with Analyze/Target, so nothing can shrink it,
    *  displace it, or get drawn over it - which is what kept happening when
    *  its x was computed from its neighbours' label widths.
    * ------------------------------------------------------------------ */
   private static final int GAP = 4;
   private static final int BTN_H = 16;
   private static final int BTN_PAD = 12;
   private static final int BTN_GAP = 6;
   private static final int SLOT_BOX = 18;

   private static final int HEADER_H = 20;

   /* sidebar column - its own column, never overlaps the content column */
   private static final int SIDE_X = 6;
   private static final int SIDE_W = 82;
   private static final int SIDE_Y = 24;
   private static final int SIDE_ROW_H = 12;

   /* content column */
   private static final int CONTENT_X = ShopMenu.CONTENT_X;
   private static final int CONTENT_R = ShopMenu.CONTENT_R;
   private static final int CONTENT_W = CONTENT_R - CONTENT_X;

   /* band 1: category icon + title + subtitle */
   private static final int HEAD_Y = HEADER_H + 3;                 // 23
   private static final int HEAD_ICON = 22;
   private static final int HEAD_H = 26;                           // 23..49
   private static final int TITLE_X = CONTENT_X + HEAD_ICON + 8;   // 124

   /* band 2: the main area - catalog rows, or input slots + AI box */
   private static final int MAIN_Y = ShopMenu.BAND_MAIN_Y;         // 52
   private static final int MAIN_H = ShopMenu.BAND_MAIN_H;         // 60
   private static final int ROWS_PER_PAGE = 3;
   private static final int ROW_H = MAIN_H / ROWS_PER_PAGE;        // 20
   /** input slots sit at the top of the main band; the AI box gets whatever is left */
   private static final int SLOT_ROW_H = SLOT_BOX + 2;             // 20
   private static final int AI_BOX_Y = MAIN_Y + SLOT_ROW_H + GAP;  // 76
   private static final int AI_BOX_H = MAIN_Y + MAIN_H - AI_BOX_Y; // 36
   private static final int AI_TEXT_W = CONTENT_W - 8;
   private static final int AI_TEXT_LINES = 2;

   /* band 3: two action rows. Row B belongs to one primary button, always. */
   private static final int ACTION_Y = ShopMenu.BAND_ACTION_Y;     // 116
   private static final int ACTION_H = ShopMenu.BAND_ACTION_H;     // 38
   private static final int ROW_A_Y = ShopMenu.ACTION_ROW_A_Y;     // 116
   private static final int ROW_B_Y = ShopMenu.ACTION_ROW_B_Y;     // 136
   /** row B is 18 tall (a slot box); a 16-tall button centres one pixel down */
   private static final int ROW_B_BTN_Y = ROW_B_Y + 1;

   /* ------------------------------------------------------------------ *
    *  1.1 (2.6.0) - there is no band 4 any more. The status strip is gone.
    *
    *  It used to live at y = ACTION_Y + ACTION_H + GAP = 158, 14 tall, and
    *  it was drawn from SIDE_X (6) all the way to CONTENT_R (330) - i.e.
    *  straight across the SIDEBAR column, not just the content column.
    *  The sidebar's 14 tab rows occupy y 24..192 (SIDE_Y + 14 * SIDE_ROW_H),
    *  so rows 11 and 12 - Trade (156..167) and Sell (168..179) - sat exactly
    *  underneath it. The strip painted an opaque box over the Trade tab
    *  completely and over the top third of the Sell tab, and because it drew
    *  itself unconditionally it did that even when there was nothing to say,
    *  showing the placeholder text "Ready." for the rest of the session.
    *
    *  assertNoOverlap() never caught it because the strip is a raw g.fill(),
    *  not a widget and not a slot, so nothing in the layout check could see
    *  it. checkContentColumn() below now covers hand-drawn fills too.
    *
    *  Both the strip and the 158..172 space it occupied are deleted, which
    *  gives the Trade and Sell tab rows their pixels back. Shop feedback
    *  goes to the chat log instead - see postMessage().
    * ------------------------------------------------------------------ */

   /** Last usable y in the content column: below it are the inventory label and the inventory. */
   private static final int CONTENT_BOTTOM = ShopMenu.INV_Y - 13;  // 172

   /* sidebar footer starts below the tab list, never on top of it */
   /**
    * 1.1 (2.8.0) - the tab list, once.
    *
    * <p>Enum.values() hands out a fresh clone of the backing array on every call, and drawSidebar()
    * called it three times per frame. Nothing mutates this array, so one private copy is shared.
    */
   private static final ShopScreen.Tab[] TABS = ShopScreen.Tab.values();
   private static final int SIDE_FOOT_Y = SIDE_Y + TABS.length * SIDE_ROW_H + 6;
   private static final int SIDE_FOOT_H = 46;

   private static final Identifier RESTOCK_IMG = Identifier.fromNamespaceAndPath("merchantmind", "textures/gui/restock_center.png");

   private ShopScreen.Tab tab = ShopScreen.Tab.WEAPON;
   private List<ShopPackets.ShopEntryData> entries = new ArrayList<>();
   private long balance = 0L;
   private String tradeAi = "Press Analyze and the trade AI will decide.";
   private String sellAi = "Press Appraise and the price AI will quote you.";
   private boolean tradeApproved = false;
   private String tradeResultId = "";
   private int tradeResultQty = 0;
   private long sellQuote = 0L;
   private boolean thinking = false;
   private int page = 0;
   private String forcedTarget = "";
   /**
    * 1.1 (2.8.0) - the sidebar's click targets, built once per init() instead of per frame.
    *
    * <p>drawSidebar() used to clear this list and allocate 14 fresh Rect records on EVERY frame, for
    * geometry that only depends on leftPos/topPos - which change only when the screen is opened or
    * the window is resized, and both of those re-run init().
    */
   private final List<ShopScreen.Rect> hitRects = new ArrayList<>();
   /** 4.1 - buttons whose enabled state follows live slot contents, refreshed every client tick. */
   private Button submitButton;
   private Button tradeCommitButton;
   private Button sellCommitButton;
   private boolean restocking;
   /**
    * 3.2 - true while the open category cannot be bought from: it is refilling, or this client has
    * just asked for a refill and is waiting for the answer. While it is set the Buy buttons are
    * created disabled AND {@link #buy} refuses to send, so there is no second path to a purchase.
    * The server enforces the same rule independently, in handleBuy.
    */
   private boolean restockBusy;
   /**
    * 3.2 (2.6.0) - set the instant a restock is requested and cleared by the next sync that
    * confirms it. Without it the Buy buttons would stay live for up to a full second after the
    * player pressed Restock, because the state they read only arrives on the once-a-second sync.
    */
   private boolean restockRequested;
   /**
    * 3.1 / 1.1 (2.7.0) - the big countdown's text, cached with the second it was built for. The
    * label only changes once a second but is drawn every frame, so this keeps the render pass from
    * building the same string a couple of hundred times a second.
    */
   private int bigLabelSecond = -1;
   private String bigLabel = "";
   /** 1.1 (2.7.0) - last wrap() result, so the AI box is not re-wrapped on every frame. */
   private String wrapKey = "";
   private int wrapWidth = -1;
   private List<String> wrapCache = List.of();
   /** 1.1 (2.7.0) - the trade result item, resolved on sync instead of per frame. */
   private ItemStack tradeResultStack = ItemStack.EMPTY;
   /** 1.1 (2.8.0) - the Help tab's text, rebuilt only when a key binding is renamed. */
   private String[] helpLines;
   private String helpShopKey = "";
   private String helpKillKey = "";
   private String helpSlamKey = "";
   /**
    * 2.2 (2.6.0) - client ticks since the last sync. Every countdown on this screen is drawn as
    * "what the server last said" minus this, so the timers actually run instead of stepping once
    * a second - and keep running visibly even if a sync is late.
    */
   private int ticksSinceSync;

   /* ---------------- 6.1 animation state ----------------
    *
    * All of it is client-side eye candy driven by two clocks:
    *
    *   animTicks  - free running, for anything that loops (pulses, spinners)
    *   openTicks  - reset every time the screen opens, for the entrance
    *
    * Both are advanced in containerTick() and read with the frame delta added back on, so the
    * motion is smooth at any framerate instead of stepping 20 times a second. Nothing here can
    * change what the server does; the worst a bug in this section can do is look wrong.
    */
   private static final float OPEN_TICKS = 6.0F;
   private static final float HOVER_SPEED = 0.28F;

   private int animTicks;
   private int openTicks;
   private float frameDelta;
   /** per-tab hover ramp, 0 = cold, 1 = fully lit */
   private final float[] tabHover = new float[TABS.length];
   /** y of the gold selection bar, chased toward the selected row instead of teleporting */
   private float selectorY = -1.0F;
   /** 1.1 (2.7.0) - which tabs the last frame found under the cursor; consumed by containerTick(). */
   private int tabHoverMask;
   /** 1.1 (2.7.0) - y the selector bar is chasing, published by the render pass, stepped per tick. */
   private float selectorGoal = -1.0F;
   /** the coin total the header is currently showing; rolls toward the real balance */
   private double shownBalance;
   /** reset on every rebuild(), so a page turn or a tab switch re-deals the catalog rows */
   private int rowAnimTicks;
   /** the single row-B button, whatever it is on this tab; used only to place its halo */
   private Button primary;

   /* ---------------- 3.2.0 animation state ----------------
    *
    * Everything added in 3.2.0 works the same way: an event records the tick it happened on, and
    * the render pass draws whatever that event looks like at (now - then) ticks old. There are no
    * per-effect counters to advance and nothing to reset, so an effect that is never triggered
    * costs one subtraction and a comparison, and no effect can leak into the next screen.
    *
    * The one exception is the particle pool, which does need stepping - see stepParticles().
    */
   /** how long, in ticks, each one-shot effect lasts */
   private static final float READY_TICKS = 30.0F;
   private static final float COIN_TICKS = 26.0F;
   private static final float CLICK_TICKS = 10.0F;
   private static final float POP_TICKS = 7.0F;
   private static final int FX_MAX = 72;

   /** animTicks when each one-shot fired, or -1 for "has not happened on this screen" */
   private int readyAt = -1;
   private int coinAt = -1;
   private int popAt = -1;
   private int clickAt = -1;
   /** where the READY burst is centred, captured when the refill finished */
   private int readyX;
   private int readyY;
   /** the coin delta the header is currently floating upward */
   private long coinDelta;
   /** where the last click landed, for the ripple, and whether that click was refused */
   private int clickX;
   private int clickY;
   private boolean clickDenied;
   /**
    * The restocking flag the last frame saw, and the tab it belonged to. The pair is what makes the
    * "refill just finished" edge detectable client-side without a new packet: the server already
    * says whether a category is restocking, so the client only has to notice it stop. The tab is
    * part of the state because switching tabs is not a refill finishing.
    */
   private boolean sawRestocking;
   private ShopScreen.Tab sawTab;

   /**
    * The particle pool: parallel arrays, fixed capacity, no allocation and no garbage.
    *
    * <p>A dead particle is simply one with {@code life <= 0}, and a new one overwrites the oldest
    * slot rather than growing anything, so a player who buys forty things in two seconds costs
    * exactly what one who buys nothing does. 72 is about three simultaneous bursts.
    */
   private final float[] fxX = new float[FX_MAX];
   private final float[] fxY = new float[FX_MAX];
   private final float[] fxVX = new float[FX_MAX];
   private final float[] fxVY = new float[FX_MAX];
   private final float[] fxLife = new float[FX_MAX];
   private final float[] fxSpan = new float[FX_MAX];
   private final int[] fxColor = new int[FX_MAX];
   private int fxCursor;
   /** cheap deterministic noise source for particle spread; no need for a real Random here */
   private int fxSeed = 12345;

   public ShopScreen(ShopMenu menu, Inventory inventory, Component title) {
      super(menu, inventory, title, ShopMenu.PANEL_W, ShopMenu.PANEL_H);
   }

   @Override
   protected void init() {
      super.init();
      // 1.1 (2.9.0) - everything this screen draws is pulled into memory here, before the first
      // frame, instead of being loaded a tick at a time in the background
      this.loadGuiAssets();
      this.openTicks = 0; // 6.1 - replay the entrance every time the screen is opened
      this.replayRows();
      this.titleLabelX = -9999;
      this.inventoryLabelX = ShopMenu.INV_X;
      this.inventoryLabelY = ShopMenu.INV_Y - 11;
      this.syncTabState();
      checkContentColumn();
      this.buildHitRects();
      /*
       * 1.1 (2.8.0) - derive the view model before the first frame is drawn.
       *
       * init() used to go straight to rebuild(), which left tabCategory null, so the first frames
       * had no rows and the middle panel drew the "Sold out" screen until the first sync landed a
       * moment later. Nothing was wrong with the shop - the client simply had no data yet - but it
       * flashed a message that was not true.
       */
      this.refreshView();
      this.rebuild();
      ClientPlayNetworking.send(new ShopPackets.RequestSyncC2SPayload());
   }

   /** Fills {@link #hitRects}; the formulas must match the rows drawn by drawSidebar(). */
   private void buildHitRects() {
      this.hitRects.clear();
      for (int i = 0; i < TABS.length; i++) {
         int ty = this.topPos + SIDE_Y + i * SIDE_ROW_H;
         int tx = this.leftPos + SIDE_X + 1;
         this.hitRects.add(new ShopScreen.Rect(tx, ty, SIDE_W - 2, SIDE_ROW_H - 1, TABS[i].name()));
      }
   }

   /** Logged once, not every time the screen opens - this is a constant-vs-constant check. */
   private static boolean columnChecked;
   /** The layout signature {@link #assertNoOverlap} last ran for; -1 means "never". */
   private long checkedLayout = -1L;

   /**
    * 1.1 (2.6.0) - the guard rail for the bug this release fixes.
    *
    * <p>{@link #assertNoOverlap} only ever compared WIDGETS and SLOTS. The status strip was
    * neither - it was a raw {@code g.fill} - so nothing could see that it started at SIDE_X and
    * ran across the sidebar's tab rows, hiding Trade and half of Sell behind the word "Ready.".
    *
    * <p>This checks the thing that actually went wrong: that the two columns are disjoint, and
    * that nothing the content column draws is allowed below CONTENT_BOTTOM. It is arithmetic on
    * compile-time constants, so it runs once and only logs.
    */
   private static void checkContentColumn() {
      if (columnChecked) {
         return;
      }
      columnChecked = true;

      int sideRight = SIDE_X + SIDE_W;
      if (CONTENT_X < sideRight) {
         com.merchantmind.MerchantMind.LOGGER
            .warn("[merchantmind] layout: content column starts at {} but the sidebar ends at {}", CONTENT_X, sideRight);
      }
      int tabsBottom = SIDE_Y + TABS.length * SIDE_ROW_H;
      if (SIDE_FOOT_Y < tabsBottom) {
         com.merchantmind.MerchantMind.LOGGER
            .warn("[merchantmind] layout: sidebar footer at {} overlaps the tab list ending at {}", SIDE_FOOT_Y, tabsBottom);
      }
      int actionBottom = ACTION_Y + ACTION_H;
      if (actionBottom > CONTENT_BOTTOM) {
         com.merchantmind.MerchantMind.LOGGER
            .warn("[merchantmind] layout: action band ends at {} but the content column stops at {}", actionBottom, CONTENT_BOTTOM);
      }
   }

   /**
    * Pushes the current tab to the menu on BOTH sides.
    *
    * 3.1 - this used to go out as a custom payload, which the server re-queued and therefore
    * applied after the very click it was supposed to authorise; every trade/sell click was then
    * rejected and no item ever showed up. clickMenuButton rides the vanilla container channel,
    * so the server has the right tab before the next click packet is read.
    */
   private void syncTabState() {
      int set = this.tab == ShopScreen.Tab.TRADE
         ? ShopMenu.SET_TRADE
         : (this.tab == ShopScreen.Tab.SELL ? ShopMenu.SET_SELL : ShopMenu.SET_NONE);
      int cat = this.tab.category == null ? -1 : ShopManager.Category.valueOf(this.tab.category).ordinal();
      this.menu.activeInputSet = set;
      this.menu.activeCategory = cat;
      if (this.minecraft != null && this.minecraft.gameMode != null) {
         this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, ShopMenu.encodeTab(set, cat));
      }
   }

   public void applySync(ShopPackets.SyncS2CPayload payload) {
      this.entries = payload.entries();
      /*
       * 3.2.0 - a balance that moved is worth showing as an event, not just as a new number. The
       * delta is captured here, where both values still exist, and the header floats it upward and
       * throws a few sparks off the coin readout for the next second and a bit.
       */
      if (payload.balance() != this.balance) {
         this.coinDelta = payload.balance() - this.balance;
         this.coinAt = this.animTicks;
         int cx = this.leftPos + this.imageWidth - 66;
         int cy = this.topPos + 9;
         this.spawn(cx, cy, 10, this.coinDelta > 0L ? C_GOLD : C_BAD, 1.5F, -1.1F);
      }
      this.balance = payload.balance();
      this.menu.ironSlotMask = ironMaskOf(this.entries);
      // 2.2 - restart the local clock every countdown on this screen interpolates from
      this.ticksSinceSync = 0;
      // 3.2 - the authoritative restock state has arrived; the optimistic client lock can go
      this.restockRequested = false;
      if (!payload.message().isEmpty()) {
         this.postMessage(payload.message());
      }
      if (payload.updateTradePanel()) {
         this.tradeAi = payload.tradeAi().isEmpty() ? this.tradeAi : payload.tradeAi();
         this.tradeApproved = payload.tradeApproved();
         this.tradeResultId = payload.tradeResultId();
         this.tradeResultQty = payload.tradeResultQty();
         this.thinking = false;
      }
      if (payload.updateSellPanel()) {
         this.sellAi = payload.sellAi().isEmpty() ? this.sellAi : payload.sellAi();
         this.sellQuote = payload.sellQuote();
         this.thinking = false;
      }
      // 1.1 (2.7.0) - a sync is the only thing that can change the catalog, so the work the render
      // pass used to redo every frame is done once here instead.
      this.refreshView();
      this.rebuild();
   }

   /**
    * 1.2 (2.6.0) - where a shop message goes now that the status strip is gone.
    *
    * <p>Chat is outside the panel, so it occupies no layout space and cannot cover a tab, a button
    * or a slot - which is the entire class of bug the strip caused. Only real messages are shown:
    * the server sends an empty string on its once-a-second heartbeat sync and nothing is printed
    * for it, so there is no equivalent of the old permanent "Ready." placeholder.
    */
   private void postMessage(String text) {
      if (this.minecraft != null && this.minecraft.player != null) {
         this.minecraft.player.sendSystemMessage(Component.literal("§6[Shop] §f" + text));
      }
   }

   /* ==================================================================== *
    *  1.1 (2.7.0) - the view model, and why it exists
    *
    *  Everything below used to be recomputed inside the render pass, i.e.
    *  once per FRAME. At 200 fps that is 200 times a second to produce data
    *  that only changes when the server syncs (once a second) or when the
    *  player switches tab or page. Per frame, the old drawCatalog() did:
    *
    *    - catEntries(): allocate an ArrayList and walk all ~77 sync entries,
    *      comparing category strings;
    *    - catStatus(): walk all ~77 entries again, comparing item ids;
    *    - per visible row: Identifier.parse() + a registry lookup + a new
    *      ItemStack, prettyName() (a split plus a StringBuilder) and two
    *      concatenated price strings;
    *    - Category.valueOf() several times, once per drawSlotBoxes call and
    *      again inside ironCount().
    *
    *  That is a few hundred short-lived objects every frame, which is exactly
    *  the shape of a problem that shows up as stutter rather than as a lower
    *  average: the young generation fills, and the collections land as frame
    *  spikes while the animations are running.
    *
    *  It is all derived state, so it is now computed ONCE in refreshView()
    *  and read straight out of these fields while drawing. The render pass
    *  allocates nothing.
    *
    *  4.1 - none of this is authoritative. Every field here is derived from
    *  the last server sync; the client stores no shop state of its own.
    * ==================================================================== */

   /** One catalog row, prepared once instead of rebuilt from strings every frame. */
   private record Row(String itemId, ItemStack icon, String name, String priceLine, int buyPrice) {
   }

   /** Rows on the CURRENT page only - exactly what drawCatalog draws. */
   private final List<ShopScreen.Row> rows = new ArrayList<>();
   /** The open tab's category, resolved once per tab switch instead of by valueOf() per frame. */
   private ShopManager.Category tabCategory;
   /** The open category's status row from the last sync, found once instead of per frame. */
   private ShopPackets.ShopEntryData tabStatus;
   /**
    * 3.3.0 - the open category's restock trigger, parsed out of {@link #tabStatus} once per sync
    * rather than per frame, exactly like {@link #tabCategory}. It is read three times a frame - the
    * header subtitle, the progress bar's total and the reason line - and it is what says which of the
    * two durations this refill is on, so it has to be right rather than convenient.
    */
   private ShopManager.RestockReason tabReason = ShopManager.RestockReason.NONE;
   /** How many offers the open category has in total, and the resulting last page index. */
   private int catCount;
   private int maxPage;

   /**
    * Recomputes everything the render pass reads. Called only when the inputs actually change:
    * a server sync, a tab switch or a page turn.
    */
   private void refreshView() {
      // resolved here so drawTrade does not have to parse an id and build an ItemStack every frame
      this.tradeResultStack = this.stackFor(this.tradeResultId);

      this.tabCategory = null;
      if (this.tab.category != null) {
         try {
            this.tabCategory = ShopManager.Category.valueOf(this.tab.category);
         } catch (IllegalArgumentException ignored) {
         }
      }

      this.tabStatus = null;
      this.tabReason = ShopManager.RestockReason.NONE;
      this.catCount = 0;
      if (this.tabCategory == null) {
         this.rows.clear();
         this.maxPage = 0;
         this.page = 0;
         return;
      }

      String statusKey = "merchantmind:__cat_" + this.tab.category;

      // one pass over the sync for both the status row and the offer count
      for (ShopPackets.ShopEntryData e : this.entries) {
         if (e.itemId().equals(statusKey)) {
            this.tabStatus = e;
         } else if (!e.itemId().startsWith(CAT_PREFIX) && this.tab.category.equals(e.category())) {
            this.catCount++;
         }
      }

      if (this.tabStatus != null) {
         this.tabReason = ShopManager.RestockReason.fromKey(this.tabStatus.reason());
      }

      this.maxPage = Math.max(0, (this.catCount - 1) / ROWS_PER_PAGE);
      this.page = Math.max(0, Math.min(this.page, this.maxPage));

      // second pass: build only the rows this page shows, with their item and text ready to draw
      this.rows.clear();
      int first = this.page * ROWS_PER_PAGE;
      int last = first + ROWS_PER_PAGE;
      int index = 0;
      for (ShopPackets.ShopEntryData e : this.entries) {
         if (e.itemId().startsWith(CAT_PREFIX) || !this.tab.category.equals(e.category())) {
            continue;
         }
         if (index >= first && index < last) {
            this.rows.add(
               new ShopScreen.Row(
                  e.itemId(),
                  this.stackFor(e.itemId()),
                  this.prettyName(e.itemId()),
                  "buy " + e.buyPrice() + "c  ·  sells for " + e.sellPrice() + "c",
                  e.buyPrice()
               )
            );
         }
         if (++index >= last) {
            break;
         }
      }
   }

   private static final String CAT_PREFIX = "merchantmind:__cat_";

   /**
    * 2.2 (2.7.0) / 4.1 - rebuilds "which categories have their iron slot open" from the status rows
    * the server just sent. Derived from the sync rather than stored, so the client can never offer
    * an iron slot for a category the server does not consider restocking.
    */
   private static int ironMaskOf(List<ShopPackets.ShopEntryData> entries) {
      int mask = 0;
      for (ShopPackets.ShopEntryData e : entries) {
         if (e.itemId().startsWith(CAT_PREFIX) && e.ironSkipOpen()) {
            try {
               mask |= 1 << ShopManager.Category.valueOf(e.category()).ordinal();
            } catch (IllegalArgumentException ignored) {
            }
         }
      }
      return mask;
   }

   /** 1.1 (2.7.0) - reads the resolved category off the view model instead of doing valueOf() again. */
   private int ironCount() {
      return this.tabCategory == null ? 0 : this.menu.ironCount(this.tabCategory);
   }

   /* ---------------- widgets ---------------- */

   /**
    * 2.1 - button widths used to be hard-coded, so a label wider than its box was clipped by
    * vanilla's scrolling-label renderer and the row could run past the panel edge. Widths are
    * now measured from the actual font, and rows are packed so they always fit CONTENT_X..CONTENT_R.
    */
   private int btnW(String label) {
      return this.font.width(label) + BTN_PAD;
   }

   private Button makeButton(String label, int x, int y, Button.OnPress action) {
      return Button.builder(Component.literal(label), action).bounds(x, y, this.btnW(label), BTN_H).build();
   }

   private void rebuild() {
      this.clearWidgets();
      this.primary = null;
      this.submitButton = null;
      this.tradeCommitButton = null;
      this.sellCommitButton = null;
      int x = this.leftPos;
      int y = this.topPos;

      this.addRenderableWidget(
         Button.builder(Component.literal("✕ Close"), b -> this.onClose()).bounds(x + this.imageWidth - 52, y + 3, 46, 14).build()
      );

      if (this.tab.category != null) {
         this.buildCatalog(x, y);
      } else if (this.tab == ShopScreen.Tab.TRADE) {
         this.buildTrade(x, y);
      } else if (this.tab == ShopScreen.Tab.SELL) {
         this.buildSell(x, y);
      }

      /*
       * 1.1 (2.8.0) - only check a layout that has not been checked yet.
       *
       * rebuild() runs once a second for as long as the screen is open (every server sync), and the
       * check walked every widget against every slot each time - for a layout that is identical to
       * the one it just cleared. It is a development guard rail, so it now runs when the layout can
       * actually differ: a different tab, page, or restock state.
       */
      long signature = (long)this.tab.ordinal() * 1000L + this.page * 10L + (this.restocking ? 1 : 0) + (this.restockBusy ? 2 : 0) + this.rows.size() * 100000L;
      if (signature != this.checkedLayout) {
         this.checkedLayout = signature;
         this.assertNoOverlap();
      }
   }

   /**
    * 1.1 - the guard rail for the original bug.
    *
    * AbstractContainerScreen renders widgets first and container slots second, so a slot that
    * shares even one pixel with a button silently erases it while the button keeps eating the
    * click - which is exactly how the Trade button disappeared. Layout is band-based now, so this
    * can no longer happen; this check makes sure it stays that way instead of trusting the
    * arithmetic. It only walks the handful of widgets and active slots this screen has, once per
    * rebuild, and just logs - it never changes what the player sees.
    */
   private void assertNoOverlap() {
      List<net.minecraft.client.gui.components.AbstractWidget> widgets = new ArrayList<>();
      for (net.minecraft.client.gui.components.events.GuiEventListener listener : this.children()) {
         if (listener instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.visible) {
            widgets.add(widget);
         }
      }

      /*
       * 2.1 - widget against widget. This is the pair the old check missed and the pair that
       * actually broke: Trade and Target were both buttons, so no slot was involved, and whichever
       * was added last simply painted over the other. Row B is exclusive now, but the check is
       * what keeps a future edit from quietly re-sharing it.
       */
      for (int i = 0; i < widgets.size(); i++) {
         for (int j = i + 1; j < widgets.size(); j++) {
            net.minecraft.client.gui.components.AbstractWidget a = widgets.get(i);
            net.minecraft.client.gui.components.AbstractWidget b = widgets.get(j);
            if (a.getX() < b.getX() + b.getWidth()
               && b.getX() < a.getX() + a.getWidth()
               && a.getY() < b.getY() + b.getHeight()
               && b.getY() < a.getY() + a.getHeight()) {
               com.merchantmind.MerchantMind.LOGGER
                  .warn(
                     "[merchantmind] layout overlap: widget '{}' at {},{} {}x{} overlaps widget '{}' at {},{} {}x{}",
                     a.getMessage().getString(), a.getX() - this.leftPos, a.getY() - this.topPos, a.getWidth(), a.getHeight(),
                     b.getMessage().getString(), b.getX() - this.leftPos, b.getY() - this.topPos, b.getWidth(), b.getHeight()
                  );
            }
         }
      }

      for (net.minecraft.client.gui.components.AbstractWidget widget : widgets) {
         for (Slot slot : this.menu.slots) {
            // 1.1 (2.8.0) - only this mod's own slots share the content column with a widget; the
            // player's 36 inventory slots sit below CONTENT_BOTTOM and were 36/57 of the work
            if (!this.menu.isCustomSlot(slot) || !slot.isActive()) {
               continue;
            }
            int sx = this.leftPos + slot.x - 1;
            int sy = this.topPos + slot.y - 1;
            if (sx < widget.getX() + widget.getWidth()
               && sx + SLOT_BOX > widget.getX()
               && sy < widget.getY() + widget.getHeight()
               && sy + SLOT_BOX > widget.getY()) {
               com.merchantmind.MerchantMind.LOGGER
                  .warn(
                     "[merchantmind] layout overlap: widget '{}' at {},{} {}x{} is covered by slot {} at {},{}",
                     widget.getMessage().getString(),
                     widget.getX() - this.leftPos,
                     widget.getY() - this.topPos,
                     widget.getWidth(),
                     widget.getHeight(),
                     this.menu.slots.indexOf(slot),
                     slot.x,
                     slot.y
                  );
            }
         }
      }
   }

   /**
    * A primary button: owns action ROW B outright, spanning the whole content column.
    *
    * <p>2.1 - this is the fix. Trade used to be packed between Analyze and Target with an x
    * derived from their rendered label widths, and the clamp that kept it inside the panel could
    * shove it underneath its own neighbour. A primary button now has a fixed rectangle that no
    * other widget shares and no label width can influence.
    *
    * @param fromX where the button starts - only the iron slot ever pushes this right
    */
   private Button primaryButton(String label, int fromX, int rightEdge, Button.OnPress action) {
      Button button = Button.builder(Component.literal(label), action)
         .bounds(fromX, this.topPos + ROW_B_BTN_Y, Math.max(24, rightEdge - fromX), BTN_H)
         .build();
      this.primary = button; // 6.1 - remembered so the halo below it knows where to draw
      return button;
   }

   /**
    * 6.1 - a soft halo behind whatever primary button row B is currently showing.
    *
    * <p>Vanilla buttons cannot be restyled without a widget subclass, so instead of fighting them
    * this draws two fading rings just outside the button's rectangle. When the button is ready to
    * be pressed the rings breathe in gold; when it is disabled they sit still and dim, which is
    * what tells you at a glance that the shop is waiting on you rather than on itself.
    *
    * <p>Drawn before super.extractRenderState(), so it is always behind the button itself.
    */
   private void drawPrimaryHalo(GuiGraphicsExtractor g) {
      Button button = this.primary;
      if (button == null || !button.visible) {
         return;
      }
      int bx = button.getX();
      int by = button.getY();
      int bw = button.getWidth();
      int bh = button.getHeight();
      float energy = button.active ? 0.35F + 0.65F * this.pulse(30.0F) : 0.12F;
      int glow = button.active ? C_GOLD : C_BORDER;
      for (int ring = 1; ring <= 2; ring++) {
         int color = withAlpha(glow, energy / (ring * 1.6F));
         this.border(g, bx - ring, by - ring, bw + ring * 2, bh + ring * 2, color);
      }
      /*
       * 3.2.0 - a light travels around the outside of an ENABLED primary button, and only an enabled
       * one. The breathing rings above say "this is the important button"; the runner says "and it
       * will do something if you press it", which is the distinction players kept missing when the
       * disabled state was just a dimmer version of the same pulse.
       */
      if (button.active) {
         int perimeter = (bw + bh) * 2;
         int head = (int)(this.clock() * 1.6F) % perimeter;
         for (int t = 0; t < 10; t++) {
            int at = (head - t + perimeter) % perimeter;
            int px;
            int py;
            if (at < bw) {
               px = bx + at;
               py = by - 2;
            } else if (at < bw + bh) {
               px = bx + bw + 1;
               py = by + (at - bw);
            } else if (at < bw * 2 + bh) {
               px = bx + bw - (at - bw - bh);
               py = by + bh + 1;
            } else {
               px = bx - 2;
               py = by + bh - (at - bw * 2 - bh);
            }
            g.fill(px, py, px + 1, py + 1, withAlpha(0xFFFFF4C0, (1.0F - t / 10.0F) * 0.75F));
         }
      }
   }

   /* ==================================================================== *
    *  2.2 / 3.1 (2.7.0) - the catalog's widgets
    *
    *  There are now exactly two states, not four:
    *
    *    idle        rows with Buy buttons, paging, and "⟳ Restock".
    *    restocking  no rows, no Buy, no paging (3.1 replaces the whole item
    *                area with the countdown). The iron slot is open and the
    *                Submit button is offered as an OPTIONAL shortcut.
    *
    *  The "armed" state is gone. Through 2.6.0, pressing Restock only armed
    *  the category and the refill could not start until 4 iron had also been
    *  submitted, so iron was mandatory. Pressing Restock now starts the
    *  refill immediately, on the same duration an automatic one uses, and
    *  iron only ever buys the remaining wait away.
    * ==================================================================== */
   private void buildCatalog(int x, int y) {
      ShopManager.Category category = this.tabCategory;
      if (category == null) {
         return;
      }

      ShopPackets.ShopEntryData status = this.tabStatus;
      boolean restocking = status != null && status.restocking();
      this.restocking = restocking;
      /*
       * 3.2 - one flag gates every "you cannot buy right now" widget. restockRequested is the
       * optimistic half-second between this client's click and the server's answer, so the rows do
       * not flash back to buyable in between.
       */
      this.restockBusy = restocking || this.restockRequested;

      if (!restocking) {
         /* --- the offer rows, one Buy button each --- */
         for (int i = 0; i < this.rows.size(); i++) {
            ShopScreen.Row row = this.rows.get(i);
            int rowY = y + MAIN_Y + i * ROW_H;
            Button buy = Button.builder(Component.literal("Buy " + row.buyPrice() + "c"), b -> this.buy(row.itemId(), row.buyPrice()))
               .bounds(x + CONTENT_R - 58, rowY + 2, 58, BTN_H)
               .build();
            buy.active = !this.restockBusy && this.balance >= row.buyPrice();
            this.addRenderableWidget(buy);
         }

         /* --- action row A: paging. Pointless while the rows are hidden, so it is only built here. --- */
         Button prev = Button.builder(Component.literal("<"), b -> this.turnPage(-1))
            .bounds(x + CONTENT_X, y + ROW_A_Y, 16, BTN_H)
            .build();
         prev.active = this.page > 0;
         this.addRenderableWidget(prev);

         Button next = Button.builder(Component.literal(">"), b -> this.turnPage(1))
            .bounds(x + CONTENT_X + 18, y + ROW_A_Y, 16, BTN_H)
            .build();
         next.active = this.page < this.maxPage;
         this.addRenderableWidget(next);
      }

      /* --- action row B: the single restock control --- */
      if (restocking) {
         /*
          * 2.2 - the optional skip. The button sits to the right of the category's iron slot, which
          * the server opened when the refill started. If the player has no iron the button is
          * simply inactive and the countdown runs out on its own - nothing is ever blocked on it.
          */
         int submitX = x + ShopMenu.IRON_X + SLOT_BOX + BTN_GAP;
         String label = "⚡ Submit " + ShopManager.RESTOCK_IRON_COST + " iron — finish now";
         Button submit = this.primaryButton(label, submitX, x + CONTENT_R, b -> this.restock(category, true));
         submit.active = this.ironCount() >= ShopManager.RESTOCK_IRON_COST;
         this.submitButton = submit;
         this.addRenderableWidget(submit);
      } else {
         Button ask = this.primaryButton("⟳ Restock", x + CONTENT_X, x + CONTENT_R, b -> this.restock(category, false));
         this.addRenderableWidget(ask);
      }
   }

   /** Page turn: the view model has to be rebuilt because it only holds the current page's rows. */
   private void turnPage(int delta) {
      int target = Math.max(0, Math.min(this.page + delta, this.maxPage));
      if (target == this.page) {
         return;
      }
      this.page = target;
      this.refreshView();
      this.replayRows();
      this.rebuild();
   }

   private void buildTrade(int x, int y) {
      String analyze = this.thinking ? "Analyzing…" : "⚙ Analyze";
      String target = "⟳ Target";

      /* row A: the two secondary controls, packed from opposite edges */
      this.addRenderableWidget(this.makeButton(analyze, x + CONTENT_X, y + ROW_A_Y, b -> this.analyzeTrade(false)));
      this.addRenderableWidget(this.makeButton(target, x + CONTENT_R - this.btnW(target), y + ROW_A_Y, b -> this.cycleTarget()));

      /* row B: Trade, alone, full width */
      String commitLabel = this.tradeApproved ? "✔ Trade" : "Trade";
      Button commit = this.primaryButton(commitLabel, x + CONTENT_X, x + CONTENT_R, b -> this.analyzeTrade(true));
      commit.active = this.tradeResultQty > 0;
      this.tradeCommitButton = commit;
      this.addRenderableWidget(commit);
   }

   private void buildSell(int x, int y) {
      String appraise = this.thinking ? "Appraising…" : "⚙ Appraise";

      /* row A */
      this.addRenderableWidget(this.makeButton(appraise, x + CONTENT_X, y + ROW_A_Y, b -> this.analyzeSell(false)));

      /* row B: Sell, alone, full width */
      Button sell = this.primaryButton("✔ Sell " + this.sellQuote + "c", x + CONTENT_X, x + CONTENT_R, b -> this.analyzeSell(true));
      sell.active = this.sellQuote > 0L;
      this.sellCommitButton = sell;
      this.addRenderableWidget(sell);
   }

   /* ---------------- actions ---------------- */

   /**
    * 3.2 (2.6.0) - the second half of the client-side buy lock.
    *
    * <p>Disabling the button is not enough on its own: it only covers the one path the player can
    * see. This is the single funnel every purchase goes through, so a keyboard activation, a
    * queued click or any future call site is refused here too. The server repeats the check before
    * it moves a single coin, which is what stops a hand-crafted packet.
    */
   private void buy(String itemId, int price) {
      if (this.restockBusy || this.balance < price) {
         return;
      }
      ClientPlayNetworking.send(new ShopPackets.BuyC2SPayload(itemId));
   }

   /**
    * 2.2 (2.7.0) - the only path to the restock packet. useIron == false asks the server to start a
    * refill now; useIron == true asks it to end the running refill for 4 iron.
    */
   private void restock(ShopManager.Category category, boolean useIron) {
      // 3.2 - lock buying immediately rather than a sync later, so the rows cannot be clicked in
      // the gap before the server's answer arrives
      this.restockRequested = true;
      this.restockBusy = true;
      ClientPlayNetworking.send(new ShopPackets.RestockC2SPayload(category.name(), useIron));
      this.rebuild();
   }

   private void cycleTarget() {
      List<String> ids = new ArrayList<>();
      for (ShopPackets.ShopEntryData e : this.entries) {
         if (!e.itemId().startsWith("merchantmind:__cat_")) {
            ids.add(e.itemId());
         }
      }
      if (ids.isEmpty()) {
         this.forcedTarget = "";
      } else if (this.forcedTarget.isEmpty()) {
         this.forcedTarget = ids.get(0);
      } else {
         int i = ids.indexOf(this.forcedTarget);
         this.forcedTarget = i >= 0 && i + 1 < ids.size() ? ids.get(i + 1) : "";
      }
      this.analyzeTrade(false);
   }

   private void analyzeTrade(boolean commit) {
      this.thinking = !commit;
      this.rebuild();
      ClientPlayNetworking.send(new ShopPackets.TradeC2SPayload(this.forcedTarget, commit));
   }

   private void analyzeSell(boolean commit) {
      this.thinking = !commit;
      this.rebuild();
      ClientPlayNetworking.send(new ShopPackets.SellC2SPayload(commit));
   }

   /**
    * 4.1 - the Submit button used to be enabled only while a widget rebuild happened, and rebuilds
    * only ran on the once-a-second server sync. Dropping in the 4th iron therefore left the button
    * dead for up to a second (and forever if no sync followed). Slot-driven buttons are now
    * refreshed from the live container every client tick.
    */
   @Override
   protected void containerTick() {
      super.containerTick();

      // 6.1 - the only place the animation clocks move. Guarded so nothing here can break a tick.
      this.animTicks++;
      // 3.2.0 - the particles are the one part of the animation layer with state to advance
      this.stepParticles();
      if (this.openTicks < 1000) {
         this.openTicks++;
      }
      if (this.rowAnimTicks < 1000) {
         this.rowAnimTicks++;
      }
      // 2.2 - the clock the live restock countdowns are interpolated against
      if (this.ticksSinceSync < 100000) {
         this.ticksSinceSync++;
      }
      /*
       * 6.1 / 1.1 (2.7.0) - all easing runs here, at a fixed 20 Hz, so every animation takes the
       * same wall-clock time on every machine regardless of framerate.
       */
      for (int i = 0; i < this.tabHover.length; i++) {
         float target = (this.tabHoverMask >> i & 1) != 0 ? 1.0F : 0.0F;
         this.tabHover[i] += (target - this.tabHover[i]) * HOVER_SPEED;
         if (Math.abs(target - this.tabHover[i]) < 0.01F) {
            this.tabHover[i] = target;
         }
      }
      if (this.selectorGoal >= 0.0F && this.selectorY >= 0.0F) {
         this.selectorY += (this.selectorGoal - this.selectorY) * 0.35F;
      }

      // roll the coin counter 25% of the remaining distance per tick, snapping when close
      double gap = this.balance - this.shownBalance;
      this.shownBalance = Math.abs(gap) < 1.0 ? this.balance : this.shownBalance + gap * 0.25;

      /*
       * 2.2 (2.7.0) - the iron shortcut only exists WHILE a refill is running, which is the exact
       * opposite of the 2.6.0 rule this line used to carry ("not while restocking").
       */
      if (this.submitButton != null) {
         this.submitButton.active = this.restocking && this.ironCount() >= ShopManager.RESTOCK_IRON_COST;
      }
      if (this.tradeCommitButton != null) {
         this.tradeCommitButton.active = this.tradeResultQty > 0 && !this.menu.tradeInput.isEmpty();
      }
      if (this.sellCommitButton != null) {
         this.sellCommitButton.active = this.sellQuote > 0L && !this.menu.sellInput.isEmpty();
      }
   }

   /* ---------------- slot gating ---------------- */

   /**
    * 1.1 (2.8.0) - one hash lookup, where this used to be menu.slots.indexOf(slot).
    *
    * <p>extractSlot() calls this for all 57 slots every frame, so the old linear scan cost ~3,000
    * reference comparisons per frame; assertNoOverlap() multiplied it again. The answer never
    * changes after the menu is built, so ShopMenu now keeps the set.
    */
   private boolean slotActive(Slot slot) {
      return !this.menu.isCustomSlot(slot) || slot.isActive();
   }

   @Override
   protected void extractSlot(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY) {
      if (this.slotActive(slot)) {
         super.extractSlot(g, slot, mouseX, mouseY);
      }
   }

   @Override
   protected void slotClicked(Slot slot, int slotId, int button, ContainerInput input) {
      if (slot == null || this.slotActive(slot)) {
         super.slotClicked(slot, slotId, button, input);
      }
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      Slot slot = this.slotAt(event.x(), event.y());
      if (slot != null && !this.slotActive(slot)) {
         /*
          * 3.2.0 - a click on a dead slot is swallowed here (see 2.2), and used to produce nothing
          * whatsoever: no sound, no movement, no message. That is indistinguishable from the game
          * having frozen, so the ripple below is the click's only feedback and is deliberately drawn
          * in the "denied" colour rather than the neutral one.
          */
         this.clickAt = this.animTicks;
         this.clickX = (int)event.x();
         this.clickY = (int)event.y();
         this.clickDenied = true;
         return true;
      }
      this.clickAt = this.animTicks;
      this.clickX = (int)event.x();
      this.clickY = (int)event.y();
      this.clickDenied = false;
      return super.mouseClicked(event, doubleClick);
   }

   /**
    * 3.2.0 - the expanding ring left behind by the last click, anywhere on the screen.
    *
    * <p>Drawn in screen space, not panel space, because it has to work over the inventory and the
    * sidebar as well as the content column. Denied clicks ripple red and do not travel as far, which
    * is what separates "nothing happened because you missed" from "nothing happened yet".
    */
   private void drawClickRipple(GuiGraphicsExtractor g, float age) {
      float t = age / CLICK_TICKS;
      float grow = easeOut(t);
      float fade = 1.0F - t;
      int color = withAlpha(this.clickDenied ? C_BAD : C_ACCENT, fade * 0.8F);
      ShopFx.ring(g, this.clickX, this.clickY, grow * (this.clickDenied ? 7.0F : 12.0F), color, 1);
      if (!this.clickDenied) {
         ShopFx.ring(g, this.clickX, this.clickY, grow * 6.0F, withAlpha(0xFFFFFFFF, fade * 0.45F), 1);
      }
   }

   private Slot slotAt(double mx, double my) {
      Slot inactive = null;
      for (Slot slot : this.menu.slots) {
         if (mx >= this.leftPos + slot.x - 1 && mx < this.leftPos + slot.x + 17 && my >= this.topPos + slot.y - 1 && my < this.topPos + slot.y + 17) {
            if (this.slotActive(slot)) {
               return slot;
            }
            if (inactive == null) {
               inactive = slot;
            }
         }
      }
      return inactive;
   }

   @Override
   public boolean mouseReleased(MouseButtonEvent event) {
      for (ShopScreen.Rect rect : this.hitRects) {
         if (rect.has(event.x(), event.y())) {
            ShopScreen.Tab clicked = ShopScreen.Tab.valueOf(rect.tab());
            if (clicked != this.tab) {
               this.tab = clicked;
               this.page = 0;
               // 1.1 (2.7.0) - a tab switch changes every derived value, so the view model is rebuilt
               // here, once, rather than being recomputed by the render pass
               this.refreshView();
               this.replayRows();
               this.syncTabState();
               this.rebuild();
            }
            return true;
         }
      }
      return super.mouseReleased(event);
   }

   /* ---------------- rendering ---------------- */

   @Override
   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
      this.frameDelta = delta;
      int x = this.leftPos;
      int y = this.topPos;

      g.fill(x, y, x + this.imageWidth, y + this.imageHeight, C_PANEL);
      g.fill(x + 1, y + HEADER_H, x + this.imageWidth - 1, y + this.imageHeight - 1, C_BODY);
      this.border(g, x, y, this.imageWidth, this.imageHeight, C_BORDER);

      g.fill(x + 1, y + 1, x + this.imageWidth - 1, y + HEADER_H - 1, C_HEADER);

      /*
       * 3.2.0 - the header is the one strip of this screen that is always visible whatever tab is
       * open, so it is where the "this thing is alive" motion lives: a slow light wash across the
       * bar, and a highlight travelling through the title one letter at a time.
       */
      ShopFx.shine(g, x + 1, y + 1, this.imageWidth - 2, HEADER_H - 2, ShopFx.saw(this.clock(), 150.0F), C_ACCENT, 0.10F);
      ShopFx.shimmerText(g, this.font, "✦ QUEST SHOP", x + 8, y + 6, C_GOLD, 0xFFFFFFFF, ShopFx.saw(this.clock(), 90.0F), true);

      /*
       * 6.1 - the coin total rolls to its new value instead of jumping, and the whole header line
       * glints gold->white while it is still moving, so a purchase is visible even if you were
       * looking at the item grid.
       */
      long shown = Math.round(this.shownBalance);
      boolean rolling = shown != this.balance;
      String coins = "♦ " + shown + "c";
      int coinColor = rolling ? mix(C_GOLD, 0xFFFFFFFF, this.pulse(6.0F)) : C_GOLD;
      int coinX = x + this.imageWidth - 60 - this.font.width(coins);
      g.text(this.font, coins, coinX, y + 6, coinColor, true);

      /*
       * 3.2.0 - and the amount it changed by floats up out of the readout and fades, the way a
       * damage number does. The rolling counter says what you have; this says what just happened,
       * which is the part that is easy to miss when the number is large.
       */
      float coinAge = this.age(this.coinAt, COIN_TICKS);
      if (coinAge >= 0.0F && this.coinDelta != 0L) {
         float t = coinAge / COIN_TICKS;
         String floater = (this.coinDelta > 0L ? "+" : "") + this.coinDelta + "c";
         int rise = (int)(ShopFx.easeOut(t) * 11.0F);
         int color = ShopFx.withAlpha(this.coinDelta > 0L ? C_GOOD : C_BAD, 1.0F - t * t);
         g.text(this.font, floater, coinX + this.font.width(coins) - this.font.width(floater), y + 6 - rise, color, true);
      }

      // header underline sweeps across on open
      int sweep = (int)(easeOut(this.openProgress()) * (this.imageWidth - 2));
      if (sweep > 0) {
         g.fill(x + 1, y + HEADER_H - 1, x + 1 + sweep, y + HEADER_H, C_GOLD);
      }
      /*
       * 3.2.0 - once that entrance sweep has finished the underline keeps a bead of light running
       * along it forever. It is two dozen fills and it is the only thing on the screen that is
       * still moving when the player is doing nothing at all.
       */
      if (sweep >= this.imageWidth - 2) {
         ShopFx.glint(g, x + 1, y + HEADER_H - 1, this.imageWidth - 2, ShopFx.saw(this.clock(), 70.0F), 0xFFFFFFFF, 14);
      }

      this.drawSidebar(g, mouseX, mouseY, x, y);

      /*
       * 1.2 - draw order matters, and it used to be wrong.
       *
       * The trade result item is not a container slot, it is painted by hand in drawTrade(). The
       * old code called drawSlotBoxes() AFTER the content pass, and slotBox() fills an OPAQUE
       * C_SLOT rectangle - so the empty socket was stamped straight over the item that had just
       * been drawn there and the icon was invisible.
       *
       * Passes are now strictly back-to-front:
       *   1. empty slot sockets (background)
       *   2. content: rows, hand-drawn items, AI text
       *   3. super - vanilla widgets, container slot contents, tooltips
       *
       * 1.1 (2.6.0) - what used to be pass 3, the full width status strip, is deleted. It was the
       * one thing in this method that drew outside the content column, and that is exactly what
       * made it land on top of the sidebar's Trade and Sell rows.
       */
      this.drawSlotBoxes(g, x, y);

      if (this.tab.category != null) {
         this.drawCatalog(g, mouseX, mouseY, x, y);
      } else if (this.tab == ShopScreen.Tab.TRADE) {
         this.drawTrade(g, x, y);
      } else if (this.tab == ShopScreen.Tab.SELL) {
         this.drawSell(g, x, y);
      } else {
         this.drawHelp(g, x, y);
      }

      this.drawPrimaryHalo(g);

      /*
       * 3.2.0 - sparks and the click ripple go last of the custom layers but still BEFORE the
       * vanilla widgets, for the same reason the halo does: they are decoration, and a button or an
       * item they cover up is a button the player cannot read. Both are screen-space, not panel-
       * space, so they are drawn here rather than inside any one panel.
       */
      this.drawParticles(g);
      float clickAge = this.age(this.clickAt, CLICK_TICKS);
      if (clickAge >= 0.0F) {
         this.drawClickRipple(g, clickAge);
      }

      super.extractRenderState(g, mouseX, mouseY, delta);
   }

   private void drawSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y) {
      g.fill(x + SIDE_X, y + SIDE_Y - 2, x + SIDE_X + SIDE_W, y + SIDE_Y + TABS.length * SIDE_ROW_H + 2, C_SIDE);
      this.border(g, x + SIDE_X, y + SIDE_Y - 2, SIDE_W, TABS.length * SIDE_ROW_H + 4, C_BORDER);

      ShopScreen.Tab[] tabs = TABS;
      int selectedIndex = 0;
      this.tabHoverMask = 0; // rebuilt from scratch each frame; containerTick() reads the latest

      for (int i = 0; i < tabs.length; i++) {
         ShopScreen.Tab t = tabs[i];
         int ty = y + SIDE_Y + i * SIDE_ROW_H;
         int tx = x + SIDE_X + 1;
         int tw = SIDE_W - 2;
         boolean selected = t == this.tab;
         boolean hover = mouseX >= tx && mouseX < tx + tw && mouseY >= ty && mouseY < ty + SIDE_ROW_H - 1;
         if (selected) {
            selectedIndex = i;
         }

         /*
          * 6.1 - the hover highlight used to snap on and off with the cursor. Each row keeps its own
          * 0..1 ramp that chases the hover state, so moving down the list leaves a short trail of
          * fading rows behind the cursor instead of a hard flicker.
          *
          * 1.1 (2.7.0) - the ramp itself is advanced in containerTick(), not here. Advancing it in
          * the render pass made the fade run at the framerate: the same 28%-per-call step is a ~4
          * frame fade at 30 fps and a ~40 frame one at 300 fps, so the effect looked different on
          * every machine and ran at a different speed whenever the framerate moved. Render now only
          * records what is hovered.
          */
         if (hover) {
            this.tabHoverMask |= 1 << i;
         }

         // each row slides in from the left, one tick apart, when the screen opens
         float in = easeOut(this.openProgress(i * 0.8F));
         int slide = (int)((1.0F - in) * 10.0F);

         if (selected) {
            g.fill(tx, ty, tx + tw, ty + SIDE_ROW_H - 1, C_TAB_ACTIVE);
            /*
             * 3.2.0 - the selected row breathes and carries its own light sweep, so which tab you
             * are on is legible from motion alone rather than only from a slightly bluer rectangle.
             */
            ShopFx.box(g, tx, ty, tx + tw, ty + SIDE_ROW_H - 1, ShopFx.withAlpha(C_ACCENT, 0.10F + 0.14F * this.pulse(34.0F)));
            ShopFx.shine(g, tx, ty, tw, SIDE_ROW_H - 1, ShopFx.saw(this.clock(), 55.0F), 0xFFFFFFFF, 0.20F);
         } else if (this.tabHover[i] > 0.0F) {
            g.fill(tx, ty, tx + tw, ty + SIDE_ROW_H - 1, withAlpha(C_TAB_HOVER, this.tabHover[i]));
            // 3.2.0 - and a hovered row gets a faster sweep, which reads as "this one is live"
            ShopFx.shine(g, tx, ty, tw, SIDE_ROW_H - 1, ShopFx.saw(this.clock(), 22.0F), C_ACCENT, 0.30F * this.tabHover[i]);
         }

         /*
          * 3.2.0 - the icon of the selected tab bobs a pixel, and a hovered one leans right. Both
          * are single pixel moves on purpose: at 10px an icon that travels further stops reading as
          * the same object moving and starts reading as a redraw.
          */
         int bob = selected ? (int)Math.round(Math.sin(this.clock() / 13.0F) * 1.2F) : 0;
         int lean = (int)(this.tabHover[i] * 2.0F);
         this.icon(g, t, tx + 4 - slide + lean, ty + 1 + bob, 10);
         int label = mix(selected ? C_TEXT : C_DIM, C_TEXT, this.tabHover[i]);
         g.text(this.font, t.display, tx + 17 - slide + lean, ty + 2, withAlpha(label, in), false);
      }

      /*
       * 6.1 - one gold bar for the whole list, chased toward the selected row. Switching tabs now
       * reads as the marker travelling there, which also makes it obvious which way you moved.
       */
      // 1.1 (2.7.0) - as with the hover ramps, the chase is stepped once per tick in containerTick()
      this.selectorGoal = y + SIDE_Y + selectedIndex * SIDE_ROW_H;
      if (this.selectorY < 0.0F) {
         this.selectorY = this.selectorGoal; // first frame: no flight in from the top of the screen
      }
      int barY = Math.round(this.selectorY);
      /*
       * 3.2.0 - the marker now smears while it is travelling. The gap between where it is and where
       * it is going is drawn as a dim gold column, so a tab switch reads as one object moving the
       * whole distance instead of a bar that is briefly in two places. It costs nothing when the bar
       * has arrived, because then the gap is zero.
       */
      int goalY = Math.round(this.selectorGoal);
      if (Math.abs(goalY - barY) > 1) {
         int trailTop = Math.min(barY, goalY);
         int trailBottom = Math.max(barY, goalY) + SIDE_ROW_H - 1;
         ShopFx.box(g, x + SIDE_X + 1, trailTop, x + SIDE_X + 3, trailBottom, ShopFx.withAlpha(C_GOLD, 0.35F));
      }
      g.fill(x + SIDE_X + 1, barY, x + SIDE_X + 3, barY + SIDE_ROW_H - 1, C_GOLD);
      // a brighter cap at the top and bottom of the marker, breathing, so it never sits fully still
      int cap = ShopFx.withAlpha(0xFFFFFFFF, 0.25F + 0.45F * this.pulse(26.0F));
      ShopFx.box(g, x + SIDE_X + 1, barY, x + SIDE_X + 3, barY + 1, cap);
      ShopFx.box(g, x + SIDE_X + 1, barY + SIDE_ROW_H - 2, x + SIDE_X + 3, barY + SIDE_ROW_H - 1, cap);

      g.fill(x + SIDE_X, y + SIDE_FOOT_Y, x + SIDE_X + SIDE_W, y + SIDE_FOOT_Y + SIDE_FOOT_H, C_SIDE);
      this.border(g, x + SIDE_X, y + SIDE_FOOT_Y, SIDE_W, SIDE_FOOT_H, C_BORDER);
      g.text(this.font, "KEYS", x + SIDE_X + 6, y + SIDE_FOOT_Y + 5, C_ACCENT, false);
      this.keyHint(g, x + SIDE_X + 6, y + SIDE_FOOT_Y + 16, MerchantMindClient.openShopKey, "shop");
      this.keyHint(g, x + SIDE_X + 6, y + SIDE_FOOT_Y + 25, MerchantMindClient.killItemsKey, "clear drops");
      this.keyHint(g, x + SIDE_X + 6, y + SIDE_FOOT_Y + 34, MerchantMindClient.slamKey, "charge slam");
   }

   /**
    * 4.1 - one "&lt;key&gt; does &lt;thing&gt;" line, with the key read live from the binding.
    *
    * <p>The key part is drawn in the accent colour so it stays readable, and the description is
    * trimmed to whatever space is left in the sidebar - a rebind to something like LEFT CONTROL is
    * far wider than "V" and used to be able to run off the panel.
    */
   private void keyHint(GuiGraphicsExtractor g, int tx, int ty, KeyMapping mapping, String what) {
      String key = MerchantMindClient.keyLabel(mapping);
      int room = SIDE_W - 12;
      String keyShown = this.trim(key, room);
      g.text(this.font, keyShown, tx, ty, C_ACCENT, false);
      int used = this.font.width(keyShown) + 4;
      if (used < room) {
         g.text(this.font, this.trim(what, room - used), tx + used, ty, C_DIM, false);
      }
   }

   private void drawHeaderBlock(GuiGraphicsExtractor g, int x, int y, String title, String subtitle) {
      this.icon(g, this.tab, x + CONTENT_X + 2, y + HEAD_Y + 1, HEAD_ICON);
      g.text(this.font, title, x + TITLE_X, y + HEAD_Y + 1, C_GOLD, true);
      g.text(this.font, subtitle, x + TITLE_X, y + HEAD_Y + 13, C_DIM, false);
      g.fill(x + CONTENT_X, y + HEAD_Y + HEAD_H, x + CONTENT_R, y + HEAD_Y + HEAD_H + 1, C_BORDER);
   }

   private void drawCatalog(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y) {
      ShopPackets.ShopEntryData status = this.tabStatus;
      boolean restocking = status != null && status.restocking();
      this.restocking = restocking;
      this.restockBusy = restocking || this.restockRequested;

      /*
       * 2.2 - the two clocks, both live. live() subtracts the client ticks elapsed since the last
       * sync, so they run smoothly on screen instead of stepping once a second, and they keep
       * running if a sync is late. The server corrects them on every sync, so they cannot drift.
       *
       *   restocking -> restockLeft = seconds until the shelves are back
       *   idle       -> autoLeft    = seconds until the shop restocks this category by itself
       */
      int restockLeft = status == null ? 0 : this.live(status.restockSecondsRemaining());
      int autoLeft = status == null ? 0 : this.live(status.secondsUntilAuto());

      String subtitle;
      if (restocking) {
         /*
          * 3.3.0 - three triggers, three words. "manual/auto" was a boolean read out loud, and it
          * called a sold-out shelf an "auto restock" while timing it like a manual one, which is
          * precisely the confusion this release exists to remove.
          */
         subtitle = restockKindLabel(this.tabReason) + " · " + formatDelay(restockLeft) + " left · buying locked";
      } else {
         subtitle = this.catCount + " offers · page " + (this.page + 1) + "/" + (this.maxPage + 1) + " · next restock " + formatDelay(autoLeft);
      }
      this.drawHeaderBlock(g, x, y, this.tab.display, subtitle);

      /*
       * 3.2.0 - catch the moment a refill ENDS, so the panel can celebrate it.
       *
       * The server already tells every client whether a category is restocking; the only thing
       * missing was noticing the flag go false. The tab is compared as well, because arriving on a
       * different category that happens not to be restocking is not the same event as watching one
       * finish - without that check, switching tabs would set off the burst.
       */
      if (this.sawTab == this.tab && this.sawRestocking && !restocking) {
         this.readyAt = this.animTicks;
         this.readyX = x + (CONTENT_X + CONTENT_R) / 2;
         this.readyY = y + MAIN_Y + MAIN_H / 2;
         this.spawn(this.readyX, this.readyY, 26, C_GOOD, 3.2F, -2.6F);
         this.spawn(this.readyX, this.readyY, 10, C_GOLD, 2.2F, -3.0F);
      }
      this.sawRestocking = restocking;
      this.sawTab = this.tab;

      if (restocking) {
         this.drawRestockPanel(g, x, y, restockLeft);
      } else if (this.rows.isEmpty()) {
         this.drawEmptyPanel(g, x, y, autoLeft);
      } else {
         this.drawRows(g, mouseX, mouseY, x, y);
      }

      // drawn over whichever of the three panels is showing, since the shelves have just refilled
      float readyAge = this.age(this.readyAt, READY_TICKS);
      if (readyAge >= 0.0F) {
         this.drawReadyBurst(g, x, y, readyAge);
      }

      /* --- the caption in action row A --- */
      if (status == null) {
         return;
      }
      String caption;
      int captionColor;
      int capX;
      if (restocking) {
         // the paging buttons are not built while restocking, so the caption gets the whole row
         capX = x + CONTENT_X;
         int iron = this.ironCount();
         boolean ready = iron >= ShopManager.RESTOCK_IRON_COST;
         caption = "⟳ " + this.tabReason.display + " · iron " + iron + "/" + ShopManager.RESTOCK_IRON_COST
            + (ready ? " — Submit to finish now" : " — optional shortcut");
         captionColor = ready ? C_GOOD : C_ACCENT;
      } else {
         capX = x + CONTENT_X + 40;
         caption = "Next restock: " + formatDelay(autoLeft);
         captionColor = C_DIM;
      }
      g.text(this.font, this.trim(caption, CONTENT_R - (capX - x) - 4), capX, y + ROW_A_Y + 4, captionColor, false);
   }

   /* ==================================================================== *
    *  3.1 / 3.2 (2.7.0) - the restock panel
    *
    *  While a category is refilling, the item rows are not drawn at all: the
    *  whole central item area becomes the countdown. That is deliberate -
    *  nothing in the rows is actionable during a refill (Buy is locked, the
    *  stock is about to change), so showing them only invites clicks that get
    *  refused. What replaces them is the one thing the player wants to know,
    *  at double size and impossible to miss, plus the shortcut out of it.
    *
    *  Laid out identically whichever of the three triggers started the refill;
    *  the reason line differs, so a player who did not press anything still
    *  learns why the shelves are shut, and since 3.1.0 the progress bar is
    *  scaled to whichever of the two durations this refill is actually on.
    *
    *  3.3.0 - it takes no status row any more. Everything it needed from it was
    *  the trigger, which the screen now resolves once per sync into tabReason.
    * ==================================================================== */
   private void drawRestockPanel(GuiGraphicsExtractor g, int x, int y, int secondsLeft) {
      int left = x + CONTENT_X;
      int right = x + CONTENT_R;
      int top = y + MAIN_Y;
      int mid = (left + right) / 2;

      /*
       * 3.2.0 - the plate is no longer a flat rectangle. Bottom to top it is:
       *
       *   1. a vertical gradient that breathes between two blues, so the panel has depth and is
       *      never the same two frames running;
       *   2. a scanline crawling down it, which is the slowest moving thing here and the one that
       *      makes a 60 second wait feel like something is being processed;
       *   3. embers drifting up from the floor, spawned a few a second while the refill runs.
       *
       * The old flat C_ROW fill is what this replaces; the accent bar on the left edge stays, and
       * now pulses with the same clock as the countdown text so they read as one state.
       */
      ShopFx.vGradient(g, left, top, right - left, MAIN_H, C_ROW, C_ROW_ALT, 8);
      ShopFx.box(g, left, top, right, top + MAIN_H, ShopFx.withAlpha(C_ACCENT, 0.05F + 0.05F * this.pulse(70.0F)));
      ShopFx.scanline(g, left, top, right - left, MAIN_H, ShopFx.saw(this.clock(), 80.0F), C_ACCENT, 0.22F);
      g.fill(left, top, left + 2, top + MAIN_H, mix(C_ACCENT, C_GOLD, this.pulse(24.0F)));

      // 3.2.0 - a couple of embers a second, off the floor of the panel, drifting up and dying
      if (this.animTicks % 7 == 0 && this.frameDelta < 0.5F) {
         this.spawn(mid + (this.animTicks * 37 % (right - left - 40)) - (right - left) / 2 + 20, top + MAIN_H - 2, 1, C_ACCENT, 0.4F, -1.4F);
      }

      /*
       * 6.1 - the glyph spins while the refill runs. pose() is pushed and popped around the blit
       * alone, so the rotation cannot leak into anything drawn afterwards.
       *
       * 3.2.0 - and it now sits inside a small orrery: eight dots orbiting one way, four the other,
       * plus a ring that expands out of it on every whole second. The spinner alone was a texture
       * rotating in place, which at 24px reads as a flicker more than as motion.
       */
      int spinX = left + 20;
      int spinY = top + 20;
      ShopFx.orbit(g, spinX, spinY, 15.0F, 8, this.clock(), 0.05F, ShopFx.withAlpha(C_ACCENT, 0.75F), 2);
      ShopFx.orbit(g, spinX, spinY, 19.0F, 4, this.clock(), -0.03F, ShopFx.withAlpha(C_GOLD, 0.55F), 2);
      g.pose().pushMatrix();
      g.pose().translate(spinX, spinY);
      g.pose().rotate(this.clock() * 0.12F);
      g.pose().translate(-12.0F, -12.0F);
      g.blit(RenderPipelines.GUI_TEXTURED, RESTOCK_IMG, 0, 0, 0.0F, 0.0F, 24, 24, 64, 64, 64, 64);
      g.pose().popMatrix();

      /*
       * 3.1 - the countdown itself, drawn at 2x. The text is built once per whole second (see
       * bigLabel) rather than per frame, because at 200 fps this would otherwise be 200 identical
       * strings a second for something that changes once.
       *
       * 3.2.0 - that once-a-second rebuild is now also the tick that fires the "pop": the label
       * scales up past its own size and settles back, and a ring goes out from the spinner. So every
       * single second of the wait has a beat, instead of only the end of it.
       */
      if (secondsLeft != this.bigLabelSecond) {
         this.bigLabelSecond = secondsLeft;
         this.bigLabel = "Restocking… " + formatDelay(secondsLeft) + " left";
         this.popAt = this.animTicks;
      }
      float popAge = this.age(this.popAt, POP_TICKS);
      float popScale = popAge < 0.0F ? 2.0F : 2.0F + 0.22F * (1.0F - ShopFx.easeOutBack(popAge / POP_TICKS));
      if (popAge >= 0.0F) {
         ShopFx.burst(g, spinX, spinY, popAge / POP_TICKS, 22.0F, C_ACCENT);
      }
      int bigW = (int)(this.font.width(this.bigLabel) * popScale);
      float bigX = mid - bigW / 2.0F;
      // keep it clear of the spinner even if the label is unusually wide
      bigX = Math.max(bigX, left + 38.0F);
      g.pose().pushMatrix();
      g.pose().translate(bigX, top + 8.0F - (popScale - 2.0F) * 4.0F);
      g.pose().scale(popScale, popScale);
      g.text(this.font, this.bigLabel, 0, 0, mix(C_ACCENT, C_GOLD, this.pulse(24.0F)), true);
      g.pose().popMatrix();

      /*
       * the progress bar: the wait needs a visible end, not just a number
       *
       * 3.1.0 - the bar's full length is the duration of THIS refill, which is no longer one
       * number: the trigger travels in the status row, so the client can pick the right total without
       * a packet change. Using the wrong one would make the bar jump - a 30s refill measured against
       * the 60s total would fill only halfway before the countdown reached zero.
       *
       * 3.3.0 - it is scaled from the reason rather than the manual flag. With SOLD_OUT moved onto
       * the longer clock, the flag no longer identifies the clock: a sold-out refill would have been
       * drawn against 30s and hit 100% at its halfway point.
       */
      int barL = left + 20;
      int barR = right - 20;
      int total = ShopManager.restockDurationSeconds(this.tabReason);
      int done = total - secondsLeft;
      float frac = Math.min(1.0F, Math.max(0.0F, done / (float)total));
      /*
       * 3.2.0 - the bar got the full treatment, because it is the thing being stared at. From the
       * back forward: the empty channel, the filled part, diagonal stripes scrolling through the
       * filled part, a light sweep over it, and a bright bead at the leading edge with a glow either
       * side of it. Between two whole seconds the bar's LENGTH does not change at all - everything
       * that moves in that second is one of these.
       *
       * It is also two pixels taller than it was (5 -> 7) purely so the stripes are legible.
       */
      int barTop = top + 29;
      int barH = 7;
      int filled = (int)((barR - barL) * frac);
      g.fill(barL, barTop, barR, barTop + barH, C_SLOT);
      ShopFx.vGradient(g, barL, barTop, filled, barH, mix(C_ACCENT, 0xFFFFFFFF, 0.25F), mix(C_ACCENT, C_GOOD, this.pulse(20.0F)), 4);
      ShopFx.stripes(g, barL, barTop, filled, barH, ShopFx.saw(this.clock(), 16.0F), 0xFFFFFFFF, 0.18F, 8);
      ShopFx.shine(g, barL, barTop, filled, barH, ShopFx.saw(this.clock(), 34.0F), 0xFFFFFFFF, 0.35F);
      if (filled > 1) {
         int head = barL + filled;
         ShopFx.box(g, head - 2, barTop, head, barTop + barH, ShopFx.withAlpha(0xFFFFFFFF, 0.85F));
         ShopFx.box(g, head, barTop - 1, head + 2, barTop + barH + 1, ShopFx.withAlpha(C_GOOD, 0.30F + 0.35F * this.pulse(9.0F)));
      }
      this.border(g, barL, barTop, barR - barL, barH, C_BORDER);

      /*
       * 3.2 - the suggestion. The iron slot is open for the whole refill, so this is always a real
       * option; it says how much iron and where it goes, and switches to "press Submit" once the
       * player actually has enough in the slot.
       */
      int iron = this.ironCount();
      boolean ready = iron >= ShopManager.RESTOCK_IRON_COST;
      String hint = ready
         ? "⚡ " + iron + "/" + ShopManager.RESTOCK_IRON_COST + " iron ready — press Submit to finish instantly"
         : "⚡ Put " + ShopManager.RESTOCK_IRON_COST + " iron in the slot below and Submit to finish instantly (" + iron + "/"
            + ShopManager.RESTOCK_IRON_COST + ")";
      hint = this.trim(hint, right - left - 12);
      int hintX = mid - this.font.width(hint) / 2;
      /*
       * 3.2.0 - when the iron IS ready the hint shimmers green, because at that point it is an
       * instruction and not a note. When it is not, a chevron bounces underneath it pointing at the
       * slot the iron goes in - the one piece of this screen that new players consistently miss,
       * since the slot only exists while a refill is running.
       */
      if (ready) {
         ShopFx.shimmerText(g, this.font, hint, hintX, barTop + 12, C_GOOD, 0xFFFFFFFF, ShopFx.saw(this.clock(), 24.0F), false);
      } else {
         g.text(this.font, hint, hintX, barTop + 12, C_GOLD, false);
         /*
          * The chevron is deliberately drawn OUTSIDE this panel, immediately above the iron slot in
          * action row B, bouncing toward it. Inside the panel it would only have been another
          * decoration; where it is, it answers the question the hint raises ("which slot?"), and it
          * cannot collide with the reason line below - the panel ends well above it.
          */
         int bounce = (int)Math.round(Math.abs(Math.sin(this.clock() / 9.0F)) * 3.0F);
         ShopFx.chevronDown(
            g,
            x + ShopMenu.IRON_X + SLOT_BOX / 2,
            y + ShopMenu.IRON_Y - 5 - bounce,
            4,
            ShopFx.withAlpha(C_GOLD, 0.55F + 0.45F * this.pulse(18.0F))
         );
      }

      String why = this.tabReason.display + " · buying locked until the shelves are full";
      why = this.trim(why, right - left - 12);
      g.text(this.font, why, mid - this.font.width(why) / 2, barTop + 22, C_DIM, false);
   }

   /**
    * 3.2.0 - the payoff: what the panel does the moment the shelves come back.
    *
    * <p>The whole point of this one is that the player is usually NOT looking at the shop screen
    * while a category refills - it is a minute of nothing they can act on. So the end of it is loud:
    * a white wash over the item area that fades over a second and a half, three shockwave rings out
    * of the centre, a spray of sparks, and READY! scaled up with an overshoot. If they looked away
    * and back, the tail of this is still on screen and tells them what they missed.
    *
    * <p>Fired from {@link #drawCatalog} by watching the server's own restocking flag go false, so
    * there is no new packet and no client-side guess about when a refill ends.
    */
   private void drawReadyBurst(GuiGraphicsExtractor g, int x, int y, float age) {
      int left = x + CONTENT_X;
      int right = x + CONTENT_R;
      int top = y + MAIN_Y;
      int mid = (left + right) / 2;
      float t = age / READY_TICKS;

      // the wash: brightest on the first frame, gone by the end, and never fully opaque
      ShopFx.box(g, left, top, right, top + MAIN_H, ShopFx.withAlpha(0xFFFFFFFF, (1.0F - t) * (1.0F - t) * 0.45F));
      ShopFx.burst(g, mid, top + MAIN_H / 2, t, 90.0F, C_GOOD);
      ShopFx.border(g, left, top, right - left, MAIN_H, ShopFx.withAlpha(C_GOOD, 1.0F - t));

      String label = "READY!";
      float scale = 1.6F + 1.2F * ShopFx.easeOutBack(Math.min(1.0F, t * 3.0F));
      float alpha = t > 0.65F ? (1.0F - t) / 0.35F : 1.0F;
      int w = (int)(this.font.width(label) * scale);
      g.pose().pushMatrix();
      g.pose().translate(mid - w / 2.0F, top + MAIN_H / 2.0F - 6.0F * scale);
      g.pose().scale(scale, scale);
      g.text(this.font, label, 0, 0, ShopFx.withAlpha(C_GOOD, alpha), true);
      g.pose().popMatrix();
   }

   /**
    * A category with nothing in it and no refill running.
    *
    * <p>1.1 (2.8.0) - it also covers the handful of frames between opening the screen and the first
    * sync arriving, and those two situations are not the same thing: before any sync the client
    * simply has no data, and saying "Sold out" there was a lie the player could see on every open.
    */
   private void drawEmptyPanel(GuiGraphicsExtractor g, int x, int y, int autoLeft) {
      int mid = x + (CONTENT_X + CONTENT_R) / 2;
      int cy = y + MAIN_Y + 4;
      /*
       * 3.2.0 - this glyph used to sit dead still, which made the two states this panel covers look
       * identical: "the shelves are empty" and "the client has not been told anything yet". It now
       * turns slowly, and while WAITING for the first sync it also gets an orbit and animated dots,
       * so loading looks like loading and sold out looks like a wall.
       */
      boolean waiting = this.tabStatus == null;
      g.pose().pushMatrix();
      g.pose().translate(mid, cy + 16.0F);
      g.pose().rotate(this.clock() * (waiting ? 0.09F : 0.02F));
      g.pose().translate(-16.0F, -16.0F);
      g.blit(RenderPipelines.GUI_TEXTURED, RESTOCK_IMG, 0, 0, 0.0F, 0.0F, 32, 32, 64, 64, 64, 64);
      g.pose().popMatrix();
      if (waiting) {
         ShopFx.orbit(g, mid, cy + 16, 24.0F, 6, this.clock(), 0.07F, ShopFx.withAlpha(C_ACCENT, 0.7F), 2);
      } else {
         ShopFx.ring(g, mid, cy + 16, 22.0F + 3.0F * this.pulse(40.0F), ShopFx.withAlpha(C_BAD, 0.35F), 1);
      }
      String line = waiting
         ? "Loading offers" + ShopFx.ellipsis(this.clock())
         : "Sold out — press ⟳ Restock, or wait " + formatDelay(autoLeft);
      g.text(this.font, line, mid - this.font.width(line) / 2, cy + 36, waiting ? C_DIM : C_BAD, false);
   }

   /** The normal state: up to three offer rows, all of them prepared in refreshView(). */
   private void drawRows(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y) {
      for (int i = 0; i < this.rows.size(); i++) {
         ShopScreen.Row row = this.rows.get(i);
         int ry = y + MAIN_Y + i * ROW_H;
         int rh = ROW_H - 2;
         boolean hover = mouseX >= x + CONTENT_X && mouseX < x + CONTENT_R && mouseY >= ry && mouseY < ry + rh;

         /*
          * 6.1 - rows deal themselves in from the right, a couple of ticks apart, whenever the page
          * or the tab changes. The clock is rowAnimTicks, reset in rebuild().
          */
         float in = easeOut(this.rowProgress(i * 1.2F));
         int slide = (int)((1.0F - in) * 14.0F);
         int rowLeft = x + CONTENT_X + slide;

         g.fill(rowLeft, ry, x + CONTENT_R, ry + rh, hover ? C_ROW_HOVER : (i % 2 == 0 ? C_ROW : C_ROW_ALT));
         /*
          * 3.2.0 - an idle row gets a very slow, very faint wash, offset per row so the three of
          * them are never in phase. It is barely visible by design: enough that the list is not a
          * dead grid, not enough to compete with the row you are actually pointing at.
          */
         ShopFx.box(
            g,
            rowLeft,
            ry,
            x + CONTENT_R,
            ry + rh,
            ShopFx.withAlpha(C_ACCENT, 0.03F + 0.04F * this.pulse(64.0F + i * 11.0F))
         );
         // the accent stripe thickens under the cursor - cheap, obvious "this row is live"
         g.fill(rowLeft, ry, rowLeft + (hover ? 3 : 1), ry + rh, hover ? C_GOLD : C_ACCENT);

         /*
          * 3.2.0 - the hovered row is where most of the new motion goes, because it is the one the
          * player is deciding about: a light sweep across the whole row, a bobbing icon, and the
          * price line shimmering gold. Three effects on one row and none of them on the other two.
          */
         if (hover) {
            ShopFx.shine(g, rowLeft, ry, x + CONTENT_R - rowLeft, rh, ShopFx.saw(this.clock(), 26.0F), 0xFFFFFFFF, 0.16F);
            ShopFx.box(g, rowLeft, ry, x + CONTENT_R, ry + 1, ShopFx.withAlpha(C_GOLD, 0.5F));
            ShopFx.box(g, rowLeft, ry + rh - 1, x + CONTENT_R, ry + rh, ShopFx.withAlpha(C_GOLD, 0.5F));
         }

         if (!row.icon().isEmpty()) {
            int lift = hover ? (int)Math.round(Math.sin(this.clock() / 7.0F) * 1.4F) - 1 : 0;
            g.item(row.icon(), rowLeft + 5, ry + 3 + lift);
         }
         g.text(this.font, row.name(), rowLeft + 27, ry + 2, withAlpha(C_TEXT, in), false);
         if (hover && in >= 1.0F) {
            ShopFx.shimmerText(
               g, this.font, row.priceLine(), rowLeft + 27, ry + 12, C_TEXT, C_GOLD, ShopFx.saw(this.clock(), 30.0F), false
            );
         } else {
            g.text(this.font, row.priceLine(), rowLeft + 27, ry + 12, withAlpha(hover ? C_TEXT : C_DIM, in), false);
         }
      }
   }

   /**
    * 2.2 - a server countdown, advanced locally so it visibly runs.
    *
    * <p>The server only syncs once a second, so a value drawn exactly as received freezes between
    * syncs and stops dead if one is late or dropped. Every countdown on this screen is therefore
    * the last value the server sent, minus the whole seconds that have elapsed on the client since
    * that sync. Floored at zero, and corrected on the next sync, so it can never run ahead.
    */
   private int live(int serverSeconds) {
      return Math.max(0, serverSeconds - this.ticksSinceSync / 20);
   }

   /**
    * "4m 05s" reads better than "245s" for the multi-minute auto restock timer.
    *
    * <p>1.1 (2.7.0) - the seconds used to be padded with String.format, which parses its format
    * string on every call and boxes the argument. This runs on countdowns that are drawn every
    * frame, so the padding is done by hand instead; the output is identical.
    */
   private static String formatDelay(int seconds) {
      int s = Math.max(0, seconds);
      if (s < 60) {
         return s + "s";
      }
      int rest = s % 60;
      return s / 60 + "m " + (rest < 10 ? "0" : "") + rest + "s";
   }

   /**
    * 3.3.0 - the two-word name of a refill for the header, one per trigger.
    *
    * <p>There are three of them and they no longer split 1:2 by duration the way the old
    * "manual restock" / "auto restock" pair implied: a sold-out refill is timed like a manual one but
    * nobody pressed anything, so it gets its own words rather than being filed under either.
    */
   private static String restockKindLabel(ShopManager.RestockReason reason) {
      return switch (reason) {
         case MANUAL -> "manual restock";
         case SOLD_OUT -> "sold-out restock";
         default -> "auto restock";
      };
   }

   private void drawTrade(GuiGraphicsExtractor g, int x, int y) {
      this.drawHeaderBlock(g, x, y, "Trade", "slot 2 opens once slot 1 is filled");
      /*
       * 3.2.0 - the arrow slides toward the result and brightens instead of sitting there. It is the
       * only thing on this tab that says which direction the trade goes, so it may as well point.
       */
      int nudge = (int)Math.round(this.pulse(20.0F) * 3.0F);
      g.text(
         this.font, "→", x + ShopMenu.RESULT_X - 16 + nudge, y + ShopMenu.INPUT_Y + 5, mix(C_DIM, C_ACCENT, this.pulse(20.0F)), false
      );

      ItemStack result = this.tradeResultStack;
      if (!result.isEmpty()) {
         /*
          * 3.2.0 - a result the AI has approved bobs, sits inside a breathing ring, and throws the
          * occasional spark. Before this it appeared in the socket with no transition at all, which
          * made a successful analysis look the same as an empty slot with something in it.
          */
         int rx = x + ShopMenu.RESULT_X;
         int ry = y + ShopMenu.INPUT_Y;
         int lift = (int)Math.round(Math.sin(this.clock() / 11.0F) * 1.5F);
         ShopFx.ring(g, rx + 8, ry + 8, 12.0F + 1.5F * this.pulse(30.0F), ShopFx.withAlpha(this.tradeApproved ? C_GOOD : C_ACCENT, 0.35F), 1);
         g.item(result, rx, ry + lift);
         if (this.tradeResultQty > 1) {
            g.itemDecorations(this.font, new ItemStack(result.getItem(), Math.min(this.tradeResultQty, 99)), rx, ry + lift);
         }
         if (this.tradeApproved && this.animTicks % 11 == 0 && this.frameDelta < 0.5F) {
            this.spawn(rx + 8, ry + 4, 1, C_GOOD, 0.8F, -0.9F);
         }
      }

      // 3.2.0 - "thinking..." now actually cycles its dots, so a slow server looks busy, not stuck
      String status = "⚙ Trade AI: "
         + (this.thinking ? "thinking" + ShopFx.ellipsis(this.clock()) : (this.tradeApproved ? "APPROVED ✓" : "waiting"));
      this.drawAiBox(g, x, y, status, this.tradeAi, this.thinking ? C_ACCENT : (this.tradeApproved ? C_GOOD : C_DIM));
   }

   private void drawSell(GuiGraphicsExtractor g, int x, int y) {
      this.drawHeaderBlock(g, x, y, "Sell", "a 2nd AI prices what you put in");
      String status = "⚙ Price AI: "
         + (this.thinking ? "appraising" + ShopFx.ellipsis(this.clock()) : (this.sellQuote > 0L ? this.sellQuote + "c offered" : "waiting"));
      this.drawAiBox(g, x, y, status, this.sellAi, this.thinking ? C_ACCENT : (this.sellQuote > 0L ? C_GOOD : C_DIM));
   }

   /**
    * 1.1 (2.8.0) - the help text, built only when it can actually have changed.
    *
    * <p>The 16 lines were rebuilt from scratch on every frame the Help tab was open: a String[] plus
    * roughly six concatenations, thrown away microseconds later. Nothing in them changes except the
    * three key names, which only change when the player rebinds a key, so the labels are compared
    * (three reference-equal string compares in the normal case) and the array is reused.
    */
   private void drawHelp(GuiGraphicsExtractor g, int x, int y) {
      this.drawHeaderBlock(g, x, y, "Help", "everything this mod adds");

      // 4.1 - never spell a key out by hand; ask the binding what it is right now.
      String kShop = MerchantMindClient.keyLabel(MerchantMindClient.openShopKey);
      String kKill = MerchantMindClient.keyLabel(MerchantMindClient.killItemsKey);
      String kSlam = MerchantMindClient.keyLabel(MerchantMindClient.slamKey);

      if (this.helpLines == null || !kShop.equals(this.helpShopKey) || !kKill.equals(this.helpKillKey) || !kSlam.equals(this.helpSlamKey)) {
         // 2.1 (2.8.0) - only mentioned when the mod it needs is actually installed
         String cinematic = MerchantMindClient.respawnCameraActive
            ? "Cinematic Respawn found: dying goes 3rd person, moving"
            : "";
         this.helpShopKey = kShop;
         this.helpKillKey = kKill;
         this.helpSlamKey = kSlam;
         this.helpLines = new String[] {
            "Each category keeps its own stock and its own coins.",
            "THREE things restock a category, on TWO clocks, and the",
            "header counts down whichever one is running:",
            /*
             * 3.1.0 - the two flows no longer take the same time, so each one has to quote its own
             * length. Deliberately still SEVEN lines, the same as the block it replaces, and none
             * of them longer than the longest line this tab already had: the help text is drawn
             * into a fixed number of rows (see the loop below) with no scrolling, so a line added
             * here is a line silently lost off the bottom.
             */
            /*
             * 3.2.0 - the interval label comes from the manager now. This line used to divide the two
             * interval constants by 60 itself, which printed "every 1-1min" as soon as the spread
             * went to zero.
             */
            /*
             * 3.3.0 - three triggers to describe instead of two, still in SEVEN lines. Every length
             * is asked for by trigger, so this tab cannot quote a number the server is not using -
             * which is the whole reason restockDelayLabel() has no no-argument form.
             */
            "AUTO — every " + ShopManager.autoIntervalLabel() + " the shop refills itself,",
            "and that one is quick: " + ShopManager.restockDelayLabel(ShopManager.RestockReason.SCHEDULED) + ". Nothing to press.",
            "SOLD OUT — buy the last offer and it refills at once.",
            "MANUAL — press ⟳ Restock. Both of those take "
               + ShopManager.restockDelayLabel(ShopManager.RestockReason.MANUAL) + ",",
            "since demand caused them. " + ShopManager.RESTOCK_IRON_COST + " iron + Submit ends it now.",
            "Buying is locked for the whole refill, every way in:",
            "no iron, no shopping from that category until it ends.",
            "Trade / Sell reveal one slot at a time, up to 5.",
            "Left click = whole stack, right click = one item.",
            "",
            kShop + " opens this shop, " + kKill + " clears dropped items.",
            kSlam + " charges the slam — hold it for more bonus damage",
            "on the next sword/axe hit. The screen stretches as it",
            "winds up; it never zooms.",
            cinematic,
            MerchantMindClient.respawnCameraActive ? "again brings your own view back. F5 still yours." : ""
         };
      }

      /*
       * The help tab has no buttons, so it is the one tab allowed to spill out of the main band and
       * use the action band too. The hard stop at CONTENT_BOTTOM keeps it from ever reaching the
       * inventory label below it, however long a rebound key name gets. (It used to stop at
       * STATUS_Y, which no longer exists - the status strip was removed in 2.6.0.)
       */
      int ly = y + MAIN_Y;
      int stop = y + CONTENT_BOTTOM - 9;
      for (String line : this.helpLines) {
         if (ly > stop) {
            break;
         }
         g.text(this.font, this.trim(line, CONTENT_R - CONTENT_X - 4), x + CONTENT_X + 2, ly, C_TEXT, false);
         ly += 9;
      }
   }

   /** Bordered panel for the AI status + reasoning, so that text can never reach the buttons. */
   private void drawAiBox(GuiGraphicsExtractor g, int x, int y, String status, String reasoning, int statusColor) {
      int left = x + CONTENT_X;
      int right = x + CONTENT_R;
      g.fill(left, y + AI_BOX_Y, right, y + AI_BOX_Y + AI_BOX_H, C_SIDE);
      this.border(g, left, y + AI_BOX_Y, right - left, AI_BOX_H, C_BORDER);
      g.text(this.font, this.trim(status, right - left - 8), left + 4, y + AI_BOX_Y + 4, statusColor, false);
      this.drawWrapped(g, reasoning, left + 4, y + AI_BOX_Y + 16, 3);
   }

   private void drawWrapped(GuiGraphicsExtractor g, String text, int x, int y, int maxLines) {
      int shown = 0;
      for (String line : this.wrap(text, AI_TEXT_W)) {
         if (shown++ >= maxLines) {
            break;
         }
         g.text(this.font, line, x, y, C_TEXT, false);
         y += 9;
      }
   }

   private void drawSlotBoxes(GuiGraphicsExtractor g, int x, int y) {
      /*
       * 6.1 - the socket the game is currently waiting on breathes. There is exactly one at a time
       * (the iron slot once a restock is armed, otherwise the next empty input), which is what
       * makes it a hint rather than noise.
       */
      // 2.2 (2.7.0) - no running restock, no iron socket. Matches ShopMenu's Slot.isActive() exactly.
      if (this.tabCategory != null && this.menu.ironSlotOpen(this.tabCategory)) {
         boolean fed = this.ironCount() >= ShopManager.RESTOCK_IRON_COST;
         this.slotBox(g, x + ShopMenu.IRON_X, y + ShopMenu.IRON_Y,
            fed ? C_GOOD : mix(C_GOLD, 0xFFFFF0C0, this.pulse(22.0F)));
      }
      if (this.tab == ShopScreen.Tab.TRADE || this.tab == ShopScreen.Tab.SELL) {
         boolean trade = this.tab == ShopScreen.Tab.TRADE;
         int firstEmpty = -1;
         for (int i = 0; i < ShopMenu.TRADE_SLOTS; i++) {
            boolean live = i == 0
               || !(trade ? this.menu.tradeInput : this.menu.sellInput).getItem(i - 1).isEmpty();
            if (!live) {
               continue;
            }
            if (firstEmpty < 0 && (trade ? this.menu.tradeInput : this.menu.sellInput).getItem(i).isEmpty()) {
               firstEmpty = i;
            }
            int color = i == firstEmpty ? mix(C_SLOT_BORDER, C_ACCENT, this.pulse(26.0F)) : C_SLOT_BORDER;
            this.slotBox(g, x + ShopMenu.INPUT_X0 + i * ShopMenu.INPUT_STEP, y + ShopMenu.INPUT_Y, color);
         }
         if (trade) {
            // the result socket glows once the AI has actually approved something
            int color = this.tradeResultQty > 0 ? mix(C_GOLD, C_GOOD, this.pulse(18.0F)) : C_GOLD;
            this.slotBox(g, x + ShopMenu.RESULT_X, y + ShopMenu.INPUT_Y, color);
         }
      }
      /*
       * 1.1 (2.7.0) - the player inventory, as a grid instead of 36 separate boxes.
       *
       * One slotBox() is five fills (a background plus four border edges), so the 36 inventory
       * sockets alone cost 180 draw calls every frame - more than the entire rest of the screen.
       * The boxes are adjacent, so their borders are shared edges: drawing the shared edges once as
       * lines gives a pixel-identical result for about 28 fills.
       */
      this.slotGrid(g, x + ShopMenu.INV_X, y + ShopMenu.INV_Y, 3);
      this.slotGrid(g, x + ShopMenu.INV_X, y + ShopMenu.INV_Y + 58, 1);
   }

   /**
    * Draws a rows x 9 block of 18px sockets as a grid.
    *
    * <p>The geometry matches {@link #slotBox} exactly, including the 2px-wide shared edges that
    * appear where two boxes each drew their own 1px border: interior lines are therefore 2px and
    * the outer edges 1px, which is what the per-box version produced.
    */
   private void slotGrid(GuiGraphicsExtractor g, int firstSlotX, int firstSlotY, int rows) {
      int left = firstSlotX - 1;
      int top = firstSlotY - 1;
      int right = left + 162;
      int bottom = top + rows * 18;

      g.fill(left, top, right, bottom, C_SLOT);

      // vertical: the two outer edges, then one shared edge between every pair of columns
      g.fill(left, top, left + 1, bottom, C_SLOT_BORDER);
      g.fill(right - 1, top, right, bottom, C_SLOT_BORDER);
      for (int col = 1; col < 9; col++) {
         g.fill(left + col * 18 - 1, top, left + col * 18 + 1, bottom, C_SLOT_BORDER);
      }

      // horizontal: same again for the rows
      g.fill(left, top, right, top + 1, C_SLOT_BORDER);
      g.fill(left, bottom - 1, right, bottom, C_SLOT_BORDER);
      for (int row = 1; row < rows; row++) {
         g.fill(left, top + row * 18 - 1, right, top + row * 18 + 1, C_SLOT_BORDER);
      }
   }

   private void slotBox(GuiGraphicsExtractor g, int x, int y, int borderColor) {
      g.fill(x - 1, y - 1, x + 17, y + 17, C_SLOT);
      this.border(g, x - 1, y - 1, 18, 18, borderColor);
   }

   private void icon(GuiGraphicsExtractor g, ShopScreen.Tab tab, int x, int y, int size) {
      g.blit(RenderPipelines.GUI_TEXTURED, tab.icon, x, y, 0.0F, 0.0F, size, size, 64, 64, 64, 64);
   }

   private void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
      g.fill(x, y, x + w, y + 1, color);
      g.fill(x, y + h - 1, x + w, y + h, color);
      g.fill(x, y, x + 1, y + h, color);
      g.fill(x + w - 1, y, x + w, y + h, color);
   }

   /* ==================================================================== *
    *  3.2.0 - particles
    *
    *  A deliberately small, deliberately dumb system: ballistic, no
    *  collision, no rotation, no textures, three fills per particle. It runs
    *  at 20 Hz in containerTick like every other easing in this screen, so
    *  the sparks fall at the same speed on a 30 fps machine and a 300 fps
    *  one, and the render pass only interpolates between two known positions.
    *
    *  It exists because the flat panels this screen is made of have nothing
    *  in them that moves on its own. A burst of sparks off a coin, an iron
    *  submit or a finished refill is the cheapest possible way to make an
    *  event feel like it happened rather than like a number changing.
    * ==================================================================== */

   /** Advances every live particle one tick. Bounded by FX_MAX, allocates nothing. */
   private void stepParticles() {
      for (int i = 0; i < FX_MAX; i++) {
         if (this.fxLife[i] <= 0.0F) {
            continue;
         }
         this.fxX[i] = this.fxX[i] + this.fxVX[i];
         this.fxY[i] = this.fxY[i] + this.fxVY[i];
         this.fxVY[i] = this.fxVY[i] + 0.16F; // gravity, in pixels per tick per tick
         this.fxVX[i] = this.fxVX[i] * 0.94F; // drag, so the spread settles instead of running off
         this.fxLife[i] = this.fxLife[i] - 1.0F;
      }
   }

   /**
    * Throws {@code count} sparks from a point.
    *
    * @param spread how far sideways they scatter, in pixels per tick
    * @param lift   initial vertical speed; negative goes up
    */
   private void spawn(int x, int y, int count, int color, float spread, float lift) {
      for (int n = 0; n < count; n++) {
         int i = this.fxCursor;
         this.fxCursor = (this.fxCursor + 1) % FX_MAX;
         // xorshift-ish: cheap, no allocation, and nobody is auditing these for randomness
         this.fxSeed = this.fxSeed * 1103515245 + 12345;
         int a = this.fxSeed >>> 16 & 0xFF;
         this.fxSeed = this.fxSeed * 1103515245 + 12345;
         int b = this.fxSeed >>> 16 & 0xFF;
         this.fxX[i] = x;
         this.fxY[i] = y;
         this.fxVX[i] = (a / 255.0F - 0.5F) * 2.0F * spread;
         this.fxVY[i] = lift - b / 255.0F * Math.abs(lift) * 0.8F;
         this.fxSpan[i] = 14.0F + b / 255.0F * 10.0F;
         this.fxLife[i] = this.fxSpan[i];
         this.fxColor[i] = color;
      }
   }

   /**
    * Draws the pool.
    *
    * <p>Each spark is a two pixel square that fades over its life, with a dimmer one pixel trail
    * behind it along its own velocity - which costs one extra fill and is the difference between
    * "dots" and "sparks". Position is interpolated by the frame delta so they move smoothly.
    */
   private void drawParticles(GuiGraphicsExtractor g) {
      for (int i = 0; i < FX_MAX; i++) {
         if (this.fxLife[i] <= 0.0F) {
            continue;
         }
         float t = this.fxLife[i] / this.fxSpan[i];
         int px = Math.round(this.fxX[i] + this.fxVX[i] * this.frameDelta);
         int py = Math.round(this.fxY[i] + this.fxVY[i] * this.frameDelta);
         int color = ShopFx.withAlpha(this.fxColor[i], t);
         ShopFx.box(g, px - 1, py - 1, px + 1, py + 1, color);
         ShopFx.box(
            g,
            px - 1 - Math.round(this.fxVX[i]),
            py - 1 - Math.round(this.fxVY[i]),
            px - Math.round(this.fxVX[i]),
            py - Math.round(this.fxVY[i]),
            ShopFx.withAlpha(this.fxColor[i], t * 0.45F)
         );
      }
   }

   /**
    * Age of a one-shot effect in ticks, or a negative number when it is not running.
    *
    * <p>Every 3.2.0 effect is driven by this one method: an event stores {@code animTicks}, and this
    * turns that into "how long ago", frame-smoothed. Returns -1 both when the event has never fired
    * and when it has already finished, so callers only ever test {@code age >= 0}.
    */
   private float age(int at, float span) {
      if (at < 0) {
         return -1.0F;
      }
      float a = this.clock() - at;
      return a >= 0.0F && a <= span ? a : -1.0F;
   }

   /* ---------------- 6.1 animation utils ----------------
    *
    * Deliberately tiny and pure: given a clock, return a number. Nothing in here touches the menu,
    * the network or the container, so the animation layer can never desync anything.
    */

   /** Free running clock in ticks, with the frame fraction added for sub-tick smoothness. */
   private float clock() {
      return this.animTicks + this.frameDelta;
   }

   /** 0 while the screen is opening, 1 once the entrance has finished. */
   private float openProgress() {
      return Math.min(1.0F, (this.openTicks + this.frameDelta) / OPEN_TICKS);
   }

   /** Same, but delayed by {@code stagger} ticks - used to cascade the sidebar rows in. */
   private float openProgress(float stagger) {
      return Math.min(1.0F, Math.max(0.0F, (this.openTicks + this.frameDelta - stagger) / OPEN_TICKS));
   }

   /**
    * 6.1 - restarts the catalog row entrance.
    *
    * <p>Deliberately NOT called from rebuild(): rebuild() also runs on the once-a-second server
    * sync, and re-dealing the whole list every second would be a twitch, not an animation. Only a
    * real change of what is being listed - a page turn or a tab switch - replays it.
    */
   private void replayRows() {
      this.rowAnimTicks = 0;
   }

   /** Catalog row entrance, on its own clock so a page turn replays it without reopening. */
   private float rowProgress(float stagger) {
      return Math.min(1.0F, Math.max(0.0F, (this.rowAnimTicks + this.frameDelta - stagger) / OPEN_TICKS));
   }

   /** Classic ease-out-cubic: fast at first, gently settling. */
   private static float easeOut(float t) {
      float u = 1.0F - Math.min(1.0F, Math.max(0.0F, t));
      return 1.0F - u * u * u;
   }

   /** A 0..1 sine that completes one cycle every {@code periodTicks}. */
   private float pulse(float periodTicks) {
      return 0.5F + 0.5F * (float)Math.sin(this.clock() / periodTicks * 2.0 * Math.PI);
   }

   /** Linear blend between two ARGB colours, alpha included. */
   private static int mix(int from, int to, float t) {
      float k = Math.min(1.0F, Math.max(0.0F, t));
      int out = 0;
      for (int shift = 0; shift < 32; shift += 8) {
         int a = from >> shift & 0xFF;
         int b = to >> shift & 0xFF;
         out |= (int)(a + (b - a) * k) << shift;
      }
      return out;
   }

   /** Replaces a colour's alpha channel, for fading text and fills in and out. */
   private static int withAlpha(int color, float alpha) {
      int a = (int)(Math.min(1.0F, Math.max(0.0F, alpha)) * (color >>> 24));
      return color & 0x00FFFFFF | a << 24;
   }

   /* ---------------- text utils ---------------- */

   private ItemStack stackFor(String id) {
      if (id == null || id.isEmpty()) {
         return ItemStack.EMPTY;
      }
      try {
         Identifier key = Identifier.parse(id);
         if (!BuiltInRegistries.ITEM.containsKey(key)) {
            return ItemStack.EMPTY;
         }
         return new ItemStack((Item)BuiltInRegistries.ITEM.getValue(key));
      } catch (Exception e) {
         return ItemStack.EMPTY;
      }
   }

   /**
    * Shortens text to fit a pixel width, appending an ellipsis.
    *
    * <p>1.1 (2.7.0) - this used to append one character at a time and call font.width() on a freshly
    * concatenated string each time, which is O(n²) in both time and garbage for a method that runs
    * several times per frame. The font can measure a fitting substring in one pass, so it does.
    */
   private String trim(String text, int width) {
      if (this.font.width(text) <= width) {
         return text;
      }
      int ellipsis = this.font.width("…");
      return this.font.plainSubstrByWidth(text, Math.max(0, width - ellipsis)) + "…";
   }

   /**
    * 1.1 (2.7.0) - word wrap, with a one-entry cache.
    *
    * <p>The AI reasoning box is re-wrapped on every frame even though its text only changes when
    * the server answers, and wrapping allocates a list, a builder and a string per line plus the
    * array from split(). The panel only ever wraps one block of text at a time, so caching the last
    * result is enough to take this off the frame path entirely.
    */
   private List<String> wrap(String text, int width) {
      if (width == this.wrapWidth && text.equals(this.wrapKey)) {
         return this.wrapCache;
      }
      List<String> out = new ArrayList<>();
      StringBuilder line = new StringBuilder();
      for (String word : text.split(" ")) {
         String candidate = line.length() == 0 ? word : line + " " + word;
         if (this.font.width(candidate) > width && line.length() > 0) {
            out.add(line.toString());
            line = new StringBuilder(word);
         } else {
            line = new StringBuilder(candidate);
         }
      }
      if (line.length() > 0) {
         out.add(line.toString());
      }
      this.wrapKey = text;
      this.wrapWidth = width;
      this.wrapCache = out;
      return out;
   }

   private String prettyName(String id) {
      String path = id.contains(":") ? id.substring(id.indexOf(58) + 1) : id;
      StringBuilder sb = new StringBuilder();
      for (String part : path.split("_")) {
         if (!part.isEmpty()) {
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
         }
      }
      return sb.toString().trim();
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }

   /**
    * 1.1 (2.9.0) - loads everything this screen draws, in one go, on the way in.
    *
    * <h2>What this replaces</h2>
    *
    * 2.8.0 shipped a background warm-up that loaded one texture per client tick for the first 15
    * ticks in a world. It had to exist because Minecraft's texture manager loads a GUI texture
    * lazily, on first bind, on the render thread - so the frame that opened the shop was the frame
    * that read, decoded and uploaded 15 PNGs.
    *
    * <p>That cure was worse than the disease. It ran in the background for every player in every
    * world, held a queue and a cursor alive for the whole session, and it did all of that even for
    * players who never open the shop. Worse, it was a race: open the menu inside those 15 ticks and
    * the screen and the warm-up were loading the same textures at the same time.
    *
    * <h2>What happens now</h2>
    *
    * The screen loads its own assets, itself, in {@code init()} - before the first frame is drawn,
    * all of them, in one pass. There is no queue, no tick hook and no state kept between openings:
    * the textures live in the texture manager's own cache, which is where every other texture in
    * the game already lives.
    *
    * <p>The second and every later opening is therefore free. {@code getTexture} on a texture that
    * is already resident is a single hash lookup, so re-running this on every open costs nothing
    * measurable - and it is what makes the shop survive a resource-pack reload (F3+T), which throws
    * the textures away: the next opening simply loads them again instead of hitching mid-frame.
    *
    * <p>Failure is not fatal. If a texture is missing the texture manager substitutes the usual
    * missing-texture placeholder, exactly as it would have done on first bind.
    */
   private void loadGuiAssets() {
      if (this.minecraft == null) {
         return;
      }
      try {
         TextureManager textures = this.minecraft.getTextureManager();
         if (textures == null) {
            return;
         }
         textures.getTexture(RESTOCK_IMG);
         for (ShopScreen.Tab tab : TABS) {
            textures.getTexture(tab.icon);
         }
      } catch (Throwable error) {
         // the screen still works; it just pays the old lazy-load cost on the first frame
         com.merchantmind.MerchantMind.LOGGER
            .warn("[merchantmind] preloading the shop's GUI textures failed - falling back to lazy loading", error);
      }
   }

   private record Rect(int x, int y, int w, int h, String tab) {
      boolean has(double mx, double my) {
         return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
      }
   }

   private enum Tab {
      WEAPON("Weapon", "WEAPON", "weapon"),
      ARMOR("Armor", "ARMOR", "armor"),
      TOOLS("Tools", "TOOLS", "tools"),
      THROWABLES("Throwables", "THROWABLES", "throwables"),
      RESOURCES("Resources", "RESOURCES", "resources"),
      BLOCKS("Blocks", "BLOCKS", "blocks"),
      UTILITY("Utility", "UTILITY", "utility"),
      FOOD("Food", "FOOD", "food"),
      REDSTONE("Redstone", "REDSTONE", "redstone"),
      DECORATION("Decor", "DECORATION", "decoration"),
      RARE("Rare", "RARE", "rare"),
      TRADE("Trade", null, "trade"),
      SELL("Sell", null, "sell"),
      HELP("Help", null, "help");

      final String display;
      final String category;
      final Identifier icon;

      Tab(String display, String category, String iconName) {
         this.display = display;
         this.category = category;
         this.icon = Identifier.fromNamespaceAndPath("merchantmind", "textures/gui/icon/" + iconName + ".png");
      }
   }
}

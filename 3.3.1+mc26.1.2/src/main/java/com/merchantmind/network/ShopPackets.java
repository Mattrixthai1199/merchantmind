package com.merchantmind.network;

import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

public final class ShopPackets {
   private ShopPackets() {
   }

   private static Identifier id(String string) {
      return Identifier.fromNamespaceAndPath("merchantmind", string);
   }

   public record BuyC2SPayload(String itemId) implements CustomPacketPayload {
      public static final Type<ShopPackets.BuyC2SPayload> TYPE = new Type(ShopPackets.id("buy"));
      public static final StreamCodec<ByteBuf, ShopPackets.BuyC2SPayload> CODEC = StreamCodec.composite(
         ByteBufCodecs.STRING_UTF8, ShopPackets.BuyC2SPayload::itemId, ShopPackets.BuyC2SPayload::new
      );

      public Type<?> type() {
         return TYPE;
      }
   }

   public record OpenShopC2SPayload() implements CustomPacketPayload {
      public static final Type<ShopPackets.OpenShopC2SPayload> TYPE = new Type(ShopPackets.id("open_shop"));
      public static final StreamCodec<ByteBuf, ShopPackets.OpenShopC2SPayload> CODEC = StreamCodec.unit(new ShopPackets.OpenShopC2SPayload());

      public Type<?> type() {
         return TYPE;
      }
   }

   public record RequestSyncC2SPayload() implements CustomPacketPayload {
      public static final Type<ShopPackets.RequestSyncC2SPayload> TYPE = new Type(ShopPackets.id("request_sync"));
      public static final StreamCodec<ByteBuf, ShopPackets.RequestSyncC2SPayload> CODEC = StreamCodec.unit(new ShopPackets.RequestSyncC2SPayload());

      public Type<?> type() {
         return TYPE;
      }
   }

   /** Client asks the server to remove every dropped item entity in its dimension. */
   public record KillItemsC2SPayload() implements CustomPacketPayload {
      public static final Type<ShopPackets.KillItemsC2SPayload> TYPE = new Type(ShopPackets.id("kill_items"));
      public static final StreamCodec<ByteBuf, ShopPackets.KillItemsC2SPayload> CODEC = StreamCodec.unit(new ShopPackets.KillItemsC2SPayload());

      public Type<?> type() {
         return TYPE;
      }
   }

   /** Charge-and-Slam: true when the key goes down, false when it is released. */
   public record SlamChargeC2SPayload(boolean charging) implements CustomPacketPayload {
      public static final Type<ShopPackets.SlamChargeC2SPayload> TYPE = new Type(ShopPackets.id("slam_charge"));
      public static final StreamCodec<ByteBuf, ShopPackets.SlamChargeC2SPayload> CODEC = StreamCodec.composite(
         ByteBufCodecs.BOOL, ShopPackets.SlamChargeC2SPayload::charging, ShopPackets.SlamChargeC2SPayload::new
      );

      public Type<?> type() {
         return TYPE;
      }
   }

   /**
    * 3.0.0 - "your slam charge was just spent".
    *
    * <p>Sent the moment the server consumes a charge into a hit. The server restarts the hold from
    * zero at that instant ({@code slamChargeStart.put(id, now)}), and before this packet existed the
    * client had no way to know: it kept its own counter running, so the charge HUD went on showing
    * a nearly-full bar while the real charge was back at nothing. The bar is a promise about damage,
    * so it has to be told when the damage has already been paid out.
    *
    * <p>Carries nothing - the event is the whole message - and is only ever sent to a client that
    * registered it, so an older client simply never receives one.
    */
   public record SlamConsumedS2CPayload() implements CustomPacketPayload {
      public static final Type<ShopPackets.SlamConsumedS2CPayload> TYPE = new Type(ShopPackets.id("slam_consumed"));
      public static final StreamCodec<ByteBuf, ShopPackets.SlamConsumedS2CPayload> CODEC = StreamCodec.unit(new ShopPackets.SlamConsumedS2CPayload());

      public Type<?> type() {
         return TYPE;
      }
   }

   /**
    * Restock request for one category.
    *
    * <p>2.2 (2.7.0) - {@code useIron} still selects between the packet's two jobs, but both of them
    * changed meaning:
    *
    * <ul>
    *   <li>{@code false} - the "⟳ Restock" button. <b>Starts the refill immediately</b> on the
    *       normal timer. Costs nothing and asks for nothing.</li>
    *   <li>{@code true} - the Submit button, which only exists <b>while a refill is already
    *       running</b>. Spends 4 iron to finish that refill instantly.</li>
    * </ul>
    */
   public record RestockC2SPayload(String category, boolean useIron) implements CustomPacketPayload {
      public static final Type<ShopPackets.RestockC2SPayload> TYPE = new Type(ShopPackets.id("restock"));
      public static final StreamCodec<ByteBuf, ShopPackets.RestockC2SPayload> CODEC = StreamCodec.composite(
         ByteBufCodecs.STRING_UTF8,
         ShopPackets.RestockC2SPayload::category,
         ByteBufCodecs.BOOL,
         ShopPackets.RestockC2SPayload::useIron,
         ShopPackets.RestockC2SPayload::new
      );

      public Type<?> type() {
         return TYPE;
      }
   }

   public record SellC2SPayload(boolean commit) implements CustomPacketPayload {
      public static final Type<ShopPackets.SellC2SPayload> TYPE = new Type(ShopPackets.id("sell"));
      public static final StreamCodec<ByteBuf, ShopPackets.SellC2SPayload> CODEC = StreamCodec.composite(
         ByteBufCodecs.BOOL, ShopPackets.SellC2SPayload::commit, ShopPackets.SellC2SPayload::new
      );

      public Type<?> type() {
         return TYPE;
      }
   }

   /**
    * One shop row, or - when the id starts with {@code merchantmind:__cat_} - the status of a
    * whole category.
    *
    * <p>4.1 - a category status row is the whole of the shared, server-owned shop state for one
    * category, and it is sent to every player who has the shop open. Nothing about stock or
    * restocking is tracked anywhere else, so two players looking at the same category cannot end up
    * showing different things.
    *
    * <p>For a category status row:
    *
    * <ul>
    *   <li>{@code stock} - how many offers are left;</li>
    *   <li>{@code restocking} - a refill is running right now. This is the only restock state
    *       there is as of 2.7.0; the old "armed, waiting for iron" state is gone;</li>
    *   <li>{@code restockSecondsRemaining} - seconds until that refill finishes by itself;</li>
    *   <li>{@code manualRestock} - a player started it, rather than the shop's own schedule or a
    *       sold-out shelf, so the UI can say which. Since 3.3.0 this is <b>display only</b>: it no
    *       longer identifies which clock the refill is on, because MANUAL and SOLD_OUT share one.
    *       (Occupies the field 2.6.0 called {@code restockArmed}.)</li>
    *   <li>{@code ironSkipOpen} - the optional iron shortcut is available, which is also exactly
    *       when the iron slot and its Submit button exist. (Field 2.6.0 called
    *       {@code restockDue}.)</li>
    *   <li>{@code secondsUntilAuto} - while idle, seconds until the shop restocks itself;</li>
    *   <li>{@code reason} - since 3.3.0 the {@link com.merchantmind.shop.ShopManager.RestockReason}
    *       KEY ({@code name()}), empty on an item row. It used to be the display sentence. It is the
    *       trigger, so it is also the refill's duration, which is what lets the client scale its
    *       progress bar correctly for all three triggers - the client turns it back into the same
    *       display text with {@code RestockReason.fromKey().display}. Same field, same type, same
    *       row shape: nothing about the packet changed, only what the string says.</li>
    * </ul>
    */
   public record ShopEntryData(
      String itemId,
      String category,
      int buyPrice,
      int sellPrice,
      int stock,
      int secondsUntilAuto,
      int restockSecondsRemaining,
      boolean restocking,
      boolean manualRestock,
      boolean ironSkipOpen,
      String reason
   ) {
      public static final StreamCodec<ByteBuf, ShopPackets.ShopEntryData> CODEC = StreamCodec.composite(
         ByteBufCodecs.STRING_UTF8,
         ShopPackets.ShopEntryData::itemId,
         ByteBufCodecs.STRING_UTF8,
         ShopPackets.ShopEntryData::category,
         ByteBufCodecs.VAR_INT,
         ShopPackets.ShopEntryData::buyPrice,
         ByteBufCodecs.VAR_INT,
         ShopPackets.ShopEntryData::sellPrice,
         ByteBufCodecs.VAR_INT,
         ShopPackets.ShopEntryData::stock,
         ByteBufCodecs.VAR_INT,
         ShopPackets.ShopEntryData::secondsUntilAuto,
         ByteBufCodecs.VAR_INT,
         ShopPackets.ShopEntryData::restockSecondsRemaining,
         ByteBufCodecs.BOOL,
         ShopPackets.ShopEntryData::restocking,
         ByteBufCodecs.BOOL,
         ShopPackets.ShopEntryData::manualRestock,
         ByteBufCodecs.BOOL,
         ShopPackets.ShopEntryData::ironSkipOpen,
         ByteBufCodecs.STRING_UTF8,
         ShopPackets.ShopEntryData::reason,
         ShopPackets.ShopEntryData::new
      );
   }

   public record SyncS2CPayload(
      List<ShopPackets.ShopEntryData> entries,
      long balance,
      String message,
      String tradeAi,
      String sellAi,
      boolean tradeApproved,
      String tradeResultId,
      int tradeResultQty,
      long sellQuote,
      boolean updateTradePanel,
      boolean updateSellPanel
   ) implements CustomPacketPayload {
      public static final Type<ShopPackets.SyncS2CPayload> TYPE = new Type(ShopPackets.id("sync"));
      public static final StreamCodec<ByteBuf, ShopPackets.SyncS2CPayload> CODEC = StreamCodec.composite(
         ShopPackets.ShopEntryData.CODEC.apply(ByteBufCodecs.list()),
         ShopPackets.SyncS2CPayload::entries,
         ByteBufCodecs.VAR_LONG,
         ShopPackets.SyncS2CPayload::balance,
         ByteBufCodecs.STRING_UTF8,
         ShopPackets.SyncS2CPayload::message,
         ByteBufCodecs.STRING_UTF8,
         ShopPackets.SyncS2CPayload::tradeAi,
         ByteBufCodecs.STRING_UTF8,
         ShopPackets.SyncS2CPayload::sellAi,
         ByteBufCodecs.BOOL,
         ShopPackets.SyncS2CPayload::tradeApproved,
         ByteBufCodecs.STRING_UTF8,
         ShopPackets.SyncS2CPayload::tradeResultId,
         ByteBufCodecs.VAR_INT,
         ShopPackets.SyncS2CPayload::tradeResultQty,
         ByteBufCodecs.VAR_LONG,
         ShopPackets.SyncS2CPayload::sellQuote,
         ByteBufCodecs.BOOL,
         ShopPackets.SyncS2CPayload::updateTradePanel,
         ByteBufCodecs.BOOL,
         ShopPackets.SyncS2CPayload::updateSellPanel,
         ShopPackets.SyncS2CPayload::new
      );

      public Type<?> type() {
         return TYPE;
      }
   }

   public record TradeC2SPayload(String targetItemId, boolean commit) implements CustomPacketPayload {
      public static final Type<ShopPackets.TradeC2SPayload> TYPE = new Type(ShopPackets.id("trade"));
      public static final StreamCodec<ByteBuf, ShopPackets.TradeC2SPayload> CODEC = StreamCodec.composite(
         ByteBufCodecs.STRING_UTF8,
         ShopPackets.TradeC2SPayload::targetItemId,
         ByteBufCodecs.BOOL,
         ShopPackets.TradeC2SPayload::commit,
         ShopPackets.TradeC2SPayload::new
      );

      public Type<?> type() {
         return TYPE;
      }
   }
}

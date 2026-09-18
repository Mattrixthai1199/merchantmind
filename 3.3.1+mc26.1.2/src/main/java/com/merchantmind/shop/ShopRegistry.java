package com.merchantmind.shop;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.MenuType;

public final class ShopRegistry {
   public static final Identifier MENU_ID = Identifier.fromNamespaceAndPath("merchantmind", "shop");
   public static MenuType<ShopMenu> MENU_TYPE;

   private ShopRegistry() {
   }

   public static void init() {
      ExtendedMenuType extendedMenuType = new ExtendedMenuType((n, inventory, bl) -> new ShopMenu(n, inventory), StreamCodec.unit(Boolean.TRUE));
      MENU_TYPE = (MenuType<ShopMenu>)Registry.register(BuiltInRegistries.MENU, MENU_ID, extendedMenuType);
   }
}

package com.merchantmind.ai;

import java.util.Locale;

public final class ItemFeatures {
   private ItemFeatures() {
   }

   public static int tier(String string) {
      String string2 = string.toLowerCase(Locale.ROOT);
      if (string2.contains("netherite")
         || string2.contains("nether_star")
         || string2.contains("dragon")
         || string2.contains("elytra")
         || string2.contains("beacon")
         || string2.contains("totem")
         || string2.contains("enchanted_golden_apple")
         || string2.contains("conduit")
         || string2.contains("end_crystal")
         || string2.contains("heart_of_the_sea")) {
         return 5;
      } else if (string2.contains("diamond")
         || string2.contains("emerald")
         || string2.contains("trident")
         || string2.contains("mace")
         || string2.contains("shulker")
         || string2.contains("enchanted_book")) {
         return 4;
      } else if (!string2.contains("iron")
         && !string2.contains("gold")
         && !string2.contains("golden")
         && !string2.contains("amethyst")
         && !string2.contains("obsidian")
         && !string2.contains("ender")) {
         return !string2.contains("stone") && !string2.contains("copper") && !string2.contains("cooked") && !string2.contains("redstone") ? 1 : 2;
      } else {
         return 3;
      }
   }

   public static String archetype(String string) {
      String string2 = string.toLowerCase(Locale.ROOT);
      if (string2.contains("sword")
         || string2.contains("bow")
         || string2.contains("crossbow")
         || string2.contains("trident")
         || string2.contains("mace")
         || string2.contains("arrow")
         || string2.contains("tnt")) {
         return "weapon";
      } else if (string2.contains("helmet")
         || string2.contains("chestplate")
         || string2.contains("leggings")
         || string2.contains("boots")
         || string2.contains("shield")) {
         return "armor";
      } else if (string2.contains("pickaxe")
         || string2.contains("axe")
         || string2.contains("shovel")
         || string2.contains("hoe")
         || string2.contains("shears")
         || string2.contains("fishing")
         || string2.contains("flint")) {
         return "tool";
      } else if (string2.contains("apple")
         || string2.contains("bread")
         || string2.contains("cooked")
         || string2.contains("carrot")
         || string2.contains("cake")
         || string2.contains("pie")
         || string2.contains("berries")
         || string2.contains("melon")
         || string2.contains("cookie")
         || string2.contains("honey")
         || string2.contains("milk")) {
         return "food";
      } else if (!string2.contains("block")
         && !string2.contains("planks")
         && !string2.contains("stone")
         && !string2.contains("bricks")
         && !string2.contains("glass")
         && !string2.contains("log")) {
         return tier(string) >= 5 ? "rare" : "utility";
      } else {
         return "block";
      }
   }

   /**
    * 3.0.1 - is this item a weapon in the "you get exactly one" sense?
    *
    * <p>Deliberately narrower than {@link #archetype}, which files arrows, TNT and fire charges
    * under {@code "weapon"} and every axe under {@code "tool"}. Those two groups need opposite
    * answers here: an axe is a weapon you can only hold one of, ammunition is a consumable that is
    * meaningless in ones. So this matches the item <em>name</em> of the gear itself and nothing
    * else - no {@code contains("axe")}, which would also catch nothing useful but would catch a
    * modded {@code axe_head}, and no {@code contains("flint")}, which {@code archetype} treats as a
    * tool even though flint is a stackable raw material.
    *
    * <p>In vanilla every id this matches already has a max stack size of 1, so on its own this
    * method changes no behaviour. It exists because the caller pairs it with the stack-size test
    * ({@code maxStack <= 1 || isWeapon(id)}): the stack size is the authoritative rule and the name
    * is the readable one, and a modded weapon that wrongly declares itself stackable is still
    * handed over one at a time.
    */
   public static boolean isWeapon(String string) {
      String id = string.toLowerCase(Locale.ROOT);
      int colon = id.indexOf(58);
      String path = colon >= 0 ? id.substring(colon + 1) : id;
      return path.endsWith("_sword")
         || path.endsWith("_axe")
         || path.equals("bow")
         || path.endsWith("crossbow")
         || path.endsWith("trident")
         || path.endsWith("mace");
   }

   public static String pretty(String string) {
      String string2 = string.contains(":") ? string.substring(string.indexOf(58) + 1) : string;
      String[] stringArray = string2.split("_");
      StringBuilder stringBuilder = new StringBuilder();

      for (String string3 : stringArray) {
         if (!string3.isEmpty()) {
            stringBuilder.append(Character.toUpperCase(string3.charAt(0))).append(string3.substring(1)).append(' ');
         }
      }

      return stringBuilder.toString().trim();
   }
}

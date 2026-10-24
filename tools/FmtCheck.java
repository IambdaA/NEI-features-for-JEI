import java.util.*;
import neiaddon.HotkeyTips;
public class FmtCheck {
  public static void main(String[] a) {
    Map<String,String> k = new HashMap<>();
    k.put("A","Add Bookmark"); k.put("R","Recipe to make this item"); k.put("LMB","Recipe to make this item");
    k.put("U","Recipes that use this item"); k.put("RMB","Recipes that use this item");
    k.put("CTRL + C","Copy Item Name"); k.put("SHIFT + A","Add Bookmark with Recipe"); k.put("CTRL + SHIFT + A","Add Bookmark with Recipe & Count");
    List<String> out = HotkeyTips.format(k);
    out.forEach(System.out::println);
    String[] want = {"§6A§8 - §7Add Bookmark","§6R§8 / §6LMB§8 - §7Recipe to make this item","§6U§8 / §6RMB§8 - §7Recipes that use this item","§6CTRL + C§8 - §7Copy Item Name","§6SHIFT + A§8 - §7Add Bookmark with Recipe","§6CTRL + SHIFT + A§8 - §7Add Bookmark with Recipe & Count"};
    if (!out.equals(Arrays.asList(want))) { System.out.println("FAIL"); System.exit(1); }
    System.out.println("ALL OK");
  }
}

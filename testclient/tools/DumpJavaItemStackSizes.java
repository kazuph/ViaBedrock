import com.google.gson.GsonBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.core.component.DataComponents;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;

/** Run with the official Java 26.3 jar and its installed libraries on the classpath. */
class DumpJavaItemStackSizes {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (var components : BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())) components.apply();
        var sizes = new TreeMap<String, Integer>();
        var damageDefaults = new TreeMap<String, Integer>();
        var equipmentSlots = new TreeMap<String, String>();
        for (var name : BuiltInRegistries.ITEM.keySet()) {
            var item = BuiltInRegistries.ITEM.getValue(name);
            sizes.put(name.toString(), item.getDefaultMaxStackSize());
            Integer damage = item.components().get(DataComponents.DAMAGE);
            if (damage != null) damageDefaults.put(name.toString(), damage);
            var equipment = item.components().get(DataComponents.EQUIPPABLE);
            if (equipment != null) equipmentSlots.put(name.toString(), equipment.slot().getSerializedName());
        }
        Files.writeString(Path.of(args[0]), new GsonBuilder().setPrettyPrinting().create().toJson(sizes) + "\n");
        Files.writeString(Path.of(args[0]).resolveSibling("item_default_damage.json"), new GsonBuilder().setPrettyPrinting().create().toJson(damageDefaults) + "\n");
        Files.writeString(Path.of(args[0]).resolveSibling("item_equipment_slots.json"), new GsonBuilder().setPrettyPrinting().create().toJson(equipmentSlots) + "\n");
        Bootstrap.realStdoutPrintln("Exported " + sizes.size() + " official item stack limits");
    }
}

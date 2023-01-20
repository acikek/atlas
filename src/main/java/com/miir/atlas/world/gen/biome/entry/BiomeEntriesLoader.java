package com.miir.atlas.world.gen.biome.entry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miir.atlas.Atlas;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class BiomeEntriesLoader {

    public static Map<Identifier, List<BiomeEntry>> loadedBiomeEntries = new HashMap<>();

    public static List<BiomeEntry> getBiomeEntries(Either<Identifier, List<BiomeEntry>> result) {
        if (result.right().isPresent()) {
            return result.right().get();
        }
        Identifier id = result.left().get();
        if (loadedBiomeEntries.containsKey(id)) {
            return loadedBiomeEntries.get(id);
        }
        // bad
        Optional<Resource> file = Atlas.SERVER.getResourceManager().getResource(id.withPath(s -> s + ".json"));
        if (file.isEmpty()) {
            throw new IllegalStateException("'" + id + "' is not a valid biome entry list");
        }
        Resource resource = file.get();
        try {
            JsonObject obj = JsonHelper.deserialize(resource.getReader());
            JsonArray array = JsonHelper.getArray(obj, "biomes");
            var parsed = BiomeEntry.LIST_CODEC.parse(JsonOps.INSTANCE, array);
            var optional = parsed.resultOrPartial(s -> {
                throw new IllegalStateException("error when parsing biome entry list '" + id + "': " + s);
            });
            if (optional.isPresent()) {
                return optional.get();
            }
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return null;
    }

    public static void register() {
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resourceManager) -> loadedBiomeEntries.clear());
    }
}

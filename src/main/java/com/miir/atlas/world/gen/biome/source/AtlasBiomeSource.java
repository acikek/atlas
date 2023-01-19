package com.miir.atlas.world.gen.biome.source;

import com.miir.atlas.Atlas;
import com.miir.atlas.world.gen.NamespacedMapImage;
import com.miir.atlas.world.gen.biome.BiomeEntry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.dynamic.Codecs;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class AtlasBiomeSource extends BiomeSource {
    private final NamespacedMapImage image;
    private final List<BiomeEntry> biomeEntries;
    private final RegistryEntry<Biome> defaultBiome;
    private final Int2ObjectArrayMap<RegistryEntry<Biome>> biomes = new Int2ObjectArrayMap<>();
    private final float horizontalScale;

    protected AtlasBiomeSource(String path, List<BiomeEntry> biomes, Optional<RegistryEntry<Biome>> defaultBiome, float horizontalScale) {
        super(biomes.stream().map(BiomeEntry::getTopBiome).toList());
        this.image = new NamespacedMapImage(path, NamespacedMapImage.Type.COLOR);
        this.biomeEntries = biomes;
        this.defaultBiome = defaultBiome.orElse(this.biomeEntries.get(0).getTopBiome());
        this.horizontalScale = horizontalScale;
        for (BiomeEntry entry : this.biomeEntries) {
            this.biomes.put(entry.getColor(), entry.getTopBiome());
        }
    }

    public static final Codec<AtlasBiomeSource> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("biome_map").forGetter(AtlasBiomeSource::getPath),
            Codecs.nonEmptyList(BiomeEntry.CODEC.listOf()).fieldOf("biomes").forGetter(AtlasBiomeSource::getBiomeEntries),
            Biome.REGISTRY_CODEC.optionalFieldOf("default").forGetter(AtlasBiomeSource::getDefaultBiome),
            Codec.FLOAT.fieldOf("horizontal_scale").forGetter(AtlasBiomeSource::getHorizontalScale)
    ).apply(instance, AtlasBiomeSource::new));

    public List<BiomeEntry> getBiomeEntries() {
        return this.biomeEntries;
    }
    public Optional<RegistryEntry<Biome>> getDefaultBiome(){return Optional.of(this.defaultBiome);}
    public String getPath() {
        return this.image.getPath();
    }
    public float getHorizontalScale() {return this.horizontalScale;}

    @Override
    protected Codec<AtlasBiomeSource> getCodec() {
        return CODEC;
    }

    public void findBiomeMap(MinecraftServer server, String levelName) throws IOException {
        this.image.initialize(server);
        Atlas.LOGGER.info("found biomes for dimension " + levelName + " in a " + this.image.getWidth() + "x" + this.image.getHeight() + " map: " + getPath());
    }

    @Override
    public RegistryEntry<Biome> getBiome(int x, int y, int z, MultiNoiseUtil.MultiNoiseSampler noise) {
        x *=4;
        z *=4;
        x = Math.round(x/horizontalScale);
        z = Math.round(z/horizontalScale);
        x += this.image.getWidth() / 2;
        z += this.image.getHeight() / 2;
        if (x < 0 || z < 0 || x >= this.image.getWidth() || z >= this.image.getHeight()) return this.defaultBiome;
//        this.image.loadPixelsInRange(x, z, false, Atlas.GEN_RADIUS);
        return this.biomes.getOrDefault(this.image.getPixels()[z][x], this.defaultBiome);
    }
}

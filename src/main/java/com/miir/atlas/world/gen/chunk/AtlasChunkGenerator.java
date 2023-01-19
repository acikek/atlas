package com.miir.atlas.world.gen.chunk;

import com.google.common.annotations.VisibleForTesting;
import com.miir.atlas.Atlas;
import com.miir.atlas.world.gen.NamespacedMapImage;
import com.miir.atlas.world.gen.cave.CaveLayerEntry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.util.dynamic.Codecs;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.RandomSeed;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.GenerationSettings;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.BiomeCoords;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.HeightContext;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.StructureWeightSampler;
import net.minecraft.world.gen.carver.CarverContext;
import net.minecraft.world.gen.carver.CarvingMask;
import net.minecraft.world.gen.carver.ConfiguredCarver;
import net.minecraft.world.gen.chunk.*;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

public class AtlasChunkGenerator extends ChunkGenerator {
    private final NamespacedMapImage heightmap;
    private final NamespacedMapImage aquifer;
    private final NamespacedMapImage roof;
    private final int seaLevel;
    private final int minimumY;
    private final int ceilingHeight;
    private final RegistryEntry<ChunkGeneratorSettings> settings;
    private final float verticalScale;
    private final float horizontalScale;
    private final ArrayList<CaveLayerEntry> caveLayers = new ArrayList<>();


    public AtlasChunkGenerator(
            String heightmapPath, String aquiferPath, String roofPath,
            BiomeSource biomeSource, RegistryEntry<ChunkGeneratorSettings> settings,
            int startingY, int ceilingHeight,
            float verticalScale, float horizontalScale,
            List<CaveLayerEntry> caveLayers
    ) {
        super(biomeSource);
        this.seaLevel = settings.value().seaLevel();
        this.minimumY = startingY;
        this.ceilingHeight = ceilingHeight;
        this.verticalScale = verticalScale;
        if (this.verticalScale != 1) Atlas.LOGGER.warn("using non-default vertical scale for a dimension! this feature is in alpha, expect weird generation!");
        this.horizontalScale = horizontalScale;
        this.heightmap = new NamespacedMapImage(heightmapPath, NamespacedMapImage.Type.GRAYSCALE);
        this.caveLayers.addAll(caveLayers);
        this.aquifer = !aquiferPath.equals("") ? new NamespacedMapImage(aquiferPath, NamespacedMapImage.Type.GRAYSCALE) :null;
        this.roof = !roofPath.equals("") ? new NamespacedMapImage(roofPath, NamespacedMapImage.Type.GRAYSCALE) : null;
        this.settings = settings;
    }

    public void findMaps(MinecraftServer server, String levelName) throws IOException {
        this.heightmap.initialize(server);
        Atlas.LOGGER.info("found elevation data for dimension " + levelName + " in a " + this.heightmap.getWidth() + "x" + this.heightmap.getHeight() + " map: " + getPath());
        if (!this.getAquiferPath().equals("")) {
            this.aquifer.initialize(server);
            Atlas.LOGGER.info("found aquifer data for dimension " + levelName + " in a " + this.aquifer.getWidth() + "x" + this.aquifer.getHeight() + " map: " + getAquiferPath());
        } else {
            Atlas.LOGGER.warn("couldn't find aquifer for dimension " + levelName + ", defaulting to sea level!");
        }
        if (!Objects.equals(this.getRoofPath(), "")) {
            this.roof.initialize(server);
            Atlas.LOGGER.info("found roof data for dimension " + levelName + " in a " + this.roof.getWidth() + "x" + this.roof.getHeight() + " map: " + getRoofPath());
        }
        if (this.caveLayers.size() > 0) {
            for (CaveLayerEntry layer :
                    this.caveLayers) {
                layer.getCeiling().initialize(server);
                layer.getFloor().initialize(server);
                if (layer.getBiomes() != null) {
                    layer.getBiomes().initialize(server);
                }
                if (layer.getAquifer() != null) {
                    layer.getAquifer().initialize(server);
                }
            }
        }
    }

    public static final Codec<AtlasChunkGenerator> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("height_map").forGetter(AtlasChunkGenerator::getPath),
            Codec.STRING.optionalFieldOf("aquifer", "").forGetter(AtlasChunkGenerator::getAquiferPath),
            Codec.STRING.optionalFieldOf("roof", "").forGetter(AtlasChunkGenerator::getRoofPath),
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(AtlasChunkGenerator::getBiomeSource),
            ChunkGeneratorSettings.REGISTRY_CODEC.fieldOf("settings").forGetter(AtlasChunkGenerator::getSettings),
            Codec.INT.fieldOf("starting_y").forGetter(AtlasChunkGenerator::getMinimumY),
            Codec.INT.optionalFieldOf("ceiling_height", Integer.MIN_VALUE).forGetter(AtlasChunkGenerator::getCeilingHeight),
            Codec.FLOAT.optionalFieldOf("vertical_scale", 1f).forGetter(AtlasChunkGenerator::getVerticalScale),
            Codec.FLOAT.optionalFieldOf("horizontal_scale", 1f).forGetter(AtlasChunkGenerator::getHorizontalScale),
            Codecs.nonEmptyList(CaveLayerEntry.CODEC.listOf()).optionalFieldOf("caves", new ArrayList<>()).forGetter(AtlasChunkGenerator::getCaveLayers)
    ).apply(instance, AtlasChunkGenerator::new));

    private List<CaveLayerEntry> getCaveLayers() {
        return this.caveLayers;
    }

    private int getCeilingHeight() {return this.ceilingHeight;}

    private String getRoofPath() {return this.roof == null ? "" : (this.roof.getPath());}
    private String getAquiferPath() {return this.aquifer == null ? "" : (this.aquifer.getPath());}

    private double getFromMap(int x, int z, NamespacedMapImage nmi) {
        float xR = (x/horizontalScale);
        float zR = (z/horizontalScale);
        xR += nmi.getWidth()  / 2f; // these will always be even numbers
        zR += nmi.getHeight() / 2f;
        if (xR < 0 || zR < 0 || xR >= nmi.getWidth() || zR >= nmi.getHeight()) return -1;
        int truncatedX = (int)Math.floor(xR);
        int truncatedZ = (int)Math.floor(zR);
        double d = nmi.lerp(truncatedX, xR-truncatedX, truncatedZ, zR-truncatedZ);
        return this.verticalScale*d+minimumY;
    }
    public float getVerticalScale() {return this.verticalScale;}
    public float getHorizontalScale() {return this.horizontalScale;}
    public RegistryEntry<ChunkGeneratorSettings> getSettings() {return this.settings;}

    private String getPath() {return this.heightmap.getPath();}
    @Override
    protected Codec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<Chunk> populateBiomes(Executor executor, NoiseConfig noiseConfig, Blender blender, StructureAccessor structureAccessor, Chunk chunk) {
        return CompletableFuture.supplyAsync(Util.debugSupplier("init_biomes", () -> {
            chunk.populateBiomes(this.biomeSource, noiseConfig.getMultiNoiseSampler());
            return chunk;
        }), Util.getMainWorkerExecutor());
    }

    @Override
    @SuppressWarnings("deprecation")
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess, StructureAccessor structureAccessor, Chunk chunk2, GenerationStep.Carver carverStep) {
        BiomeAccess biomeAccess2 = biomeAccess.withSource((biomeX, biomeY, biomeZ) -> this.biomeSource.getBiome(biomeX, biomeY, biomeZ, noiseConfig.getMultiNoiseSampler()));
        ChunkRandom chunkRandom = new ChunkRandom(new CheckedRandom(RandomSeed.getSeed()));
        int i = 8;
        ChunkPos chunkPos = chunk2.getPos();
        ChunkNoiseSampler chunkNoiseSampler = chunk2.getOrCreateChunkNoiseSampler(chunk -> this.createChunkNoiseSampler(chunk, structureAccessor, Blender.getBlender(chunkRegion), noiseConfig));
        AquiferSampler aquiferSampler = chunkNoiseSampler.getAquiferSampler();
        CarverContext carverContext = new CarverContext(new NoiseChunkGenerator(this.biomeSource, this.settings)/*lmao*/, chunkRegion.getRegistryManager(), chunk2.getHeightLimitView(), chunkNoiseSampler, noiseConfig, this.settings.value().surfaceRule());
        CarvingMask carvingMask = ((ProtoChunk)chunk2).getOrCreateCarvingMask(carverStep);
        for (int j = -i; j <= i; ++j) {
            for (int k = -i; k <= i; ++k) {
                ChunkPos chunkPos2 = new ChunkPos(chunkPos.x + j, chunkPos.z + k);
                Chunk chunk22 = chunkRegion.getChunk(chunkPos2.x, chunkPos2.z);
                GenerationSettings generationSettings = chunk22.getOrCreateGenerationSettings(() -> this.getGenerationSettings(this.biomeSource.getBiome(BiomeCoords.fromBlock(chunkPos2.getStartX()), 0, BiomeCoords.fromBlock(chunkPos2.getStartZ()), noiseConfig.getMultiNoiseSampler())));
                Iterable<RegistryEntry<ConfiguredCarver<?>>> iterable = generationSettings.getCarversForStep(carverStep);
                int l = 0;
                for (RegistryEntry<ConfiguredCarver<?>> registryEntry : iterable) {
                    ConfiguredCarver<?> configuredCarver = registryEntry.value();
                    chunkRandom.setCarverSeed(seed + (long)l, chunkPos2.x, chunkPos2.z);
                    if (configuredCarver.shouldCarve(chunkRandom)) {
                        configuredCarver.carve(carverContext, chunk2, biomeAccess2::getBiome, chunkRandom, aquiferSampler, chunkPos2, carvingMask);
                    }
                    ++l;
                }
            }
        }
    }

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk) {
        if (SharedConstants.isOutsideGenerationArea(chunk.getPos())) {
            return;
        }
        HeightContext heightContext = new HeightContext(this, region);
        this.buildSurface(chunk, heightContext, noiseConfig, structures, region.getBiomeAccess(), region.getRegistryManager().get(RegistryKeys.BIOME), Blender.getBlender(region));
    }

    @VisibleForTesting
    public void buildSurface(Chunk chunk, HeightContext heightContext, NoiseConfig noiseConfig, StructureAccessor structureAccessor, BiomeAccess biomeAccess, Registry<Biome> biomeRegistry, Blender blender) {
        ChunkNoiseSampler chunkNoiseSampler = chunk.getOrCreateChunkNoiseSampler(chunk3 -> this.createChunkNoiseSampler(chunk3, structureAccessor, blender, noiseConfig));
        ChunkGeneratorSettings chunkGeneratorSettings = this.settings.value();
        noiseConfig.getSurfaceBuilder().buildSurface(noiseConfig, biomeAccess, biomeRegistry, chunkGeneratorSettings.usesLegacyRandom(), heightContext, chunk, chunkNoiseSampler, chunkGeneratorSettings.surfaceRule());
    }

    @Override
    public void populateEntities(ChunkRegion region) {
        ChunkPos chunkPos = region.getCenterPos();
        RegistryEntry<Biome> registryEntry = region.getBiome(chunkPos.getStartPos().withY(region.getTopY() - 1));
        ChunkRandom chunkRandom = new ChunkRandom(new CheckedRandom(RandomSeed.getSeed()));
        chunkRandom.setPopulationSeed(region.getSeed(), chunkPos.getStartX(), chunkPos.getStartZ());
        SpawnHelper.populateEntities(region, registryEntry, chunkPos, chunkRandom);
    }

    @Override
    public int getWorldHeight() {
        return this.settings.value().generationShapeConfig().height();
    }

    @Override
    public CompletableFuture<Chunk> populateNoise(Executor executor, Blender blender, NoiseConfig noiseConfig, StructureAccessor structureAccessor, Chunk chunk) {
        GenerationShapeConfig generationShapeConfig = this.settings.value().generationShapeConfig().trimHeight(chunk.getHeightLimitView());
        int k = MathHelper.floorDiv(generationShapeConfig.height(), generationShapeConfig.verticalBlockSize());
        if (k <= 0) {
            return CompletableFuture.completedFuture(chunk);
        }
        int x = chunk.getPos().x << 4;
        int z = chunk.getPos().z << 4;
        float xR = (x/horizontalScale);
        float zR = (z/horizontalScale);
        xR += this.heightmap.getWidth()  / 2f; // these will always be even numbers
        zR += this.heightmap.getHeight() / 2f;
        int truncatedX = (int)Math.floor(xR);
        int truncatedZ = (int)Math.floor(zR);
        if (truncatedX < 0 || truncatedZ < 0 || truncatedX >= this.heightmap.getWidth() || truncatedZ >= this.heightmap.getHeight()) return CompletableFuture.completedFuture(chunk);
        this.heightmap.loadPixelsInRange(truncatedX, truncatedZ, true, Atlas.GEN_RADIUS);
        if (this.aquifer != null) this.aquifer.loadPixelsInRange(truncatedX, truncatedZ, true, Atlas.GEN_RADIUS);
        return CompletableFuture.supplyAsync(Util.debugSupplier("wgen_fill_noise", () -> this.populateNoise(chunk)), Util.getMainWorkerExecutor());
    }
    private Chunk populateNoise(Chunk chunk) {
        int minY = settings.value().generationShapeConfig().minimumY();
        Heightmap oceanHeightmap = chunk.getHeightmap(Heightmap.Type.OCEAN_FLOOR_WG);
        Heightmap surfaceHeightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE_WG);
        BlockState defaultBlock = this.settings.value().defaultBlock();
        BlockState defaultFluid = this.settings.value().defaultFluid();
        int offsetX = chunk.getPos().x << 4;
        int offsetZ = chunk.getPos().z << 4;
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int x = 0; x < 16; x++) {
            mutable.setX(x);
            for (int z = 0; z < 16; z++) {
                mutable.setZ(z);
                int seaLevel = this.getSeaLevel(x, z);
                int elevation = (int) Math.min(this.getFromMap(x+offsetX, z+offsetZ, this.heightmap), this.minimumY+this.getWorldHeight());
                if (elevation != -1 && elevation >= minY) {
                    for (int y = minY; y < elevation; y++) {
                        mutable.setY(y);
                        chunk.setBlockState(mutable, defaultBlock, false);
                    }
                    if (defaultBlock.getLuminance() != 0 && chunk instanceof ProtoChunk) {
                        ((ProtoChunk)chunk).addLightSource(mutable);
                    }
                    if (elevation < seaLevel) {
                        for (int y = elevation; y < seaLevel; y++) {
                            mutable.setY(y);
                            chunk.setBlockState(mutable, defaultFluid, false);
                            chunk.markBlockForPostProcessing(mutable);
                        }
                        surfaceHeightmap.trackUpdate(x, elevation, z, defaultFluid);
                    } else {
                        surfaceHeightmap.trackUpdate(x, elevation, z, defaultBlock);
                    }
                    oceanHeightmap.trackUpdate(x, elevation, z, defaultBlock);

                }
                if (this.roof != null) {
                    float r = this.ceilingHeight - (float) this.getFromMap(x+offsetX, z+offsetZ, this.roof);
                    for (int y = this.ceilingHeight; y > r; y--) {
                        mutable.setY(y);
                        chunk.setBlockState(new BlockPos(x+offsetX, y, z+offsetZ), defaultBlock, false);
                    }
                    surfaceHeightmap.trackUpdate(x, this.ceilingHeight, z, defaultBlock);
                }
            }
        }
        return chunk;
    }

    @Override
    public int getSeaLevel() {
        return this.seaLevel;
    }
    public int getSeaLevel(int x, int z) {
        if (this.aquifer != null) {
            return (int) Math.min(Math.max(this.getFromMap(x, z, this.aquifer), this.seaLevel), this.minimumY+this.getWorldHeight());
        }
        return seaLevel;
    }

    @Override
    public int getMinimumY() {
        return this.settings.value().generationShapeConfig().minimumY();
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
        return (int) ((
                        heightmap == Heightmap.Type.OCEAN_FLOOR_WG || heightmap == Heightmap.Type.OCEAN_FLOOR)
                        ? this.getFromMap(x, z, this.heightmap)
                        : Math.max(this.seaLevel, this.getFromMap(x, z, this.heightmap)));
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        int elevation = (int) this.getFromMap(x, z, this.heightmap);
        int seaLevel = this.getSeaLevel(x, z);
        if (elevation <= 0) return new VerticalBlockSample(0, new BlockState[]{Blocks.AIR.getDefaultState()});
        if (elevation < seaLevel) {
            return new VerticalBlockSample(
                    this.settings.value().generationShapeConfig().minimumY(),
                    Stream.concat(
                            Stream.generate(() -> this.settings.value().defaultBlock()).limit(elevation),
                            Stream.generate(() -> this.settings.value().defaultFluid()).limit(seaLevel - elevation)
            ).toArray(BlockState[]::new));
        }
            return new VerticalBlockSample(
                    this.settings.value().generationShapeConfig().minimumY(),
                    Stream.generate(() -> this.settings.value().defaultBlock()).limit(elevation).toArray(BlockState[]::new)

            );
    }

    @Override
    public void getDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
    }

    private ChunkNoiseSampler createChunkNoiseSampler(Chunk chunk, StructureAccessor world, Blender blender, NoiseConfig noiseConfig) {
        return ChunkNoiseSampler.create(chunk, noiseConfig, StructureWeightSampler.createStructureWeightSampler(world, chunk.getPos()), this.settings.value(), this.createFluidLevelSampler(this.settings.value()), blender);
    }
    private AquiferSampler.FluidLevelSampler createFluidLevelSampler(ChunkGeneratorSettings settings) {
        AquiferSampler.FluidLevel fluidLevel = new AquiferSampler.FluidLevel(-54, Blocks.LAVA.getDefaultState());
        int i = settings.seaLevel();
        AquiferSampler.FluidLevel fluidLevel2 = new AquiferSampler.FluidLevel(i, settings.defaultFluid());
        AquiferSampler.FluidLevel fluidLevel3 = new AquiferSampler.FluidLevel(DimensionType.MIN_HEIGHT * 2, Blocks.AIR.getDefaultState());
        return (x, y, z) -> {
            if (y < Math.min(-54, i)) {
                return fluidLevel;
            } else if (this.getFromMap(x, z, this.heightmap) < (this.aquifer == null ? this.seaLevel : this.getFromMap(x, z, this.aquifer))) {
                return fluidLevel2;
            }
            return fluidLevel3;
        };
    }


}

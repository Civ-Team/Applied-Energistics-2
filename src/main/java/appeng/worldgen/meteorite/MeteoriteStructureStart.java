/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2021, TeamAppliedEnergistics, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.worldgen.meteorite;

import java.util.Set;

import com.google.common.math.StatsAccumulator;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ITag;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MutableBoundingBox;
import net.minecraft.util.registry.DynamicRegistries;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.Biome.Category;
import net.minecraft.world.gen.ChunkGenerator;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.gen.Heightmap.Type;
import net.minecraft.world.gen.feature.NoFeatureConfig;
import net.minecraft.world.gen.feature.structure.Structure;
import net.minecraft.world.gen.feature.structure.StructureStart;
import net.minecraft.world.gen.feature.template.TemplateManager;

import appeng.worldgen.meteorite.fallout.FalloutMode;

public class MeteoriteStructureStart extends StructureStart<NoFeatureConfig> {

    private final ITag<Block> sandTag = BlockTags.getCollection().getTagByID(new ResourceLocation("minecraft:sand"));
    private final ITag<Block> terracottaTag = BlockTags.getCollection()
            .getTagByID(new ResourceLocation("forge:terracotta"));

    public MeteoriteStructureStart(Structure<NoFeatureConfig> feature, int chunkX, int chunkZ, MutableBoundingBox box,
            int references, long seed) {
        super(feature, chunkX, chunkZ, box, references, seed);
    }

    @Override
    public void func_230364_a_(DynamicRegistries dynamicRegistryManager, ChunkGenerator generator,
            TemplateManager templateManager, int chunkX, int chunkZ, Biome biome, NoFeatureConfig config) {
        final int centerX = chunkX * 16 + this.rand.nextInt(16);
        final int centerZ = chunkZ * 16 + this.rand.nextInt(16);
        final float meteoriteRadius = (this.rand.nextFloat() * 6.0f) + 2;
        final int yOffset = (int) Math.ceil(meteoriteRadius) + 1;

        final Set<Biome> t2 = generator.getBiomeProvider().getBiomes(centerX, 0, centerZ, 0);
        final Biome spawnBiome = t2.stream().findFirst().orElse(biome);

        final boolean isOcean = spawnBiome.getCategory() == Category.OCEAN;
        final Type heightmapType = isOcean ? Heightmap.Type.OCEAN_FLOOR_WG : Heightmap.Type.WORLD_SURFACE_WG;

        // Accumulate stats about the surrounding heightmap
        StatsAccumulator stats = new StatsAccumulator();
        int scanRadius = (int) Math.max(1, meteoriteRadius * 2);
        for (int x = -scanRadius; x <= scanRadius; x++) {
            for (int z = -scanRadius; z <= scanRadius; z++) {
                int h = generator.getHeight(centerX + x, centerZ + z, heightmapType);
                stats.add(h);
            }
        }

        int centerY = (int) stats.mean();
        // Spawn it down a bit further with a high variance.
        if (stats.populationVariance() > 5) {
            centerY -= (stats.mean() - stats.min()) * .75;
        }

        // Offset caused by the meteorsize
        centerY -= yOffset;
        // Limit to not spawn below y32
        centerY = Math.max(32, centerY);

        BlockPos actualPos = new BlockPos(centerX, centerY, centerZ);

        boolean craterLake = this.locateWaterAroundTheCrater(generator, actualPos, meteoriteRadius);
        CraterType craterType = this.determineCraterType(spawnBiome);
        boolean pureCrater = this.rand.nextFloat() > .9f;
        FalloutMode fallout = getFalloutFromBaseBlock(
                spawnBiome.getGenerationSettings().getSurfaceBuilderConfig().getTop());

        components.add(
                new MeteoriteStructurePiece(actualPos, meteoriteRadius, craterType, fallout, pureCrater, craterLake));

        this.recalculateStructureSize();
    }

    /**
     * Scan for water about 1 block further than the crater radius 333 1174
     *
     * @return true, if it found a single block of water
     */
    private boolean locateWaterAroundTheCrater(ChunkGenerator generator, BlockPos pos, float radius) {
        final int seaLevel = generator.getSeaLevel();
        final int maxY = seaLevel - 1;
        BlockPos.Mutable blockPos = new BlockPos.Mutable();

        blockPos.setY(maxY);
        for (int i = pos.getX() - 32; i <= pos.getX() + 32; i++) {
            blockPos.setX(i);

            for (int k = pos.getZ() - 32; k <= pos.getZ() + 32; k++) {
                blockPos.setZ(k);
                final double dx = i - pos.getX();
                final double dz = k - pos.getZ();
                final double h = pos.getY() - radius + 1;

                final double distanceFrom = dx * dx + dz * dz;

                if (maxY > h + distanceFrom * 0.0175 && maxY < h + distanceFrom * 0.02) {
                    int heigth = generator.getHeight(blockPos.getX(), blockPos.getZ(), Heightmap.Type.OCEAN_FLOOR);
                    if (heigth < seaLevel) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private CraterType determineCraterType(Biome biome) {
        // The temperature thresholds below are taken from older Vanilla code
        // (temperature categories)
        final float temp = biome.getTemperature();
        final Category category = biome.getCategory();

        // No craters in oceans
        if (category == Category.OCEAN) {
            return CraterType.NONE;
        }

        // 50% chance for a special meteor
        final boolean specialMeteor = rand.nextFloat() > .5f;

        // Just a normal one
        if (!specialMeteor) {
            return CraterType.NORMAL;
        }

        // Warm biomes, higher chance for lava
        if (temp >= 1) {

            // 50% chance to actually spawn as lava
            final boolean lava = rand.nextFloat() > .5f;

            switch (biome.getPrecipitation()) {
                // No rainfall, only lava
                case NONE:
                    return lava ? CraterType.LAVA : CraterType.NORMAL;

                // 25% chance to convert a lava to obsidian
                case RAIN:
                    final boolean obsidian = rand.nextFloat() > .75f;
                    final CraterType alternativObsidian = obsidian ? CraterType.OBSIDIAN : CraterType.LAVA;
                    return lava ? alternativObsidian : CraterType.NORMAL;

                // Nothing for now.
                default:
                    break;
            }
        }

        // Temperate biomes. Water or maybe lava
        if (temp < 1 && temp >= 0.2) {
            // 75% chance to actually spawn with a crater lake
            final boolean lake = rand.nextFloat() > .25f;
            // 20% to spawn with lava
            final boolean lava = rand.nextFloat() > .8f;

            switch (biome.getPrecipitation()) {
                // No rainfall, water how?
                case NONE:
                    return lava ? CraterType.LAVA : CraterType.NORMAL;
                // Rainfall, can also turn lava to obsidian
                case RAIN:
                    final boolean obsidian = rand.nextFloat() > .75f;
                    final CraterType alternativObsidian = obsidian ? CraterType.OBSIDIAN : CraterType.LAVA;
                    final CraterType craterLake = lake ? CraterType.WATER : CraterType.NORMAL;
                    return lava ? alternativObsidian : craterLake;
                // No lava, but snow
                case SNOW:
                    final boolean snow = rand.nextFloat() > .75f;
                    final CraterType water = lake ? CraterType.WATER : CraterType.NORMAL;
                    return snow ? CraterType.SNOW : water;
            }

        }

        // Cold biomes, Snow or Ice, maybe water and very rarely lava.
        if (temp < 0.2) {
            // 75% chance to actually spawn with a crater lake
            final boolean lake = rand.nextFloat() > .25f;
            // 5% to spawn with lava
            final boolean lava = rand.nextFloat() > .95f;
            // 75% chance to freeze
            final boolean frozen = rand.nextFloat() > .25f;

            switch (biome.getPrecipitation()) {
                // No rainfall, water how?
                case NONE:
                    return lava ? CraterType.LAVA : CraterType.NORMAL;
                case RAIN:
                    final CraterType frozenLake = frozen ? CraterType.ICE : CraterType.WATER;
                    final CraterType craterLake = lake ? frozenLake : CraterType.NORMAL;
                    return lava ? CraterType.LAVA : craterLake;
                case SNOW:
                    final CraterType snowCovered = lake ? CraterType.SNOW : CraterType.NORMAL;
                    return lava ? CraterType.LAVA : snowCovered;
            }

        }

        return CraterType.NORMAL;
    }

    private FalloutMode getFalloutFromBaseBlock(BlockState blockState) {
        final Block block = blockState.getBlock();

        if (block.isIn(sandTag)) {
            return FalloutMode.SAND;
        } else if (block.isIn(terracottaTag)) {
            return FalloutMode.TERRACOTTA;
        } else if (block == Blocks.SNOW || block == Blocks.SNOW || block == Blocks.SNOW_BLOCK || block == Blocks.ICE
                || block == Blocks.PACKED_ICE) {
            return FalloutMode.ICE_SNOW;
        } else {
            return FalloutMode.DEFAULT;
        }
    }

}
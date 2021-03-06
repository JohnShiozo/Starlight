package ca.spottedleaf.starlight.common.light;

import ca.spottedleaf.starlight.common.blockstate.LightAccessBlockState;
import ca.spottedleaf.starlight.common.chunk.NibbledChunk;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.ChunkSection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public abstract class StarLightEngine {

    protected static final BlockState AIR_BLOCK_STATE = Blocks.AIR.getDefaultState();

    protected static final ChunkSection EMPTY_CHUNK_SECTION = new ChunkSection(0);

    protected static final AxisDirection[] DIRECTIONS = AxisDirection.values();
    protected static final AxisDirection[] AXIS_DIRECTIONS = DIRECTIONS;
    protected static final AxisDirection[] ONLY_HORIZONTAL_DIRECTIONS = new AxisDirection[] {
            AxisDirection.POSITIVE_X, AxisDirection.NEGATIVE_X,
            AxisDirection.POSITIVE_Z, AxisDirection.NEGATIVE_Z
    };

    protected static enum AxisDirection {

        // Declaration order is important and relied upon. Do not change without modifying propagation code.
        POSITIVE_X(1, 0, 0), NEGATIVE_X(-1, 0, 0),
        POSITIVE_Z(0, 0, 1), NEGATIVE_Z(0, 0, -1),
        POSITIVE_Y(0, 1, 0), NEGATIVE_Y(0, -1, 0);

        static {
            POSITIVE_X.opposite = NEGATIVE_X; NEGATIVE_X.opposite = POSITIVE_X;
            POSITIVE_Z.opposite = NEGATIVE_Z; NEGATIVE_Z.opposite = POSITIVE_Z;
            POSITIVE_Y.opposite = NEGATIVE_Y; NEGATIVE_Y.opposite = POSITIVE_Y;
        }

        protected AxisDirection opposite;

        public final int x;
        public final int y;
        public final int z;
        public final Direction nms;

        AxisDirection(final int x, final int y, final int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.nms = Direction.fromVector(x, y, z);
        }

        public AxisDirection getOpposite() {
            return this.opposite;
        }
    }

    // I'd like to thank https://www.seedofandromeda.com/blogs/29-fast-flood-fill-lighting-in-a-blocky-voxel-game-pt-1
    // for explaining how light propagates via breadth-first search

    // While the above is a good start to understanding the general idea of what the general principles are, it's not
    // exactly how the vanilla light engine should behave for minecraft.

    // similar to the above, except the chunk section indices vary from [-1, 1], or [0, 2]
    // for the y chunk section it's from [-1, 16] or [0, 17]
    // index = x + (z * 5) + (y * 25)
    // null index indicates the chunk section doesn't exist (empty or out of bounds)
    protected final ChunkSection[] sectionCache = new ChunkSection[5 * 5 * (16 + 2 + 2)]; // add two extra sections for buffer

    // the exact same as above, except for storing fast access to SWMRNibbleArray
    // for the y chunk section it's from [-1, 16] or [0, 17]
    // index = x + (z * 5) + (y * 25)
    protected final SWMRNibbleArray[] nibbleCache = new SWMRNibbleArray[5 * 5 * (16 + 2 + 2)]; // add two extra sections for buffer

    // always initialsed during start of lighting. no index is null.
    // index = x + (z * 5)
    protected final Chunk[] chunkCache = new Chunk[5 * 5];

    protected final BlockPos.Mutable mutablePos1 = new BlockPos.Mutable();
    protected final BlockPos.Mutable mutablePos2 = new BlockPos.Mutable();
    protected final BlockPos.Mutable mutablePos3 = new BlockPos.Mutable();

    protected int encodeOffsetX;
    protected int encodeOffsetY;
    protected int encodeOffsetZ;

    protected int coordinateOffset;

    protected int chunkOffsetX;
    protected int chunkOffsetY;
    protected int chunkOffsetZ;

    protected int chunkIndexOffset;
    protected int chunkSectionIndexOffset;

    protected final boolean skylightPropagator;
    protected final int emittedLightMask;
    protected final boolean isClientSide;

    protected StarLightEngine(final boolean skylightPropagator, final boolean isClientSide) {
        this.skylightPropagator = skylightPropagator;
        this.emittedLightMask = skylightPropagator ? 0 : 0xF;
        this.isClientSide = isClientSide;
    }

    protected final void setupEncodeOffset(final int centerX, final int centerY, final int centerZ) {
        // 31 = center + encodeOffset
        this.encodeOffsetX = 31 - centerX;
        this.encodeOffsetY = 31; // we want 0 to be the smallest encoded value
        this.encodeOffsetZ = 31 - centerZ;

        // coordinateIndex = x | (z << 6) | (y << 12)
        this.coordinateOffset = this.encodeOffsetX + (this.encodeOffsetZ << 6) + (this.encodeOffsetY << 12);

        // 2 = (centerX >> 4) + chunkOffset
        this.chunkOffsetX = 2 - (centerX >> 4);
        this.chunkOffsetY = 2; // lowest should be 0, not -2
        this.chunkOffsetZ = 2 - (centerZ >> 4);

        // chunk index = x + (5 * z)
        this.chunkIndexOffset = this.chunkOffsetX + (5 * this.chunkOffsetZ);

        // chunk section index = x + (5 * z) + ((5*5) * y)
        this.chunkSectionIndexOffset = this.chunkIndexOffset + ((5 * 5) * this.chunkOffsetY);
    }

    protected final void setupCaches(final ChunkProvider chunkProvider, final int centerX, final int centerY, final int centerZ, final boolean relaxed) {
        final int centerChunkX = centerX >> 4;
        final int centerChunkY = centerY >> 4;
        final int centerChunkZ = centerZ >> 4;

        this.setupEncodeOffset(centerChunkX * 16 + 7, centerChunkY * 16 + 7, centerChunkZ * 16 + 7);

        final int minX = centerChunkX - 1;
        final int minZ = centerChunkZ - 1;
        final int maxX = centerChunkX + 1;
        final int maxZ = centerChunkZ + 1;

        for (int cx = minX; cx <= maxX; ++cx) {
            for (int cz = minZ; cz <= maxZ; ++cz) {
                final Chunk chunk = (Chunk)chunkProvider.getChunk(cx, cz); // mappings are awful here, this is the "get chunk at if at least features"

                if (chunk == null) {
                    if (relaxed) {
                        continue;
                    }
                    throw new IllegalArgumentException("Trying to propagate light update before 1 radius neighbours ready");
                }

                if (!this.canUseChunk(chunk)) {
                    continue;
                }

                this.setChunkInCache(cx, cz, chunk);
                this.setBlocksForChunkInCache(cx, cz, chunk.getSectionArray());
                this.setNibblesForChunkInCache(cx, cz, this.getNibblesOnChunk(chunk));
            }
        }
    }

    protected final Chunk getChunkInCache(final int chunkX, final int chunkZ) {
        return this.chunkCache[chunkX + 5*chunkZ + this.chunkIndexOffset];
    }

    protected final void setChunkInCache(final int chunkX, final int chunkZ, final Chunk chunk) {
        this.chunkCache[chunkX + 5*chunkZ + this.chunkIndexOffset] = chunk;
    }

    protected final void setBlocksForChunkInCache(final int chunkX, final int chunkZ, final ChunkSection[] sections) {
        final int chunkIndex = chunkX + 5*chunkZ;
        for (int cy = -1; cy <= 16; ++cy) {
            this.sectionCache[chunkIndex + (cy * (5 * 5)) + this.chunkSectionIndexOffset] = sections == null ? null : (cy >= 0 && cy <= 15 ? (sections[cy] == null || sections[cy].isEmpty() ? EMPTY_CHUNK_SECTION : sections[cy]) : EMPTY_CHUNK_SECTION);
        }
    }

    protected final SWMRNibbleArray getNibbleFromCache(final int chunkX, final int chunkY, final int chunkZ) {
        return this.nibbleCache[chunkX + 5*chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset];
    }

    protected final SWMRNibbleArray[] getNibblesForChunkFromCache(final int chunkX, final int chunkZ) {
        final SWMRNibbleArray[] ret = getEmptyLightArray();

        for (int cy = -1; cy <= 16; ++cy) {
            ret[cy + 1] = this.nibbleCache[chunkX + 5*chunkZ + (cy * (5 * 5)) + this.chunkSectionIndexOffset];
        }

        return ret;
    }

    protected final void setNibblesForChunkInCache(final int chunkX, final int chunkZ, final SWMRNibbleArray[] nibbles) {
        for (int cy = -1; cy <= 16; ++cy) {
            this.setNibbleInCache(chunkX, cy, chunkZ, nibbles == null ? null : nibbles[cy + 1]);
        }
    }

    protected final void setNibbleInCache(final int chunkX, final int chunkY, final int chunkZ, final SWMRNibbleArray nibble) {
        this.nibbleCache[chunkX + 5*chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset] = nibble;
    }

    protected final void updateVisible(final ChunkProvider lightAccess) {
        for (int index = 0, max = this.nibbleCache.length; index < max; ++index) {
            final SWMRNibbleArray nibble = this.nibbleCache[index];
            if (nibble != null && nibble.updateVisible()) {
                final int chunkX = (index % 5) - this.chunkOffsetX;
                final int chunkZ = ((index / 5) % 5) - this.chunkOffsetZ;
                final int chunkY = ((index / (5*5)) % (16 + 2 + 2)) - this.chunkOffsetY;
                lightAccess.onLightUpdate(this.skylightPropagator ? LightType.SKY : LightType.BLOCK, ChunkSectionPos.from(chunkX, chunkY, chunkZ));
                // initialise 1 radius neighbours
                if (this.skylightPropagator) {
                    for (int dy = -1; dy <= 1; ++dy) {
                        for (int dz = -1; dz <= 1; ++dz) {
                            for (int dx = -1; dx <= 1; ++dx) {
                                SWMRNibbleArray neighbour = this.getNibbleFromCache(chunkX + dx, chunkY + dy, chunkZ + dz);
                                if (neighbour != null && !neighbour.isDirty() && neighbour.isNullNibbleUpdating()) {
                                    neighbour.initialiseWorking();
                                    neighbour.updateVisible();
                                    lightAccess.onLightUpdate(this.skylightPropagator ? LightType.SKY : LightType.BLOCK,
                                            ChunkSectionPos.from(chunkX + dx, chunkY + dy, chunkZ + dz));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    protected final void destroyCaches() {
        Arrays.fill(this.sectionCache, null);
        Arrays.fill(this.nibbleCache, null);
        Arrays.fill(this.chunkCache, null);
    }

    protected final BlockState getBlockState(final int worldX, final int worldY, final int worldZ) {
        final ChunkSection section = this.sectionCache[(worldX >> 4) + 5 * (worldZ >> 4) + (5 * 5) * (worldY >> 4) + this.chunkSectionIndexOffset];

        if (section != null) {
            return section == EMPTY_CHUNK_SECTION ? AIR_BLOCK_STATE : section.getBlockState(worldX & 15, worldY & 15, worldZ & 15);
        }

        return null;
    }

    protected final BlockState getBlockState(final int sectionIndex, final int localIndex) {
        final ChunkSection section = this.sectionCache[sectionIndex];

        if (section != null) {
            return section == EMPTY_CHUNK_SECTION ? AIR_BLOCK_STATE : section.getBlockState(
                    localIndex & 15,
                    (localIndex >>> 8) & 15,
                    (localIndex >>> 4) & 15
            ); // TODO
        }

        return null;
    }

    protected final int getLightLevel(final int worldX, final int worldY, final int worldZ) {
        final SWMRNibbleArray nibble = this.nibbleCache[(worldX >> 4) + 5 * (worldZ >> 4) + (5 * 5) * (worldY >> 4) + this.chunkSectionIndexOffset];

        return nibble == null ? 0 : nibble.getUpdating((worldX & 15) | ((worldZ & 15) << 4) | ((worldY & 15) << 8));
    }

    protected final int getLightLevel(final int sectionIndex, final int localIndex) {
        final SWMRNibbleArray nibble = this.nibbleCache[sectionIndex];

        return nibble == null ? 0 : nibble.getUpdating(localIndex);
    }

    protected final void setLightLevel(final int worldX, final int worldY, final int worldZ, final int level) {
        final SWMRNibbleArray nibble = this.nibbleCache[(worldX >> 4) + 5 * (worldZ >> 4) + (5 * 5) * (worldY >> 4) + this.chunkSectionIndexOffset];

        if (nibble != null) {
            nibble.set((worldX & 15) | ((worldZ & 15) << 4) | ((worldY & 15) << 8), level);
        }
    }

    protected final void setLightLevel(final int sectionIndex, final int localIndex, final int level) {
        final SWMRNibbleArray nibble = this.nibbleCache[sectionIndex];

        if (nibble != null) {
            nibble.set(localIndex, level);
        }
    }

    public static SWMRNibbleArray[] getFilledEmptyLight(final boolean skylight) {
        final SWMRNibbleArray[] ret = getEmptyLightArray();

        for (int i = 0, len = ret.length; i < len; ++i) {
            ret[i] = new SWMRNibbleArray(true, skylight ? 15 : 0);
        }

        return ret;
    }

    public static SWMRNibbleArray[] getEmptyLightArray() {
        return new SWMRNibbleArray[16 + 2];
    }

    protected abstract SWMRNibbleArray[] getNibblesOnChunk(final Chunk chunk);

    protected abstract void setNibbles(final Chunk chunk, final SWMRNibbleArray[] to);

    protected abstract boolean canUseChunk(final Chunk chunk);

    public final void blocksChangedInChunk(final ChunkProvider lightAccess, final int chunkX, final int chunkZ,
                                           final Set<BlockPos> positions) {
        this.setupCaches(lightAccess, chunkX * 16 + 7, 128, chunkZ * 16 + 7, this.isClientSide);
        try {
            final Chunk chunk = this.getChunkInCache(chunkX, chunkZ);
            if (this.isClientSide && chunk == null) {
                return;
            }
            this.propagateBlockChanges(lightAccess, chunk, positions);
            this.updateVisible(lightAccess);
        } finally {
            this.destroyCaches();
        }
    }

    // subclasses should not initialise caches, as this will always be done by the super call
    // subclasses should not invoke updateVisible, as this will always be done by the super call
    protected abstract void propagateBlockChanges(final ChunkProvider lightAccess, final Chunk atChunk,
                                                  final Set<BlockPos> positions);

    protected abstract void checkBlock(final int worldX, final int worldY, final int worldZ);

    // subclasses should not initialise caches, as this will always be done by the super call
    // subclasses should not invoke updateVisible, as this will always be done by the super call
    protected final void checkChunkEdges(final ChunkProvider lightAccess, final Chunk chunk) {
        final ChunkPos chunkPos = chunk.getPos();
        final int chunkX = chunkPos.x;
        final int chunkZ = chunkPos.z;

        for (int currSectionY = 16; currSectionY >= -1; --currSectionY) {
            final SWMRNibbleArray currNibble = this.getNibbleFromCache(chunkX, currSectionY, chunkZ);
            for (final AxisDirection direction : ONLY_HORIZONTAL_DIRECTIONS) {
                final int neighbourOffX = direction.x;
                final int neighbourOffZ = direction.z;

                final SWMRNibbleArray neighbourNibble = this.getNibbleFromCache(chunkX + neighbourOffX,
                        currSectionY, chunkZ + neighbourOffZ);

                if (neighbourNibble == null) {
                    continue;
                }

                if (!currNibble.isInitialisedUpdating() && !neighbourNibble.isInitialisedUpdating()) {
                    if (this.skylightPropagator) {
                        if (currNibble.isNullNibbleUpdating() == neighbourNibble.isNullNibbleUpdating()) {
                            continue;
                        } // else fall through to edge checks
                    } else {
                        continue;
                    }
                }

                final int incX;
                final int incZ;
                final int startX;
                final int startZ;

                if (neighbourOffX != 0) {
                    // x direction
                    incX = 0;
                    incZ = 1;

                    if (direction.x < 0) {
                        // negative
                        startX = chunkX << 4;
                    } else {
                        startX = chunkX << 4 | 15;
                    }
                    startZ = chunkZ << 4;
                } else {
                    // z direction
                    incX = 1;
                    incZ = 0;

                    if (neighbourOffZ < 0) {
                        // negative
                        startZ = chunkZ << 4;
                    } else {
                        startZ = chunkZ << 4 | 15;
                    }
                    startX = chunkX << 4;
                }

                for (int currY = currSectionY << 4, maxY = currY | 15; currY <= maxY; ++currY) {
                    for (int i = 0, currX = startX, currZ = startZ; i < 16; ++i, currX += incX, currZ += incZ) {
                        final int neighbourX = currX + neighbourOffX;
                        final int neighbourZ = currZ + neighbourOffZ;

                        final int currentLevel = currNibble.getUpdating((currX & 15) |
                                ((currZ & 15)) << 4 |
                                ((currY & 15) << 8)
                        );
                        final int neighbourLevel = neighbourNibble.getUpdating((neighbourX & 15) |
                                ((neighbourZ & 15)) << 4 |
                                ((currY & 15) << 8)
                        );

                        if (currentLevel == neighbourLevel && (currentLevel == 0 || currentLevel == 15)) {
                            // nothing to check here
                            continue;
                        }

                        if (Math.abs(currentLevel - neighbourLevel) == 1) {
                            final BlockState currentBlock = this.getBlockState(currX, currY, currZ);
                            final BlockState neighbourBlock = this.getBlockState(neighbourX, currY, neighbourZ);

                            final int currentOpacity = ((LightAccessBlockState)currentBlock).getOpacityIfCached();
                            final int neighbourOpacity = ((LightAccessBlockState)neighbourBlock).getOpacityIfCached();
                            if (currentOpacity == 0 || currentOpacity == 1 ||
                                    neighbourOpacity == 0 || neighbourOpacity == 1) {
                                // looks good
                                continue;
                            }
                        }

                        // setup queue, it looks like something could be inconsistent
                        this.checkBlock(currX, currY, currZ);
                        this.checkBlock(neighbourX, currY, neighbourZ);
                    }
                }
            }
        }

        this.performLightDecrease(lightAccess);
    }

    protected final void propagateNeighbourLevels(final ChunkProvider lightAccess, final Chunk chunk, final int fromSection, final int toSection) {
        final ChunkPos chunkPos = chunk.getPos();
        final int chunkX = chunkPos.x;
        final int chunkZ = chunkPos.z;

        for (int currSectionY = toSection; currSectionY >= fromSection; --currSectionY) {
            final SWMRNibbleArray currNibble = this.getNibbleFromCache(chunkX, currSectionY, chunkZ);
            for (final AxisDirection direction : ONLY_HORIZONTAL_DIRECTIONS) {
                final int neighbourOffX = direction.x;
                final int neighbourOffZ = direction.z;

                final SWMRNibbleArray neighbourNibble = this.getNibbleFromCache(chunkX + neighbourOffX,
                        currSectionY, chunkZ + neighbourOffZ);

                if (neighbourNibble == null) {
                    continue;
                }

                if (!neighbourNibble.isInitialisedUpdating()) {
                    if (this.skylightPropagator) {
                        if (currNibble.isNullNibbleUpdating() == neighbourNibble.isNullNibbleUpdating() || !neighbourNibble.isNullNibbleUpdating()) {
                            continue;
                        } // else fall through to edge checks
                    } else {
                        continue;
                    }
                }

                final int incX;
                final int incZ;
                final int startX;
                final int startZ;

                if (neighbourOffX != 0) {
                    // x direction
                    incX = 0;
                    incZ = 1;

                    if (direction.x < 0) {
                        // negative
                        startX = (chunkX << 4) - 1;
                    } else {
                        startX = (chunkX << 4) + 16;
                    }
                    startZ = chunkZ << 4;
                } else {
                    // z direction
                    incX = 1;
                    incZ = 0;

                    if (neighbourOffZ < 0) {
                        // negative
                        startZ = (chunkZ << 4) - 1;
                    } else {
                        startZ = (chunkZ << 4) + 16;
                    }
                    startX = chunkX << 4;
                }

                final int propagateDirection = direction.getOpposite().ordinal() | 16; // we only want to check in this direction towards this chunk
                final int encodeOffset = this.coordinateOffset;

                for (int currY = currSectionY << 4, maxY = currY | 15; currY <= maxY; ++currY) {
                    for (int i = 0, currX = startX, currZ = startZ; i < 16; ++i, currX += incX, currZ += incZ) {
                        final int level = neighbourNibble.getUpdating(
                                (currX & 15) |
                                (currZ & 15) << 4 |
                                (currY & 15) << 8
                        );

                        if (level <= 1) {
                            // nothing to propagate
                            continue;
                        }

                        this.increaseQueue[this.increaseQueueInitialLength++] = (currX + (currZ << 6) + (currY << (6 + 6)) + encodeOffset) |
                                (level << (6 + 6 + 9)) |
                                ((propagateDirection) << (6 + 6 + 9 + 4)) |
                                FLAG_HAS_SIDED_TRANSPARENT_BLOCKS; // don't know if the current block is transparent, must check.
                    }
                }
            }
        }
    }

    public final void checkChunkEdges(final ChunkProvider lightAccess, final int chunkX, final int chunkZ) {
        this.setupCaches(lightAccess, chunkX * 16 + 7, 128, chunkZ * 16 + 7, false);
        try {
            if (this.getChunkInCache(chunkX, chunkZ) == null) {
                return;
            }
            this.checkChunkEdges(lightAccess, this.getChunkInCache(chunkX, chunkZ));
            this.updateVisible(lightAccess);
        } finally {
            this.destroyCaches();
        }
    }

    // subclasses should not initialise caches, as this will always be done by the super call
    // subclasses should not invoke updateVisible, as this will always be done by the super call
    // needsEdgeChecks applies when possibly loading vanilla data, which means we need to validate the current
    // chunks light values with respect to neighbours
    protected abstract void lightChunk(final ChunkProvider lightAccess, final Chunk chunk, final boolean needsEdgeChecks);

    public final void light(final ChunkProvider lightAccess, final int chunkX, final int chunkZ) {
        this.setupCaches(lightAccess, chunkX * 16 + 7, 128, chunkZ * 16 + 7, false);
        // force current chunk into cache
        final Chunk chunk =  (Chunk)lightAccess.getChunk(chunkX, chunkZ);
        this.setChunkInCache(chunkX, chunkZ, chunk);
        this.setBlocksForChunkInCache(chunkX, chunkZ, chunk.getSectionArray());
        this.setNibblesForChunkInCache(chunkX, chunkZ, this.getNibblesOnChunk(chunk));

        try {
            this.lightChunk(lightAccess, chunk, false);
            this.updateVisible(lightAccess);
        } finally {
            this.destroyCaches();
        }
    }

    public final void relight(final ChunkProvider lightAccess, final int chunkX, final int chunkZ) {
        final Chunk chunk = (Chunk)lightAccess.getChunk(chunkX, chunkZ);
        this.relightChunk(lightAccess, chunk);
    }

    protected final void relightChunk(final ChunkProvider lightAccess, final Chunk chunk) {
        final ChunkPos chunkPos = chunk.getPos();
        this.setupEncodeOffset(chunkPos.x * 16 + 7, 128, chunkPos.z * 16 + 7);

        try {
            this.setChunkInCache(chunkPos.x, chunkPos.z, chunk);
            this.setBlocksForChunkInCache(chunkPos.x, chunkPos.z, chunk.getSectionArray());
            final SWMRNibbleArray[] chunkNibbles = getFilledEmptyLight(this.skylightPropagator);
            this.setNibblesForChunkInCache(chunkPos.x, chunkPos.z, chunkNibbles);
            this.lightChunk(lightAccess, chunk, false);

            for (int dz = -1; dz <= 1; ++dz) {
                for (int dx = -1; dx <= 1; ++dx) {
                    if ((dx | dz) == 0) {
                        continue;
                    }

                    final int cx = dx + chunkPos.x;
                    final int cz = dz + chunkPos.z;
                    final Chunk neighbourChunk = (Chunk)lightAccess.getChunk(cx, cz);

                    if (neighbourChunk == null || !this.canUseChunk(neighbourChunk)) {
                        continue;
                    }

                    this.setChunkInCache(cx, cz, neighbourChunk);
                    this.setBlocksForChunkInCache(cx, cz, neighbourChunk.getSectionArray());
                    this.setNibblesForChunkInCache(cx, cz, getFilledEmptyLight(this.skylightPropagator));
                    this.lightChunk(lightAccess, neighbourChunk, false);
                }
            }

            this.setNibbles(chunk, chunkNibbles);
            this.updateVisible(lightAccess);
        } finally {
            this.destroyCaches();
        }
    }

    // old algorithm for propagating
    // this is also the basic algorithm, the optimised algorithm is always going to be tested against this one
    // and this one is always tested against vanilla
    // contains:
    // lower 21 bits: encoded coordinate position (x | (z << 6) | (y << (6 + 6))))
    // next 4 bits: propagated light level (0, 15]
    // next 5 bits: direction propagated from
    // next 0 bits: unused
    // last 2 bits: state flags
    // state flags:
    // whether the propagation needs to check if its current level is equal to the expected level
    // used only in increase propagation
    protected static final int FLAG_RECHECK_LEVEL = Integer.MIN_VALUE >>> 1;
    // whether the propagation needs to consider if its block is conditionally transparent
    protected static final int FLAG_HAS_SIDED_TRANSPARENT_BLOCKS = Integer.MIN_VALUE;

    protected final int[] increaseQueue = new int[16 * 16 * (16 * (16 + 2)) * 9 + 1];
    protected int increaseQueueInitialLength;
    protected final int[] decreaseQueue = new int[16 * 16 * (16 * (16 + 2)) * 9 + 1];
    protected int decreaseQueueInitialLength;

    protected static final AxisDirection[][] OLD_CHECK_DIRECTIONS = new AxisDirection[4 * 8][];
    static {
        for (int i = 0; i < AXIS_DIRECTIONS.length; ++i) {
            final AxisDirection direction = AXIS_DIRECTIONS[i];
            final List<AxisDirection> directions = new ArrayList<>(Arrays.asList(AXIS_DIRECTIONS));
            directions.remove(direction.getOpposite());
            OLD_CHECK_DIRECTIONS[direction.ordinal()] = directions.toArray(new AxisDirection[0]);
            OLD_CHECK_DIRECTIONS[direction.ordinal() | 8] = AXIS_DIRECTIONS; // flag ALL_DIRECTIONS
            OLD_CHECK_DIRECTIONS[direction.ordinal() | 16] = new AxisDirection[] { direction }; // flag ONLY_THIS_DIRECTION
        }
    }

    protected final void performLightIncrease(final ChunkProvider lightAccess) {
        final BlockView world = lightAccess.getWorld();
        final int[] queue = this.increaseQueue;
        int queueReadIndex = 0;
        int queueLength = this.increaseQueueInitialLength;
        this.increaseQueueInitialLength = 0;
        final int decodeOffsetX = -this.encodeOffsetX;
        final int decodeOffsetY = -this.encodeOffsetY;
        final int decodeOffsetZ = -this.encodeOffsetZ;
        final int encodeOffset = this.coordinateOffset;
        final int sectionOffset = this.chunkSectionIndexOffset;

        while (queueReadIndex < queueLength) {
            final int queueValue = queue[queueReadIndex++];

            final int posX = ((queueValue & 63) + decodeOffsetX);
            final int posZ = (((queueValue >>> 6) & 63) + decodeOffsetZ);
            final int posY = (((queueValue >>> 12) & 511) + decodeOffsetY);
            final int propagatedLightLevel = ((queueValue >>> (6 + 6 + 9)) & 0xF);
            final int fromDirection = ((queueValue >>> (6 + 6 + 9 + 4)) & 0x1F);
            final AxisDirection[] checkDirections = OLD_CHECK_DIRECTIONS[fromDirection];

            if ((queueValue & FLAG_RECHECK_LEVEL) != 0) {
                if (this.getLightLevel(posX, posY, posZ) != propagatedLightLevel) {
                    // not at the level we expect, so something changed.
                    continue;
                }
            }

            if ((queueValue & FLAG_HAS_SIDED_TRANSPARENT_BLOCKS) == 0) {
                // we don't need to worry about our state here.
                for (final AxisDirection propagate : checkDirections) {
                    final int offX = posX + propagate.x;
                    final int offY = posY + propagate.y;
                    final int offZ = posZ + propagate.z;

                    final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
                    final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

                    final int currentLevel = this.getLightLevel(sectionIndex, localIndex);

                    if (currentLevel >= (propagatedLightLevel - 1)) {
                        continue; // already at the level we want
                    }

                    final BlockState blockState = this.getBlockState(sectionIndex, localIndex);
                    if (blockState == null) {
                        continue;
                    }
                    final int opacityCached = ((LightAccessBlockState)blockState).getOpacityIfCached();
                    if (opacityCached != -1) {
                        final int targetLevel = propagatedLightLevel - Math.max(1, opacityCached);
                        if (targetLevel > currentLevel) {
                            this.setLightLevel(sectionIndex, localIndex, targetLevel);
                            if (targetLevel > 1) {
                                queue[queueLength++] =
                                        (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                                | (targetLevel << (6 + 6 + 9))
                                                | (propagate.ordinal() << (6 + 6 + 9 + 4));
                                continue;
                            }
                        }
                        continue;
                    } else {
                        this.mutablePos1.set(offX, offY, offZ);
                        int flags = 0;
                        if (((LightAccessBlockState)blockState).isConditionallyFullOpaque()) {
                            final VoxelShape cullingFace = blockState.getCullingFace(world, this.mutablePos1, propagate.getOpposite().nms);

                            if (VoxelShapes.unionCoversFullCube(VoxelShapes.empty(), cullingFace)) {
                                continue;
                            }
                            flags |= FLAG_HAS_SIDED_TRANSPARENT_BLOCKS;
                        }

                        final int opacity = blockState.getOpacity(world, this.mutablePos1);
                        final int targetLevel = propagatedLightLevel - Math.max(1, opacity);
                        if (targetLevel <= currentLevel) {
                            continue;
                        }
                        this.setLightLevel(sectionIndex, localIndex, targetLevel);
                        if (targetLevel > 1) {
                            queue[queueLength++] =
                                    (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                            | (targetLevel << (6 + 6 + 9))
                                            | (propagate.ordinal() << (6 + 6 + 9 + 4))
                                            | (flags);
                        }
                        continue;
                    }
                }
            } else {
                // we actually need to worry about our state here
                final BlockState fromBlock = this.getBlockState(posX, posY, posZ);
                this.mutablePos2.set(posX, posY, posZ);
                for (final AxisDirection propagate : checkDirections) {
                    final int offX = posX + propagate.x;
                    final int offY = posY + propagate.y;
                    final int offZ = posZ + propagate.z;

                    final VoxelShape fromShape = (((LightAccessBlockState)fromBlock).isConditionallyFullOpaque()) ? fromBlock.getCullingFace(world, this.mutablePos2, propagate.nms) : VoxelShapes.empty();

                    if (fromShape != VoxelShapes.empty() && VoxelShapes.unionCoversFullCube(VoxelShapes.empty(), fromShape)) {
                        continue;
                    }

                    final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
                    final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

                    final int currentLevel = this.getLightLevel(sectionIndex, localIndex);

                    if (currentLevel >= (propagatedLightLevel - 1)) {
                        continue; // already at the level we want
                    }

                    final BlockState blockState = this.getBlockState(sectionIndex, localIndex);
                    if (blockState == null) {
                        continue;
                    }
                    final int opacityCached = ((LightAccessBlockState)blockState).getOpacityIfCached();
                    if (opacityCached != -1) {
                        final int targetLevel = propagatedLightLevel - Math.max(1, opacityCached);
                        if (targetLevel > currentLevel) {
                            this.setLightLevel(sectionIndex, localIndex, targetLevel);
                            if (targetLevel > 1) {
                                queue[queueLength++] =
                                        (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                                | (targetLevel << (6 + 6 + 9))
                                                | (propagate.ordinal() << (6 + 6 + 9 + 4));
                                continue;
                            }
                        }
                        continue;
                    } else {
                        this.mutablePos1.set(offX, offY, offZ);
                        int flags = 0;
                        if (((LightAccessBlockState)blockState).isConditionallyFullOpaque()) {
                            final VoxelShape cullingFace = blockState.getCullingFace(world, this.mutablePos1, propagate.getOpposite().nms);

                            if (VoxelShapes.unionCoversFullCube(fromShape, cullingFace)) {
                                continue;
                            }
                            flags |= FLAG_HAS_SIDED_TRANSPARENT_BLOCKS;
                        }

                        final int opacity = blockState.getOpacity(world, this.mutablePos1);
                        final int targetLevel = propagatedLightLevel - Math.max(1, opacity);
                        if (targetLevel <= currentLevel) {
                            continue;
                        }
                        this.setLightLevel(sectionIndex, localIndex, targetLevel);
                        if (targetLevel > 1) {
                            queue[queueLength++] =
                                    (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                            | (targetLevel << (6 + 6 + 9))
                                            | (propagate.ordinal() << (6 + 6 + 9 + 4))
                                            | (flags);
                        }
                        continue;
                    }
                }
            }
        }
    }

    protected final void performLightDecrease(final ChunkProvider lightAccess) {
        final BlockView world = lightAccess.getWorld();
        final int[] queue = this.decreaseQueue;
        final int[] increaseQueue = this.increaseQueue;
        int queueReadIndex = 0;
        int queueLength = this.decreaseQueueInitialLength;
        this.decreaseQueueInitialLength = 0;
        int increaseQueueLength = this.increaseQueueInitialLength;
        final int decodeOffsetX = -this.encodeOffsetX;
        final int decodeOffsetY = -this.encodeOffsetY;
        final int decodeOffsetZ = -this.encodeOffsetZ;
        final int encodeOffset = this.coordinateOffset;
        final int sectionOffset = this.chunkSectionIndexOffset;
        final int emittedMask = this.emittedLightMask;

        while (queueReadIndex < queueLength) {
            final int queueValue = queue[queueReadIndex++];

            final int posX = ((queueValue & 63) + decodeOffsetX);
            final int posZ = (((queueValue >>> 6) & 63) + decodeOffsetZ);
            final int posY = (((queueValue >>> 12) & 511) + decodeOffsetY);
            final int propagatedLightLevel = ((queueValue >>> (6 + 6 + 9)) & 0xF);
            final int fromDirection = ((queueValue >>> (6 + 6 + 9 + 4)) & 0x1F);
            final AxisDirection[] checkDirections = OLD_CHECK_DIRECTIONS[fromDirection];

            if ((queueValue & FLAG_HAS_SIDED_TRANSPARENT_BLOCKS) == 0) {
                // we don't need to worry about our state here.
                for (final AxisDirection propagate : checkDirections) {
                    final int offX = posX + propagate.x;
                    final int offY = posY + propagate.y;
                    final int offZ = posZ + propagate.z;

                    final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
                    final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

                    final int lightLevel = this.getLightLevel(sectionIndex, localIndex);

                    if (lightLevel == 0) {
                        // already at lowest, nothing we can do
                        continue;
                    }

                    final BlockState blockState = this.getBlockState(sectionIndex, localIndex);
                    final int opacityCached = ((LightAccessBlockState)blockState).getOpacityIfCached();
                    // no null check, blockState cannot be null if level != 0
                    if (opacityCached != -1) {
                        final int targetLevel = Math.max(0, propagatedLightLevel - Math.max(1, opacityCached));
                        if (lightLevel > targetLevel) {
                            // it looks like another source propagated here, so re-propagate it
                            increaseQueue[increaseQueueLength++] =
                                    (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                            | (lightLevel << (6 + 6 + 9))
                                            | ((propagate.ordinal() | 8) << (6 + 6 + 9 + 4))
                                            | FLAG_RECHECK_LEVEL;
                            continue;
                        }
                        final int emittedLight = blockState.getLuminance() & emittedMask;
                        if (emittedLight != 0) {
                            // re-propagate source
                            increaseQueue[increaseQueueLength++] =
                                    (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                            | (emittedLight << (6 + 6 + 9))
                                            | ((propagate.ordinal() | 8) << (6 + 6 + 9 + 4))
                                            | (((LightAccessBlockState)blockState).isConditionallyFullOpaque() ? FLAG_HAS_SIDED_TRANSPARENT_BLOCKS : 0);
                        }
                        this.setLightLevel(sectionIndex, localIndex, emittedLight);
                        if (targetLevel > 0) { // we actually need to propagate 0 just in case we find a neighbour...
                            queue[queueLength++] =
                                    (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                            | (targetLevel << (6 + 6 + 9))
                                            | (propagate.ordinal() << (6 + 6 + 9 + 4));
                            continue;
                        }
                        continue;
                    } else {
                        this.mutablePos1.set(offX, offY, offZ);
                        int flags = 0;
                        if (((LightAccessBlockState)blockState).isConditionallyFullOpaque()) {
                            final VoxelShape cullingFace = blockState.getCullingFace(world, this.mutablePos1, propagate.getOpposite().nms);

                            if (VoxelShapes.unionCoversFullCube(VoxelShapes.empty(), cullingFace)) {
                                continue;
                            }
                            flags |= FLAG_HAS_SIDED_TRANSPARENT_BLOCKS;
                        }

                        final int opacity = blockState.getOpacity(world, this.mutablePos1);
                        final int targetLevel = Math.max(0, propagatedLightLevel - Math.max(1, opacity));
                        if (lightLevel > targetLevel) {
                            // it looks like another source propagated here, so re-propagate it
                            increaseQueue[increaseQueueLength++] =
                                    (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                            | (lightLevel << (6 + 6 + 9))
                                            | ((propagate.ordinal() | 8) << (6 + 6 + 9 + 4))
                                            | (FLAG_RECHECK_LEVEL | flags);
                            continue;
                        }
                        final int emittedLight = blockState.getLuminance() & emittedMask;
                        if (emittedLight != 0) {
                            // re-propagate source
                            increaseQueue[increaseQueueLength++] =
                                    (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                            | (emittedLight << (6 + 6 + 9))
                                            | ((propagate.ordinal() | 8) << (6 + 6 + 9 + 4))
                                            | flags;
                        }
                        this.setLightLevel(sectionIndex, localIndex, emittedLight);
                        if (targetLevel > 0) {
                            queue[queueLength++] =
                                    (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                            | (targetLevel << (6 + 6 + 9))
                                            | (propagate.ordinal() << (6 + 6 + 9 + 4))
                                            | flags;
                        }
                        continue;
                    }
                }
            } else {
                // we actually need to worry about our state here
                final BlockState fromBlock = this.getBlockState(posX, posY, posZ);
                this.mutablePos2.set(posX, posY, posZ);
                for (final AxisDirection propagate : checkDirections) {
                    final int offX = posX + propagate.x;
                    final int offY = posY + propagate.y;
                    final int offZ = posZ + propagate.z;

                    final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
                    final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

                    final VoxelShape fromShape = (((LightAccessBlockState)fromBlock).isConditionallyFullOpaque()) ? fromBlock.getCullingFace(world, this.mutablePos2, propagate.nms) : VoxelShapes.empty();

                    if (fromShape != VoxelShapes.empty() && VoxelShapes.unionCoversFullCube(VoxelShapes.empty(), fromShape)) {
                        continue;
                    }

                    final int lightLevel = this.getLightLevel(sectionIndex, localIndex);

                    if (lightLevel == 0) {
                        // already at lowest, nothing we can do
                        continue;
                    }

                    final BlockState blockData = this.getBlockState(sectionIndex, localIndex);
                    final int opacityCached = ((LightAccessBlockState)blockData).getOpacityIfCached();
                    // no null check, blockData cannot be null if level != 0
                    if (opacityCached != -1) {
                        final int targetLevel = Math.max(0, propagatedLightLevel - Math.max(1, opacityCached));
                        if (lightLevel > targetLevel) {
                            // it looks like another source propagated here, so re-propagate it
                            increaseQueue[increaseQueueLength++] =
                                    (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                            | (lightLevel << (6 + 6 + 9))
                                            | ((propagate.ordinal() | 8) << (6 + 6 + 9 + 4))
                                            | FLAG_RECHECK_LEVEL;
                            continue;
                        }
                        final int emittedLight = blockData.getLuminance() & emittedMask;
                        if (emittedLight != 0) {
                            // re-propagate source
                            increaseQueue[increaseQueueLength++] =
                                    (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                            | (emittedLight << (6 + 6 + 9))
                                            | ((propagate.ordinal() | 8) << (6 + 6 + 9 + 4))
                                            | (((LightAccessBlockState)blockData).isConditionallyFullOpaque() ? FLAG_HAS_SIDED_TRANSPARENT_BLOCKS : 0);
                        }
                        this.setLightLevel(sectionIndex, localIndex, emittedLight);
                        if (targetLevel > 0) { // we actually need to propagate 0 just in case we find a neighbour...
                            queue[queueLength++] =
                                    (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                            | (targetLevel << (6 + 6 + 9))
                                            | (propagate.ordinal() << (6 + 6 + 9 + 4));
                            continue;
                        }
                        continue;
                    } else {
                        this.mutablePos1.set(offX, offY, offZ);
                        int flags = 0;
                        if (((LightAccessBlockState)blockData).isConditionallyFullOpaque()) {
                            final VoxelShape cullingFace = blockData.getCullingFace(world, this.mutablePos1, propagate.getOpposite().nms);

                            if (VoxelShapes.unionCoversFullCube(fromShape, cullingFace)) {
                                continue;
                            }
                            flags |= FLAG_HAS_SIDED_TRANSPARENT_BLOCKS;
                        }

                        final int opacity = blockData.getOpacity(world, this.mutablePos1);
                        final int targetLevel = Math.max(0, propagatedLightLevel - Math.max(1, opacity));
                        if (lightLevel > targetLevel) {
                            // it looks like another source propagated here, so re-propagate it
                            increaseQueue[increaseQueueLength++] =
                                    (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                            | (lightLevel << (6 + 6 + 9))
                                            | ((propagate.ordinal() | 8) << (6 + 6 + 9 + 4))
                                            | (FLAG_RECHECK_LEVEL | flags);
                            continue;
                        }
                        final int emittedLight = blockData.getLuminance() & emittedMask;
                        if (emittedLight != 0) {
                            // re-propagate source
                            increaseQueue[increaseQueueLength++] =
                                    (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                            | (emittedLight << (6 + 6 + 9))
                                            | ((propagate.ordinal() | 8) << (6 + 6 + 9 + 4))
                                            | flags;
                        }
                        this.setLightLevel(sectionIndex, localIndex, emittedLight);
                        if (targetLevel > 0) { // we actually need to propagate 0 just in case we find a neighbour...
                            queue[queueLength++] =
                                    (offX + (offZ << 6) + (offY << 12) + encodeOffset)
                                            | (targetLevel << (6 + 6 + 9))
                                            | (propagate.ordinal() << (6 + 6 + 9 + 4))
                                            | flags;
                        }
                        continue;
                    }
                }
            }
        }

        // propagate sources we clobbered
        this.increaseQueueInitialLength = increaseQueueLength;
        this.performLightIncrease(lightAccess);
    }

}

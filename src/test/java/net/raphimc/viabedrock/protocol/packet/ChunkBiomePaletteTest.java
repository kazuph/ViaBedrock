package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.minecraft.chunks.PaletteType;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.api.chunk.section.BedrockChunkSection;
import net.raphimc.viabedrock.api.chunk.section.BedrockChunkSectionImpl;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.Test;

import static org.junit.Assert.*;

public class ChunkBiomePaletteTest {

    @Test
    public void omittedBlockSectionsStillConsumeEveryBiomeBeforeBlockEntities() {
        final var buffer = Unpooled.buffer();
        try {
            final BedrockChunkSection[] sections = new BedrockChunkSection[24];
            for (int i = 0; i < sections.length; i++) {
                sections[i] = new BedrockChunkSectionImpl(true);
                if (i == 0) {
                    buffer.writeByte(1); // singleton runtime biome palette
                    BedrockTypes.VAR_INT.write(buffer, 24);
                } else {
                    buffer.writeByte(254); // inherit previous biome
                }
            }
            buffer.writeByte(0); // border blocks
            final CompoundTag chest = new CompoundTag();
            chest.putString("id", "Chest");
            chest.putInt("pairx", 243);
            BedrockTypes.NETWORK_TAG.write(buffer, chest);
            WorldPackets.readBiomePalettes(buffer, sections);
            assertEquals(0, buffer.readByte());
            assertEquals(chest, BedrockTypes.NETWORK_TAG.read(buffer));
            assertFalse(buffer.isReadable());
            for (final var section : sections) {
                assertEquals(24, section.palette(PaletteType.BIOMES).idAt(0));
            }
            sections[0].palette(PaletteType.BIOMES).setIdAt(0, 1);
            assertEquals(24, sections[1].palette(PaletteType.BIOMES).idAt(0));
        } finally {
            buffer.release();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void firstBiomeCannotInheritMissingPalette() {
        final var buffer = Unpooled.wrappedBuffer(new byte[]{(byte) 254});
        try {
            WorldPackets.readBiomePalettes(buffer, new BedrockChunkSection[]{new BedrockChunkSectionImpl(true)});
        } finally {
            buffer.release();
        }
    }

}

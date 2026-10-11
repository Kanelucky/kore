package org.koremc.protocol

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.BiomeDefinition
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemVersion
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PacketsTest {
    @Test
    fun `request network settings carries a big-endian protocol`() {
        // ID 193 as a varuint, then 2193 as a big-endian int32.
        val packet = decodeClientPacket(bytes(0xC1, 0x01, 0x00, 0x00, 0x08, 0x91))

        assertEquals(RequestNetworkSettings(protocol = 2193), packet)
    }

    @Test
    fun `sub-client bits in the header are ignored`() {
        val header = 193 or (1 shl 10) or (2 shl 12)
        val packet = decodeClientPacket(bytes((header and 0x7F) or 0x80, header shr 7, 0, 0, 0x08, 0x91))

        assertEquals(RequestNetworkSettings(protocol = 2193), packet)
    }

    @Test
    fun `login carries a protocol and a connection request`() {
        val login = assertIs<Login>(decodeClientPacket(bytes(0x01, 0x00, 0x00, 0x08, 0x91, 0x03, 0x0A, 0x0B, 0x0C)))

        assertEquals(2193, login.protocol)
        assertContentEquals(bytes(0x0A, 0x0B, 0x0C), login.connectionRequest)
    }

    @Test
    fun `network settings encode little-endian`() {
        val encoded = NetworkSettings(compressionThreshold = 256).encode()

        // ID 143, threshold 256, zlib, throttle off, threshold 0, scalar 0.0f.
        assertContentEquals(bytes(0x8F, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), encoded)
    }

    @Test
    fun `play status encodes big-endian`() {
        assertContentEquals(bytes(0x02, 0x00, 0x00, 0x00, 0x01), PlayStatus(PlayStatus.Status.LOGIN_FAILED_CLIENT_OLD).encode())
        assertContentEquals(bytes(0x02, 0x00, 0x00, 0x00, 0x02), PlayStatus(PlayStatus.Status.LOGIN_FAILED_SERVER_OLD).encode())
    }

    @Test
    fun `disconnect zigzags its reason and marks whether messages follow`() {
        assertContentEquals(bytes(0x05, 0x5C, 0x01), Disconnect(Disconnect.Reason.NOT_AUTHENTICATED).encode())
        assertContentEquals(
            bytes(0x05, 0x6E, 0x00, 0x02, 'H'.code, 'i'.code, 0x02, 'H'.code, 'i'.code),
            Disconnect(Disconnect.Reason.KICKED, message = "Hi").encode(),
        )
    }

    @Test
    fun `an empty resource pack list is four flags, a blank template and no packs`() {
        val expected = bytes(0x06, 0x00, 0x00, 0x00, 0x00) + ByteArray(16) + bytes(0x00, 0x00)

        assertContentEquals(expected, ResourcePacksInfo().encode())
    }

    @Test
    fun `the pack stack counts experiments with a plain uint32`() {
        assertContentEquals(bytes(0x07, 0x00, 0x00, 0x01, '*'.code, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), ResourcePackStack().encode())
        assertContentEquals(
            bytes(0x07, 0x00, 0x00, 0x01, '*'.code, 0x01, 0x00, 0x00, 0x00) + bytes(0x07) + "cameras".toByteArray() + bytes(0x01, 0x01, 0x00),
            ResourcePackStack(experiments = mapOf("cameras" to true)).encode(),
        )
    }

    @Test
    fun `resource pack responses carry a tag and its name`() {
        val finished = bytes(0x08, 0x02, 19) + "downloadingfinished".toByteArray()
        val downloading = bytes(0x08, 0x01, 11) + "downloading".toByteArray() + bytes(0x01, 0x03) + "a-b".toByteArray()

        assertEquals(ResourcePackClientResponse(ResourcePackClientResponse.Response.DOWNLOADING_FINISHED), decodeClientPacket(finished))
        assertEquals(ResourcePackClientResponse(ResourcePackClientResponse.Response.DOWNLOADING, listOf("a-b")), decodeClientPacket(downloading))
        assertFailsWith<PacketException> { decodeClientPacket(bytes(0x08, 0x09, 0x00)) }
    }

    @Test
    fun `client cache status is a single flag`() {
        assertEquals(ClientCacheStatus(supported = true), decodeClientPacket(bytes(0x81, 0x01, 0x01)))
    }

    @Test
    fun `an empty item registry is just its count`() {
        assertContentEquals(bytes(0xA2, 0x01, 0x00), ItemRegistry(emptyList()).encode())
    }

    @Test
    fun `item registry entries carry a 16-bit ID, a zigzagged version and network NBT`() {
        val food = NbtMap.builder().putCompound("minecraft:food", NbtMap.builder().putInt("nutrition", 4).build()).build()
        val registry = ItemRegistry(
            listOf(
                ItemDefinition("minecraft:air", -158, componentBased = false, ItemVersion.NONE),
                ItemDefinition("minecraft:apple", 882, componentBased = true, ItemVersion.DATA_DRIVEN, NbtMap.builder().putCompound("components", food).build()),
            ),
        )

        val expected = bytes(0xA2, 0x01, 0x02) +
            // -158 as a little-endian int16, not component based, version 2 zigzagged, and an empty compound.
            bytes(13) + "minecraft:air".toByteArray() + bytes(0x62, 0xFF, 0x00, 0x04, 0x0A, 0x00, 0x00) +
            bytes(15) + "minecraft:apple".toByteArray() + bytes(0x72, 0x03, 0x01, 0x02) +
            // Network NBT names its tags with varuint lengths, and zigzags ints: 4 becomes 8.
            bytes(0x0A, 0x00, 0x0A, 10) + "components".toByteArray() + bytes(0x0A, 14) + "minecraft:food".toByteArray() +
            bytes(0x03, 9) + "nutrition".toByteArray() + bytes(0x08, 0x00, 0x00, 0x00)

        assertContentEquals(expected, registry.encode())
    }

    @Test
    fun `biome definitions share one string list for names and tags`() {
        val water = 0xA5112233.toInt()
        val list = BiomeDefinitionList(
            listOf(
                BiomeDefinition("a", id = null, 0.5f, 0.25f, 0f, 1f, 2f, water, rain = true, tags = listOf("ocean", "deep")),
                BiomeDefinition("b", id = 7, 0.5f, 0.25f, 0f, 1f, 2f, water, rain = false, tags = listOf("ocean")),
            ),
        )

        // Temperature 0.5, downfall 0.25, foliage snow 0, depth 1 and scale 2, as little-endian floats.
        val climate = bytes(0x00, 0x00, 0x00, 0x3F, 0x00, 0x00, 0x80, 0x3E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80, 0x3F, 0x00, 0x00, 0x00, 0x40)
        val expected = bytes(0x7A, 0x02) +
            // Name "a" is string 0, and a biome without an ID sends -1. Tags "ocean" and "deep" are strings 1 and 2.
            bytes(0x00, 0x00, 0xFF, 0xFF) + climate + bytes(0x33, 0x22, 0x11, 0xA5, 0x01) + bytes(0x01, 0x02, 0x01, 0x00, 0x02, 0x00) + bytes(0x00) +
            // "b" is string 3, and its "ocean" tag reuses string 1.
            bytes(0x03, 0x00, 0x07, 0x00) + climate + bytes(0x33, 0x22, 0x11, 0xA5, 0x00) + bytes(0x01, 0x01, 0x01, 0x00) + bytes(0x00) +
            bytes(0x04, 0x01) + "a".toByteArray() + bytes(0x05) + "ocean".toByteArray() + bytes(0x04) + "deep".toByteArray() + bytes(0x01) + "b".toByteArray()

        assertContentEquals(expected, list.encode())
    }

    @Test
    fun `the client names its player's runtime ID once it has spawned`() {
        assertEquals(SetLocalPlayerAsInitialised(runtimeId = 300), decodeClientPacket(bytes(0x71, 0xAC, 0x02)))
    }

    @Test
    fun `jigsaw structure data is a compound of four empty lists`() {
        val list = { name: String -> bytes(0x09, name.length) + name.toByteArray() + bytes(0x0A, 0x00) } // Compounds, none of them

        val expected = bytes(0xB9, 0x02, 0x0A, 0x00) +
            list("jigsaws") + list("processors") + list("structure_sets") + list("template_pools") + bytes(0x00)

        assertContentEquals(expected, JigsawStructureData.encode())
    }

    @Test
    fun `empty voxel shapes are two empty lists and a zero count`() {
        assertContentEquals(bytes(0xD1, 0x02, 0x00, 0x00, 0x00, 0x00), VoxelShapes.encode())
    }

    @Test
    fun `an empty creative inventory is two empty lists`() {
        assertContentEquals(bytes(0x91, 0x01, 0x00, 0x00), CreativeContent.encode())
    }

    @Test
    fun `chunk radius packets zigzag the radius`() {
        assertEquals(RequestChunkRadius(radius = 16, maxRadius = 16), decodeClientPacket(bytes(0x45, 0x20, 0x10)))
        assertContentEquals(bytes(0x46, 0x10), ChunkRadiusUpdated(8).encode())
    }

    @Test
    fun `packets Kore doesn't decode yet pass through`() {
        val packet = assertIs<UnhandledPacket>(decodeClientPacket(bytes(0x05, 0x01, 0x02)))

        assertEquals(5, packet.id)
        assertContentEquals(bytes(0x01, 0x02), packet.payload)
    }

    @Test
    fun `truncated packets are rejected`() {
        assertFailsWith<PacketException> { decodeClientPacket(bytes(0xC1, 0x01, 0x00)) }
        assertFailsWith<PacketException> { decodeClientPacket(bytes(0x01, 0x00, 0x00, 0x08, 0x91, 0x05, 0x01)) }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}

package org.koremc.protocol

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufInputStream
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtUtils
import org.koremc.world.BlockPos

private const val OPEN_SIGN = 303
internal const val ITEM_FRAME_DROP_ITEM = 71

/** Opens the editor for the sign at [position], on its front, or its back. */
data class OpenSign(val position: BlockPos, val front: Boolean) : ServerPacket {
    override val id get() = OPEN_SIGN

    override fun encodeBody(out: ByteBuf) {
        out.writeBlockPos(position)
        out.writeBoolean(front)
    }
}

/** The data the client says the block at [position] keeps now, as a player has written it, like a sign's text, as network NBT. */
data class EditedBlockData(val position: BlockPos, val data: NbtMap) : ClientPacket

internal fun decodeEditedBlockData(buffer: ByteBuf) =
    EditedBlockData(buffer.readBlockPos(), NbtUtils.createNetworkReader(ByteBufInputStream(buffer)).readTag() as NbtMap)

/** The player hitting the item frame at [position], which knocks its item out. */
data class ItemFrameDropItem(val position: BlockPos) : ClientPacket

internal fun decodeItemFrameDropItem(buffer: ByteBuf) = ItemFrameDropItem(buffer.readBlockPos())

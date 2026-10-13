package org.koremc.protocol

import io.netty.buffer.ByteBuf
import org.koremc.world.BlockPos

internal const val BOOK_EDIT = 97
internal const val LECTERN_UPDATE = 125

/**
 * A player reading the book on the lectern at [position] turning to
 * [page], of [totalPages]. The client shows two pages at once, and [page]
 * is the left one's, counting from 0.
 */
data class LecternUpdate(val page: Int, val totalPages: Int, val position: BlockPos) : ClientPacket

internal fun decodeLecternUpdate(buffer: ByteBuf) = LecternUpdate(buffer.readUnsignedByte().toInt(), buffer.readUnsignedByte().toInt(), buffer.readBlockPos())

/**
 * A player writing in the book and quill in [slot] of their inventory, or
 * signing it, which makes it a written book: [action] says which.
 */
data class BookEdit(val slot: Int, val action: BookAction) : ClientPacket

/** What a player did to a book they're writing in. Pages count from 0. */
sealed interface BookAction {
    /** The text on [page] is now [text], with a photo called [photo], or "" for none. */
    data class Replace(val page: Int, val text: String, val photo: String) : BookAction

    /** A new [page], with [text] and a [photo], goes in before the page there. */
    data class Add(val page: Int, val text: String, val photo: String) : BookAction

    data class Delete(val page: Int) : BookAction

    data class Swap(val page: Int, val other: Int) : BookAction

    /** The book is signed, with [title], by [author], whom the player names as they like, and the player's [xuid]. */
    data class Sign(val title: String, val author: String, val xuid: String) : BookAction
}

internal fun decodeBookEdit(buffer: ByteBuf): BookEdit {
    val slot = buffer.readVarInt()
    val action = when (val type = buffer.readVarUInt()) {
        0 -> BookAction.Replace(buffer.readVarInt(), buffer.readString(), buffer.readString())
        1 -> BookAction.Add(buffer.readVarInt(), buffer.readString(), buffer.readString())
        2 -> BookAction.Delete(buffer.readVarInt())
        3 -> BookAction.Swap(buffer.readVarInt(), buffer.readVarInt())
        4 -> BookAction.Sign(buffer.readString(), buffer.readString(), buffer.readString())
        else -> throw PacketException("unknown book edit $type")
    }
    return BookEdit(slot, action)
}

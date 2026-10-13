package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.koremc.inventory.SlotStack
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks

internal const val LECTERN = "minecraft:lectern"
private const val POWERED = "powered_bit"

internal const val WRITABLE_BOOK = "minecraft:writable_book"
internal const val WRITTEN_BOOK = "minecraft:written_book"

/** Where a book's item keeps its pages, each with its text and the name of any photo on it. */
internal const val PAGES = "pages"

/** How long a lectern gives power for when a page turns, in ticks. */
private const val PAGE_PULSE = 2

/** The sound of a book going on a lectern. */
const val BOOK_PUT = "item.book.put"

val BlockState.isLectern get() = name == LECTERN

internal val BlockState.lecternPowered get() = isLectern && properties[POWERED] == 1.toByte()

/** Whether a lectern holds [stack]: a book and quill, or a written book. */
val ItemStack.goesOnLectern get() = item.name == WRITABLE_BOOK || item.name == WRITTEN_BOOK

/** How many pages the book [stack] has written in it. */
internal val ItemStack.pageCount get() = nbt.getList(PAGES, NbtType.COMPOUND)?.size ?: 0

/** The book on the lectern at [pos], if there's a lectern with one. */
fun FlatWorld.lecternBook(pos: BlockPos): ItemStack? = if (stateAt(pos)?.isLectern == true) lecternAt(pos)[0]?.stack else null

/**
 * Puts one of [book] on the lectern at [pos], open at its first page, if
 * the lectern has none and it's a book a lectern holds. Returns whether it
 * went on.
 */
fun FlatWorld.putOnLectern(pos: BlockPos, book: ItemStack): Boolean {
    if (stateAt(pos)?.isLectern != true || !book.goesOnLectern || lecternAt(pos)[0] != null) return false
    lecternAt(pos)[0] = SlotStack(book.copy(count = 1), newStackId())
    showLectern(pos, page = 0, totalPages = book.pageCount)
    return true
}

/** Takes the book off the lectern at [pos], and returns it, if it had one. */
fun FlatWorld.takeFromLectern(pos: BlockPos): ItemStack? {
    val book = lecternBook(pos) ?: return null
    lecternAt(pos)[0] = null
    showLectern(pos, page = 0, totalPages = 0)
    return book
}

/**
 * Knocks the book off the lectern at [pos], as punching it does in
 * Bedrock: it drops on top, a little out of the lectern's front. Returns
 * whether it had one.
 */
fun FlatWorld.knockBookOff(pos: BlockPos): Boolean {
    val state = stateAt(pos) ?: return false
    val book = takeFromLectern(pos) ?: return false
    val front = Face.entries.find { it.id == state.properties[CARDINAL] } ?: Face.SOUTH
    val at = Vec3(pos.x + 0.5f + BOOK_OUT * front.dx, pos.y + 1f, pos.z + 0.5f + BOOK_OUT * front.dz)
    spawnItem(book, at, Vec3(0f, 0f, 0f), DROPPED_PICKUP_DELAY)
    return true
}

/** How far out of a lectern's front a book knocked off it drops. */
private const val BOOK_OUT = 0.25f

/**
 * Turns the book on the lectern at [pos] to [page], of [totalPages], as a
 * player reading it does, which powers everything around the lectern, and
 * the block under it strongly, for two ticks. Returns whether it turned.
 */
fun FlatWorld.turnPage(pos: BlockPos, page: Int, totalPages: Int): Boolean {
    val state = stateAt(pos)?.takeIf { it.isLectern } ?: return false
    if (lecternBook(pos) == null) return false
    showLectern(pos, page.coerceIn(0, maxOf(totalPages - 1, 0)), totalPages)
    if (!state.lecternPowered) blocks.find(LECTERN, state.properties + (POWERED to bit(true)))?.let { set(pos, it) }
    if (redstoneDue.add(pos)) schedule(pos, PAGE_PULSE, BlockUpdate.LECTERN)
    return true
}

/** Ends the pulse of the lectern at [pos], [state], that a page turning started. Returns whether it changed. */
internal fun FlatWorld.endPagePulse(pos: BlockPos, state: BlockState): Boolean {
    if (!state.isLectern) return false
    redstoneDue -= pos
    if (!state.lecternPowered) return false
    blocks.find(LECTERN, state.properties + (POWERED to bit(false)))?.let { set(pos, it) } ?: return false
    return true
}

/**
 * The power a comparator reads from the lectern at [pos]: none without a
 * book, and with one, more the further through it the reader is, as
 * Bedrock reckons it, by the pair of pages open. Bedrock counts a book's
 * last page only if it makes a pair, and a book with too few pages to
 * turn, from the start, reads as full power.
 */
internal fun FlatWorld.lecternPower(pos: BlockPos): Int {
    if (lecternBook(pos) == null) return 0
    val data = blockEntityAt(pos)
    val total = data?.getInt("totalPages") ?: 0
    val page = data?.getInt("page") ?: 0
    val pairs = total - total % 2
    // The right page open, counting from 1, or else the left one's less one.
    val reading = if (page + 2 <= total) page + 2 else page
    return if (pairs <= 0) MAX_POWER else minOf(MAX_POWER, 1 + (MAX_POWER - 1) * reading / pairs)
}

/** Gives the lectern at [pos] the block entity the client draws its book by: the book, if any, and the page it's open at. */
private fun FlatWorld.showLectern(pos: BlockPos, page: Int, totalPages: Int) {
    val book = lecternAt(pos)[0]?.stack
    val data = NbtMap.builder().putString("id", "Lectern").putBoolean("hasBook", book != null)
    if (book != null) data.putCompound("book", ItemStacks.nbtOf(book, 0)).putInt("page", page).putInt("totalPages", totalPages)
    setBlockEntity(pos, data.build())
}

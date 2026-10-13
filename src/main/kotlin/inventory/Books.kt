package org.koremc.inventory

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.koremc.protocol.BookAction
import org.koremc.registry.ItemStack
import org.koremc.world.PAGES
import java.util.Collections

/**
 * The most pages a book holds, characters a page holds, and characters in
 * a written book's title or author, as in Bedrock.
 */
private const val MAX_PAGES = 50
private const val MAX_PAGE_LENGTH = 256
private const val MAX_NAME_LENGTH = 16

/**
 * The book and quill [book] with [action] done to it: a page written,
 * added, taken out or swapped with another. Signed, it becomes [written],
 * a written book with [book]'s pages, its title and author, and the [xuid]
 * of the player who signed it. Null for what Bedrock's limits don't allow.
 */
fun writeInBook(book: ItemStack, action: BookAction, written: ItemStack, xuid: String): ItemStack? {
    val pages = book.nbt.getList(PAGES, NbtType.COMPOUND)?.toMutableList() ?: mutableListOf()
    fun page(text: String, photo: String) = NbtMap.builder().putString("text", text).putString("photoname", photo).build()
    when (action) {
        is BookAction.Replace -> {
            if (action.page !in 0 until MAX_PAGES || action.text.length > MAX_PAGE_LENGTH) return null
            while (pages.size <= action.page) pages += page("", "")
            pages[action.page] = page(action.text, action.photo)
        }
        is BookAction.Add -> {
            if (action.page !in 0..pages.size || pages.size >= MAX_PAGES || action.text.length > MAX_PAGE_LENGTH) return null
            pages.add(action.page, page(action.text, action.photo))
        }
        is BookAction.Delete -> {
            if (action.page !in pages.indices) return null
            pages.removeAt(action.page)
        }
        is BookAction.Swap -> {
            if (action.page !in pages.indices || action.other !in pages.indices) return null
            Collections.swap(pages, action.page, action.other)
        }
        is BookAction.Sign -> {
            if (action.title.length > MAX_NAME_LENGTH || action.author.length > MAX_NAME_LENGTH) return null
            val tag = book.nbt.toBuilder().putList(PAGES, NbtType.COMPOUND, pages)
                .putString("title", action.title).putString("author", action.author).putString("xuid", xuid).putInt("generation", 0)
            return written.copy(count = book.count, nbt = tag.build())
        }
    }
    return book.copy(nbt = book.nbt.toBuilder().putList(PAGES, NbtType.COMPOUND, pages).build())
}

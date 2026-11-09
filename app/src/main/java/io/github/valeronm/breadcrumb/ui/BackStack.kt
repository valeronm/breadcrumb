package io.github.valeronm.breadcrumb.ui

/**
 * The pages open over the tabs, bottom first: back reaches [top], and an entry draws over every
 * entry before it. A closed entry stays as [Entry.leaving] until [closed], since it keeps drawing
 * while it recedes.
 */
internal data class BackStack<D : Any>(
    val entries: List<Entry<D>> = emptyList(),
    /** The id the next opened entry takes; ids are never reused. */
    val nextId: Long = 0,
) {
    data class Entry<D : Any>(val id: Long, val destination: D, val leaving: Boolean = false)

    /** The entry back reaches: the topmost one not closing. */
    val top: Entry<D>? get() = entries.lastOrNull { !it.leaving }

    fun push(destination: D): BackStack<D> = BackStack(entries + Entry(nextId, destination), nextId + 1)

    /** Closes [id] and every entry over it; an id the stack does not hold changes nothing. */
    fun close(id: Long): BackStack<D> {
        val at = entries.indexOfFirst { it.id == id }
        if (at < 0) return this
        return copy(entries = entries.mapIndexed { i, entry -> if (i >= at) entry.copy(leaving = true) else entry })
    }

    /** Closes [id] if back reaches it; a covered entry is removed at once, the entries over it left open. */
    fun dismiss(id: Long): BackStack<D> {
        val entry = entries.firstOrNull { it.id == id } ?: return this
        return when {
            entry.leaving -> this
            entry == top -> close(id)
            else -> copy(entries = entries - entry)
        }
    }

    fun clear(): BackStack<D> = copy(entries = entries.map { it.copy(leaving = true) })

    /** [id]'s close has finished; an entry still open stays. */
    fun closed(id: Long): BackStack<D> = copy(entries = entries.filterNot { it.id == id && it.leaving })

    /** Changes [id]'s destination with its identity, and so its composition, kept. */
    fun update(id: Long, transform: (D) -> D): BackStack<D> =
        copy(entries = entries.map { if (it.id == id) it.copy(destination = transform(it.destination)) else it })

    /** Closes [id] onto a new entry opened directly beneath it. */
    fun closeOnto(id: Long, destination: D): BackStack<D> {
        val at = entries.indexOfFirst { it.id == id }
        if (at < 0 || entries[at].leaving) return this
        val opened = entries.take(at) + Entry(nextId, destination) + entries.drop(at)
        return BackStack(opened, nextId + 1).close(id)
    }
}

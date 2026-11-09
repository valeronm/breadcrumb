package io.github.valeronm.breadcrumb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackStackTest {

    private fun BackStack<String>.drawn() = entries.map { it.destination }
    private fun BackStack<String>.leaving() = entries.filter { it.leaving }.map { it.destination }

    @Test fun `back reaches the newest page`() {
        val stack = BackStack<String>().push("place").push("gap")

        assertEquals("gap", stack.top?.destination)
        assertEquals(listOf("place", "gap"), stack.drawn())
    }

    @Test fun `a page reached twice is two entries`() {
        val stack = BackStack<String>().push("place").push("gap").push("place")

        assertEquals(3, stack.entries.map { it.id }.distinct().size)
        assertEquals("gap", stack.close(stack.top!!.id).top?.destination)
    }

    @Test fun `closing a page closes everything over it`() {
        val stack = BackStack<String>().push("place").push("editor").push("merge")
        val place = stack.entries.first().id

        val closed = stack.close(place)

        assertNull(closed.top)
        assertEquals(listOf("place", "editor", "merge"), closed.leaving())
    }

    @Test fun `a closed page keeps drawing until its close finishes, out of back's reach`() {
        val stack = BackStack<String>().push("place").push("gap")
        val gap = stack.top!!.id

        val closing = stack.close(gap)

        assertEquals(listOf("place", "gap"), closing.drawn())
        assertEquals("place", closing.top?.destination)
        assertEquals(listOf("place"), closing.closed(gap).drawn())
    }

    @Test fun `a finished close reported for an open page drops nothing`() {
        val stack = BackStack<String>().push("place")

        assertEquals(stack, stack.closed(stack.top!!.id))
    }

    @Test fun `closing an id the stack does not hold changes nothing`() {
        val stack = BackStack<String>().push("place")

        assertEquals(stack, stack.close(99))
        assertEquals(stack, stack.closeOnto(99, "gap"))
    }

    @Test fun `a landing closes every page`() {
        val cleared = BackStack<String>().push("journey").push("place").clear()

        assertNull(cleared.top)
        assertEquals(listOf("journey", "place"), cleared.leaving())
    }

    @Test fun `an update keeps the entry's identity, place and state`() {
        val stack = BackStack<String>().push("cluster:3").push("gap")
        val place = stack.entries.first()
        val gap = stack.top!!.id

        val rekeyed = stack.close(gap).update(place.id) { "place:7" }

        assertEquals(place.copy(destination = "place:7"), rekeyed.entries.first())
        assertTrue(rekeyed.entries.last().leaving)
        assertEquals("gap", rekeyed.entries.last().destination)
    }

    @Test fun `an update to an id the stack does not hold changes nothing`() {
        val stack = BackStack<String>().push("place")

        assertEquals(stack, stack.update(99) { "x" })
    }

    @Test fun `closing onto a page opens it beneath, where back lands`() {
        val stack = BackStack<String>().push("stay").push("namer")
        val namer = stack.top!!.id
        val opened = stack.nextId

        val saved = stack.closeOnto(namer, "place")

        assertEquals(listOf("stay", "place", "namer"), saved.drawn())
        assertEquals(opened, saved.top?.id)
        assertEquals(listOf("namer"), saved.leaving())
    }

    @Test fun `closing onto a page already closing opens nothing`() {
        val stack = BackStack<String>().push("stay").push("namer")
        val namer = stack.top!!.id

        val saved = stack.closeOnto(namer, "place")

        assertEquals(saved, saved.closeOnto(namer, "place"))
    }

    @Test fun `a page dismissing itself from the top closes`() {
        val stack = BackStack<String>().push("place").push("gap")

        val dismissed = stack.dismiss(stack.top!!.id)

        assertEquals("place", dismissed.top?.destination)
        assertEquals(listOf("gap"), dismissed.leaving())
    }

    @Test fun `a covered page dismissing itself leaves at once and the pages over it stay open`() {
        val stack = BackStack<String>().push("gap").push("place").push("stay")
        val gap = stack.entries.first().id

        val dismissed = stack.dismiss(gap)

        assertEquals(listOf("place", "stay"), dismissed.drawn())
        assertTrue(dismissed.leaving().isEmpty())
    }

    @Test fun `a closing page dismissing itself changes nothing`() {
        val stack = BackStack<String>().push("gap").push("place")
        val gap = stack.entries.first().id
        val closing = stack.close(gap)

        assertEquals(closing, closing.dismiss(gap))
    }

    @Test fun `ids are never reused`() {
        val stack = BackStack<String>().push("place")
        val first = stack.top!!.id

        val reopened = stack.close(first).closed(first).push("place")

        assertTrue(reopened.top!!.id != first)
    }
}

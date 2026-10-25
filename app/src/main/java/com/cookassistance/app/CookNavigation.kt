package com.cookassistance.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.navigation3.runtime.NavKey

/** Main-thread navigation actions. An outgoing entry must not mutate a newer destination. */
class CookNavigation(private val stack: MutableList<NavKey>) {
    var revision by mutableIntStateOf(0)
        private set

    fun back(expected: NavKey, expectedRevision: Int): Boolean = change(expected, expectedRevision) {
        if (stack.size <= 1) false else { stack.removeAt(stack.lastIndex); true }
    }

    fun open(expected: NavKey, expectedRevision: Int, destination: NavKey, replace: Boolean = false): Boolean =
        change(expected, expectedRevision) {
            if (replace) {
                if (stack.size <= 1) return@change false
                stack[stack.lastIndex] = destination
            } else stack.add(destination)
            true
        }

    fun root() {
        Snapshot.withMutableSnapshot {
            if (stack.size > 1) stack.subList(1, stack.size).clear()
            revision++
        }
    }

    private inline fun change(expected: NavKey, expectedRevision: Int, action: () -> Boolean): Boolean {
        if (revision != expectedRevision || stack.lastOrNull() != expected) return false
        return Snapshot.withMutableSnapshot {
            action().also { changed -> if (changed) revision++ }
        }
    }
}

package com.cookassistance.app

import androidx.compose.runtime.mutableStateListOf
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.*
import org.junit.Test

class CookNavigationTest {
    @Test fun activeExpandedSelectionReplacesDetailAndBackRestoresBrowse() {
        val stack = mutableStateListOf<NavKey>(Browse, Detail(2))
        val nav = CookNavigation(stack)
        assertTrue(nav.open(Detail(2), nav.revision, Detail(7), replace = true))
        assertEquals(listOf(Browse, Detail(7)), stack.toList())
        assertTrue(nav.open(Detail(7), nav.revision, Cooking(7)))
        assertTrue(nav.back(Cooking(7), nav.revision))
        assertEquals(Detail(7), stack.last())
        assertTrue(nav.back(Detail(7), nav.revision))
        assertEquals(listOf(Browse), stack.toList())
    }

    @Test fun outgoingCatalogCannotOpenRecipeOverCurrentCookingScreen() {
        val stack = mutableStateListOf<NavKey>(Browse)
        val nav = CookNavigation(stack)
        val catalogRevision = nav.revision
        nav.open(Browse, catalogRevision, Detail(2))
        nav.open(Detail(2), nav.revision, Cooking(2))
        assertFalse(nav.open(Browse, catalogRevision, Detail(7)))
        assertEquals(listOf(Browse, Detail(2), Cooking(2)), stack.toList())
    }

    @Test fun repeatedBackKeepsRootAndDoesNotAffectNextVisit() {
        val stack = mutableStateListOf<NavKey>(Browse, Detail(2))
        val nav = CookNavigation(stack)
        val oldRevision = nav.revision
        assertTrue(nav.back(Detail(2), oldRevision))
        assertFalse(nav.back(Detail(2), oldRevision))
        assertEquals(listOf(Browse), stack.toList())
        assertTrue(nav.open(Browse, nav.revision, Detail(2)))
        assertFalse(nav.back(Detail(2), oldRevision))
        assertEquals(listOf(Browse, Detail(2)), stack.toList())
    }

    @Test fun outgoingCookingBackCannotPopNewRecipeOrCancelItsSession() {
        val stack = mutableStateListOf<NavKey>(Browse, Detail(2), Cooking(2))
        val nav = CookNavigation(stack)
        val oldRevision = nav.revision
        nav.root()
        nav.open(Browse, nav.revision, Detail(7))
        nav.open(Detail(7), nav.revision, Cooking(7))
        var stopped = false
        if (nav.back(Cooking(2), oldRevision)) stopped = true
        assertFalse(stopped)
        assertEquals(Cooking(7), stack.last())
    }

    @Test fun tabResetAndRetainedExpandedSelectionKeepBrowse() {
        val stack = mutableStateListOf<NavKey>(Browse, Detail(2))
        val nav = CookNavigation(stack)
        val oldRevision = nav.revision
        nav.root()
        nav.root()
        assertFalse(nav.open(Detail(2), oldRevision, Detail(7), replace = true))
        assertFalse(nav.back(Browse, nav.revision))
        assertFalse(nav.open(Browse, nav.revision, Detail(7), replace = true))
        assertEquals(listOf(Browse), stack.toList())
    }
}

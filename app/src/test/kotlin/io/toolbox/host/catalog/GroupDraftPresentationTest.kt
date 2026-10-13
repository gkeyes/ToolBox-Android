package io.toolbox.host.catalog

import io.toolbox.host.ui.groupDraftHasChanges
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupDraftPresentationTest {
    @Test
    fun unchangedDraftDoesNotRequireDiscardConfirmation() {
        assertFalse(groupDraftHasChanges(" Work ", listOf("a"), "Work", listOf("a")))
        assertFalse(groupDraftHasChanges("", listOf("seed"), "", listOf("seed")))
    }

    @Test
    fun editingNameOrMembershipRequiresConfirmation() {
        assertTrue(groupDraftHasChanges("New", listOf("a"), "Old", listOf("a")))
        assertTrue(groupDraftHasChanges("Work", emptyList(), "Work", listOf("a")))
        assertTrue(groupDraftHasChanges("Work", listOf("b", "a"), "Work", listOf("a", "b")))
    }

    @Test
    fun newEmptyGroupIsStillADraftOnceNamed() {
        assertTrue(groupDraftHasChanges("Travel", emptyList(), "", emptyList()))
    }

    @Test
    fun changingOnlyTheStyleRequiresConfirmation() {
        assertTrue(groupDraftHasChanges("Work", listOf("a"), "Work", listOf("a"), "green/Code", "blue/Folder"))
        assertFalse(groupDraftHasChanges("Work", listOf("a"), "Work", listOf("a"), "blue/Folder", "blue/Folder"))
    }
}

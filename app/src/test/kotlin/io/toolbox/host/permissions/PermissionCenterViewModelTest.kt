package io.toolbox.host.permissions

import io.toolbox.core.data.DataResult
import io.toolbox.core.data.GrantScope
import io.toolbox.core.data.GrantSource
import io.toolbox.core.data.GrantState
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionCenterViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun revokeRemovesObservedGrantImmediatelyAndRefusesUnknownPermissionMutation() = runTest(mainDispatcher) {
        val repository = FakePermissionGrantRepository(
            PermissionGrant(
                toolId = "io.toolbox.example",
                permission = "storage",
                state = GrantState.GRANTED,
                scope = GrantScope.PERSISTENT,
                grantedAt = 100L,
                expiresAt = null,
                source = GrantSource.INSTALL,
            ),
        )
        val viewModel = PermissionCenterViewModel("io.toolbox.example", repository)
        advanceUntilIdle()

        viewModel.revoke("storage")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.grants.isEmpty())
        assertEquals(listOf("storage"), repository.revokeCalls)
        assertEquals(PermissionCenterFeedback.Revoked("storage"), viewModel.state.value.feedback)

        viewModel.revoke("network")
        advanceUntilIdle()

        assertEquals(listOf("storage"), repository.revokeCalls)
        assertEquals(PermissionCenterFeedback.NotDeclared("network"), viewModel.state.value.feedback)
    }
}

private class FakePermissionGrantRepository(vararg initial: PermissionGrant) : PermissionGrantRepository {
    private val values = MutableStateFlow(initial.toList())
    val revokeCalls = mutableListOf<String>()

    override fun observeGrants(toolId: String): Flow<List<PermissionGrant>> = values

    override suspend fun put(grant: PermissionGrant): DataResult<Unit> = DataResult.Success(Unit)

    override suspend fun revoke(toolId: String, permission: String): DataResult<Unit> {
        revokeCalls += permission
        values.value = values.value.filterNot { it.toolId == toolId && it.permission == permission }
        return DataResult.Success(Unit)
    }
}

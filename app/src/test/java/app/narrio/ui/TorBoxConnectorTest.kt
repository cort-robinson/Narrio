package app.narrio.ui

import app.narrio.data.ProviderException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TorBoxConnectorTest {
    /** A TorBox check that answers when [answer] is completed, recording each key it was given and each key saved. */
    private class FakeAccount {
        val tried = mutableListOf<String>()
        val saved = mutableListOf<String>()
        var answer = CompletableDeferred<Unit>()
        var connected = 0
        suspend fun save(key: String) { tried += key; answer.await(); saved += key }
    }

    @Test fun submittingTheKeyChecksItOnceSavesItAndClosesTheSheet() = runTest {
        val account = FakeAccount()
        val connector = TorBoxConnector(this, account::save) { account.connected++ }
        connector.request()
        connector.connect("  key-1 \n")
        runCurrent()
        assertEquals(TorBoxConnectState(prompt = true, connecting = true), connector.state.value)
        // A second tap while the first is checking does nothing.
        connector.connect("key-1"); runCurrent()
        assertEquals(listOf("key-1"), account.tried)
        account.answer.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("key-1"), account.saved)
        assertEquals(1, account.connected)
        assertEquals(TorBoxConnectState(), connector.state.value)
    }

    @Test fun aFailureStaysUnderTheKeyAndTheNextTryCanSucceed() = runTest {
        var fail = true
        var connected = 0
        val connector = TorBoxConnector(this, { if (fail) throw ProviderException("TorBox didn't accept this API key.") }) { connected++ }
        connector.request(); connector.connect("wrong"); advanceUntilIdle()
        assertEquals(TorBoxConnectState(prompt = true, error = "TorBox didn't accept this API key."), connector.state.value)
        connector.clearError(); assertNull(connector.state.value.error)
        fail = false; connector.connect("right"); advanceUntilIdle()
        assertEquals(1, connected)
        assertEquals(TorBoxConnectState(), connector.state.value)
    }

    @Test fun anUnexpectedFailureSaysTheKeyWasNotSaved() = runTest {
        val connector = TorBoxConnector(this, { throw IllegalStateException("keystore") }) { }
        connector.request(); connector.connect("key"); advanceUntilIdle()
        assertEquals("Couldn't save the key on this phone. Try again.", connector.state.value.error)
    }

    @Test fun closingTheSheetCancelsTheAttemptSoTheKeyIsNeverSaved() = runTest {
        val account = FakeAccount()
        val connector = TorBoxConnector(this, account::save) { account.connected++ }
        connector.request(); connector.connect("key-1"); runCurrent()
        connector.dismiss(); runCurrent()
        assertEquals(TorBoxConnectState(), connector.state.value)
        account.answer.complete(Unit); advanceUntilIdle()
        assertTrue(account.saved.isEmpty())
        assertEquals(0, account.connected)
        // Reopened, the form works again at once.
        connector.request(); connector.connect("key-2"); advanceUntilIdle()
        assertEquals(listOf("key-2"), account.saved)
        assertEquals(1, account.connected)
    }

    @Test fun anAttemptThatIgnoresCancellationCannotTouchANewerOne() = runTest {
        val stale = CompletableDeferred<Unit>()
        val current = CompletableDeferred<Unit>()
        var connected = 0
        val connector = TorBoxConnector(this, { key ->
            // A blocking check finishes even after it was cancelled; its outcome must be ignored.
            if (key == "old") withContext(NonCancellable) { stale.await(); throw ProviderException("Too late") } else current.await()
        }) { connected++ }
        connector.request(); connector.connect("old"); runCurrent()
        connector.dismiss()
        connector.request(); connector.connect("new"); runCurrent()
        stale.complete(Unit); advanceUntilIdle()
        assertEquals(TorBoxConnectState(prompt = true, connecting = true), connector.state.value)
        current.complete(Unit); advanceUntilIdle()
        assertEquals(1, connected)
        assertEquals(TorBoxConnectState(), connector.state.value)
    }
}

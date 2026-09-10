package io.toolbox.host.runtime

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Invoked in two separate app processes on the same runner emulator, without reinstall/clear. */
@RunWith(AndroidJUnit4::class)
class TbxUpgradeProcessTest {
    @Test
    fun phase() = runBlocking(Dispatchers.IO) {
        val phase = InstrumentationRegistry.getArguments().getString("tbxPhase")
        assumeTrue(phase == "seed" || phase == "restore")
        val f = UpgradeFixture("processfixture")
        val proof = File(f.application.filesDir, "upgrade-process-proof.json")
        var completed = false
        try {
            if (phase == "seed") {
                check(!proof.exists()) { "Run on a fresh isolated emulator" }
                f.install(1)
                f.login(f.page())
                val hash = sha(f.envelope.read()!!)
                f.install(2)
                f.assertLogin(f.page(), "2")
                assertEquals(hash, sha(f.envelope.read()!!))
                proof.writeText(JSONObject().put("ciphertextSha256", hash).put("pid", Process.myPid()).toString())
            } else {
                check(proof.isFile) { "Seed phase must precede process restart" }
                val stored = JSONObject(proof.readText())
                assertNotEquals(stored.getInt("pid"), Process.myPid())
                assertEquals(stored.getString("ciphertextSha256"), sha(f.envelope.read()!!))
                assertTrue(f.hasKey())
                f.dependencies.recoverPendingPackageMutations()
                f.assertLogin(f.page(), "2")
                assertEquals(stored.getString("ciphertextSha256"), sha(f.envelope.read()!!))
            }
            completed = true
        } finally {
            // Only the restore phase removes this synthetic fixture, after the assertions.
            f.close(remove = phase == "restore" && completed)
            if (phase == "restore" && completed) proof.delete()
        }
    }
}

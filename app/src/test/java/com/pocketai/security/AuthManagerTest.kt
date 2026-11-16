package com.pocketai.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuthManagerTest {
    private lateinit var context: Context
    private lateinit var auth: AuthManager
    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pocket_ai_auth", Context.MODE_PRIVATE).edit().clear().commit()
        auth = AuthManager(context)
    }

    @Test fun hashedPinSurvivesRestartButAuthorizationDoesNot() {
        val recovery = auth.configure("987654")
        assertTrue(auth.isConfigured)
        assertTrue(auth.isUnlocked)
        val restarted = AuthManager(context)
        assertFalse(restarted.isUnlocked)
        assertFalse(restarted.unlock("000000"))
        assertTrue(restarted.unlock("987654"))
        val stored = context.getSharedPreferences("pocket_ai_auth", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(stored.contains("987654"))
        assertFalse(stored.contains(recovery.replace("-", "")))
    }

    @Test fun recoveryRotatesBothPinAndRecoveryCode() {
        val firstRecovery = auth.configure("987654")
        auth.lock()
        val secondRecovery = auth.recover(firstRecovery.lowercase(), "123456")
        assertNotEquals(firstRecovery, secondRecovery)
        auth.lock()
        assertFalse(auth.unlock("987654"))
        assertTrue(auth.unlock("123456"))
        assertThrows(IllegalArgumentException::class.java) { auth.recover(firstRecovery, "654321") }
    }

    @Test fun sessionExpiresAndBadPinAttemptsAreRateLimited() {
        // Robolectric's elapsed clock is independent of the JVM wall clock used for persisted lockouts.
        var wallNow = System.currentTimeMillis()
        auth = AuthManager(context) { wallNow }
        auth.configure("987654")
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11))
        assertFalse(auth.isUnlocked)
        assertThrows(IllegalStateException::class.java) { auth.requireAuthorized() }
        repeat(5) { assertFalse(auth.unlock("000000")) }
        assertTrue(auth.retryAfterSeconds > 0)
        assertThrows(IllegalStateException::class.java) { auth.unlock("987654") }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        wallNow += Duration.ofSeconds(31).toMillis()
        assertTrue(auth.unlock("987654"))
    }

    @Test fun rejectsWeakPinAndUnauthorizedChanges() {
        assertThrows(IllegalArgumentException::class.java) { auth.configure("123") }
        assertFalse(auth.isConfigured)
        auth.configure("987654")
        assertThrows(IllegalStateException::class.java) { auth.configure("123456") }
        auth.lock()
        assertThrows(IllegalStateException::class.java) { auth.changePin("123456") }
        assertTrue(auth.unlock("987654"))
        auth.changePin("123456")
        auth.lock()
        assertTrue(auth.unlock("123456"))
    }

    @Test fun readingAndLockingSessionDoesNotWaitForPendingPinVerification() {
        auth.configure("987654")
        val enteredVerification = CountDownLatch(1)
        val releaseVerification = CountDownLatch(1)
        val blocking = AtomicBoolean(true)
        val concurrent = AuthManager(context) {
            if (blocking.get()) {
                enteredVerification.countDown()
                check(releaseVerification.await(10, TimeUnit.SECONDS)) { "Timed out waiting to finish authentication test." }
            }
            System.currentTimeMillis()
        }
        val worker = Executors.newSingleThreadExecutor()
        val ui = Executors.newSingleThreadExecutor()
        try {
            val pendingUnlock = worker.submit<Boolean> { concurrent.unlock("987654") }
            assertTrue(enteredVerification.await(5, TimeUnit.SECONDS))
            val lockFromUi = ui.submit<Boolean> {
                assertFalse(concurrent.isUnlocked)
                concurrent.lock()
                concurrent.isUnlocked
            }
            assertFalse("UI session access must not wait on the authentication monitor", lockFromUi.get(1, TimeUnit.SECONDS))
            blocking.set(false)
            releaseVerification.countDown()
            assertFalse("A successful PIN check cannot override a newer lock", pendingUnlock.get(10, TimeUnit.SECONDS))
            assertFalse(concurrent.isUnlocked)
            assertTrue(concurrent.unlock("987654"))
        } finally {
            blocking.set(false)
            releaseVerification.countDown()
            worker.shutdownNow()
            ui.shutdownNow()
        }
    }

    @Test fun pendingRecoveryCanRotateCredentialsButCannotOverrideNewerLock() {
        val recovery = auth.configure("987654")
        val enteredVerification = CountDownLatch(1)
        val releaseVerification = CountDownLatch(1)
        val blocking = AtomicBoolean(true)
        val concurrent = AuthManager(context) {
            if (blocking.get()) {
                enteredVerification.countDown()
                check(releaseVerification.await(10, TimeUnit.SECONDS)) { "Timed out waiting to finish authentication test." }
            }
            System.currentTimeMillis()
        }
        val worker = Executors.newSingleThreadExecutor()
        val ui = Executors.newSingleThreadExecutor()
        try {
            val pendingRecovery = worker.submit<String> { concurrent.recover(recovery, "123456") }
            assertTrue(enteredVerification.await(5, TimeUnit.SECONDS))
            ui.submit { concurrent.lock() }.get(1, TimeUnit.SECONDS)
            blocking.set(false)
            releaseVerification.countDown()
            assertNotEquals(recovery, pendingRecovery.get(10, TimeUnit.SECONDS))
            assertFalse("Credential replacement must preserve the newer session revocation", concurrent.isUnlocked)
            assertTrue(concurrent.unlock("123456"))
        } finally {
            blocking.set(false)
            releaseVerification.countDown()
            worker.shutdownNow()
            ui.shutdownNow()
        }
    }
}

// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.MediumTest
import okhttp3.Cookie
import pl.kyvaith.velvet.playback.NetflixPlaybackException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Uses the device's real Android Keystore; never opens the app's production preferences. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class SessionStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var preferencesName: String
    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        preferencesName = "velvet_session_test_${UUID.randomUUID()}"
        store = SessionStore(context, preferencesName)
    }

    @After
    fun tearDown() {
        // Flush queued preference writes before deleting only this test's isolated file.
        // The shared Keystore alias is deliberately not removed.
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun androidKeystoreEncryptsAndRestoresSessionAndPosition() {
        val session = dummySession(PROFILE_A)
        store.beginSession(session)
        store.savePosition(PROFILE_A, VIDEO, 143, store.generation)

        val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val encryptedSession = prefs.getString("session", null)
        val encryptedPositions = prefs.getString("positions", null)
        assertNotNull(encryptedSession)
        assertNotNull(encryptedPositions)
        assertFalse(encryptedSession!!.contains(session.cookieHeader))
        assertFalse(encryptedSession.contains(session.authUrl))
        assertFalse(encryptedPositions!!.contains(PROFILE_A))

        // A new instance must decrypt persisted values, rather than return the original cache.
        val reopened = SessionStore(context, preferencesName)
        assertEquals(session, reopened.read())
        assertEquals(143L, reopened.resumeSeconds(PROFILE_A, VIDEO))
        assertFalse(reopened.unreadableSession)

        // Encrypting the same session again must use fresh randomized encryption.
        store.save(session, store.generation)
        assertNotEquals(encryptedSession, prefs.getString("session", null))
        assertEquals(session, SessionStore(context, preferencesName).read())
    }

    @Test
    fun clearRejectsLateReadAndSaveWithoutRestoringLoggedOutSession() {
        val session = dummySession(PROFILE_A)
        store.beginSession(session)
        val previousGeneration = store.generation

        store.clear()

        assertTrue(store.generation > previousGeneration)
        assertThrows(NetflixApiException::class.java) { store.read(previousGeneration) }
        assertThrows(NetflixApiException::class.java) { store.save(session, previousGeneration) }
        assertNull(store.read())
        assertNull(SessionStore(context, preferencesName).read())
        assertTrue(context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun beginSessionRejectsPreviousGenerationAndRetainsTheNewSession() {
        val previousSession = dummySession(PROFILE_A)
        val currentSession = dummySession(PROFILE_B)
        store.beginSession(previousSession)
        val previousGeneration = store.generation

        store.beginSession(currentSession)
        val currentGeneration = store.generation

        assertTrue(currentGeneration > previousGeneration)
        assertThrows(NetflixApiException::class.java) { store.read(previousGeneration) }
        assertThrows(NetflixApiException::class.java) { store.save(previousSession, previousGeneration) }
        assertEquals(currentSession, store.read(currentGeneration))
        assertEquals(currentSession, SessionStore(context, preferencesName).read())
    }

    @Test
    fun delayedPlaybackProgressCannotWriteIntoAReplacementSession() {
        store.beginSession(dummySession(PROFILE_A))
        val previousGeneration = store.generation
        val callbackReady = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val lateCallback = executor.submit {
                callbackReady.countDown()
                check(releaseCallback.await(30, TimeUnit.SECONDS))
                // The original callback carries the old profile and generation.
                store.savePosition(PROFILE_A, VIDEO, 900, previousGeneration)
                // Even using the new profile must not rescue an obsolete generation.
                store.savePosition(PROFILE_B, VIDEO, 900, previousGeneration)
            }
            assertTrue(callbackReady.await(30, TimeUnit.SECONDS))

            store.beginSession(dummySession(PROFILE_B))
            store.savePosition(PROFILE_B, VIDEO, 47, store.generation)
            releaseCallback.countDown()
            lateCallback.get(30, TimeUnit.SECONDS)

            assertEquals(0L, store.resumeSeconds(PROFILE_A, VIDEO))
            assertEquals(47L, store.resumeSeconds(PROFILE_B, VIDEO))
            val reopened = SessionStore(context, preferencesName)
            assertEquals(PROFILE_B, reopened.read()?.profileGuid)
            assertEquals(0L, reopened.resumeSeconds(PROFILE_A, VIDEO))
            assertEquals(47L, reopened.resumeSeconds(PROFILE_B, VIDEO))
        } finally {
            releaseCallback.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun browserTransitionInvalidatesOldWorkAndPreservesAccountAndPositions() {
        val initial = dummySession(PROFILE_A).copy(userAgent =
            "Mozilla/5.0 (Linux; Android 17; Pixel; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/158.0.8127.25 Mobile Safari/537.36")
        store.beginSession(initial)
        val oldGeneration = store.generation
        store.savePosition(PROFILE_A, VIDEO, 47, oldGeneration)

        val newGeneration = store.transition(oldGeneration) { NetflixBrowserContext.migratedSession(it)!! }!!

        assertTrue(newGeneration > oldGeneration)
        assertThrows(NetflixApiException::class.java) { store.read(oldGeneration) }
        val updated = store.read(newGeneration)!!
        assertEquals(initial.cookieHeader, updated.cookieHeader)
        assertEquals(initial.profileGuid, updated.profileGuid)
        assertEquals("", updated.esn)
        assertEquals("desktop_site", NetflixBrowserContext.contextTag(updated.userAgent))
        assertEquals(updated, SessionStore(context, preferencesName).read())
        assertEquals(47L, store.resumeSeconds(PROFILE_A, VIDEO))

        store.clear()
        assertThrows(NetflixApiException::class.java) { store.transition(newGeneration) { initial } }
        assertNull(SessionStore(context, preferencesName).read())
    }

    @Test
    fun playbackCookieRenewalUsesFreshValuesAndRejectsLoggedOutOwner() {
        val owner = dummySession(PROFILE_A)
        store.beginSession(owner)
        val playback = StoredPlaybackSession(store, store.generation, owner)
        val renewed = Cookie.Builder().name("NetflixId").value("renewed-test-only")
            .domain("netflix.com").path("/").secure().build()

        playback.updateCookies(listOf(renewed))
        assertTrue(playback.playbackContext().cookieHeader.contains("NetflixId=renewed-test-only"))
        assertEquals(store.read(), SessionStore(context, preferencesName).read())

        store.clear()
        val error = assertThrows(NetflixPlaybackException::class.java) { playback.updateCookies(listOf(renewed)) }
        assertEquals(false, error.retryable)
        assertThrows(NetflixPlaybackException::class.java) { playback.playbackContext() }
        assertNull(store.read())
    }

    @Test
    fun playbackCookieRenewalCannotCrossProfilesOrDerivedClientIdentity() {
        val owner = dummySession(PROFILE_A)
        store.beginSession(owner)
        val playback = StoredPlaybackSession(store, store.generation, owner)
        val cookie = Cookie.Builder().name("NetflixId").value("late-test-only")
            .domain("netflix.com").path("/").secure().build()

        // Even a same-generation identity change must not permit a late DRM response to write.
        store.update { it.copy(profileGuid = PROFILE_B) }
        assertThrows(NetflixPlaybackException::class.java) { playback.updateCookies(listOf(cookie)) }
        assertEquals(owner.cookieHeader, store.read()!!.cookieHeader)
        store.update { it.copy(profileGuid = PROFILE_A, esn = "changed-test-only") }
        assertThrows(NetflixPlaybackException::class.java) { playback.playbackContext() }
        assertThrows(NetflixPlaybackException::class.java) { playback.updateCookies(listOf(cookie)) }
        assertEquals(owner.cookieHeader, store.read()!!.cookieHeader)
    }

    @Test
    fun nativeOwnerTokensUseEncryptedSessionStorageAndCanBeCleared() {
        val owner = dummySession(PROFILE_A)
        store.beginSession(owner)
        val playback = StoredPlaybackSession(store, store.generation, owner)
        assertEquals("", playback.loadNativeAuthentication())

        playback.saveNativeAuthentication(NATIVE_AUTH)

        assertEquals(NATIVE_AUTH, playback.loadNativeAuthentication())
        assertEquals(NATIVE_AUTH, SessionStore(context, preferencesName).read()?.nativePlaybackAuth)
        val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        assertEquals(setOf("session"), prefs.all.keys)
        val encrypted = prefs.getString("session", null)!!
        assertFalse(encrypted.contains(NATIVE_AUTH))
        assertFalse(encrypted.contains("fixture-native-master-token"))
        assertFalse(encrypted.contains("fixture-native-user-token"))

        playback.saveNativeAuthentication("")
        assertEquals("", playback.loadNativeAuthentication())
        assertEquals("", SessionStore(context, preferencesName).read()?.nativePlaybackAuth)

        playback.saveNativeAuthentication(NATIVE_AUTH)
        store.clear()
        assertThrows(NetflixPlaybackException::class.java) { playback.loadNativeAuthentication() }
        assertThrows(NetflixPlaybackException::class.java) { playback.saveNativeAuthentication(NATIVE_AUTH) }
        assertNull(SessionStore(context, preferencesName).read())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun newAccountLoginDropsCopiedNativeTokensAndRejectsThePreviousOwner() {
        val owner = dummySession(PROFILE_A)
        store.beginSession(owner)
        val playback = StoredPlaybackSession(store, store.generation, owner)
        playback.saveNativeAuthentication(NATIVE_AUTH)
        val copied = store.read()!!.copy(cookieHeader = "NetflixId=replacement; SecureNetflixId=replacement-secure")

        // Profile and web ESN deliberately stay equal; only account generation distinguishes them.
        store.beginSession(copied)

        assertEquals("", store.read()!!.nativePlaybackAuth)
        assertEquals("", SessionStore(context, preferencesName).read()?.nativePlaybackAuth)
        assertEquals(copied.cookieHeader, store.read()!!.cookieHeader)
        assertThrows(NetflixPlaybackException::class.java) { playback.loadNativeAuthentication() }
        assertThrows(NetflixPlaybackException::class.java) { playback.saveNativeAuthentication(NATIVE_AUTH) }
        assertEquals("", store.read()!!.nativePlaybackAuth)
    }

    @Test
    fun nativeOwnerTokensSurviveSameAccountProfileChangesButOldProvidersCannotAccessThem() {
        val owner = dummySession(PROFILE_A)
        store.beginSession(owner)
        val original = StoredPlaybackSession(store, store.generation, owner)
        original.saveNativeAuthentication(NATIVE_AUTH)

        store.update { it.copy(profileGuid = PROFILE_B) }

        assertThrows(NetflixPlaybackException::class.java) { original.loadNativeAuthentication() }
        assertThrows(NetflixPlaybackException::class.java) { original.saveNativeAuthentication("") }
        val replacement = StoredPlaybackSession(store, store.generation, store.read()!!)
        assertEquals(NATIVE_AUTH, replacement.loadNativeAuthentication())

        store.update { it.copy(esn = "changed-test-only-browser-identity") }

        assertThrows(NetflixPlaybackException::class.java) { replacement.loadNativeAuthentication() }
        assertThrows(NetflixPlaybackException::class.java) { replacement.saveNativeAuthentication("") }
        val newIdentity = StoredPlaybackSession(store, store.generation, store.read()!!)
        assertEquals(NATIVE_AUTH, newIdentity.loadNativeAuthentication())
        assertEquals(NATIVE_AUTH, SessionStore(context, preferencesName).read()?.nativePlaybackAuth)
    }

    @Test
    fun queuedNativeTokenReadAndWriteRejectAnAtomicOwnerChange() {
        val owner = dummySession(PROFILE_A)
        store.beginSession(owner)
        val playback = StoredPlaybackSession(store, store.generation, owner)
        playback.saveNativeAuthentication(NATIVE_AUTH)
        val changeStarted = CountDownLatch(1)
        val releaseChange = CountDownLatch(1)
        val accessStarted = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(3)

        try {
            val change = executor.submit {
                store.update { current ->
                    changeStarted.countDown()
                    check(releaseChange.await(30, TimeUnit.SECONDS))
                    current.copy(profileGuid = PROFILE_B)
                }
            }
            assertTrue(changeStarted.await(30, TimeUnit.SECONDS))
            val lateRead = executor.submit<String> {
                accessStarted.countDown()
                playback.loadNativeAuthentication()
            }
            val lateWrite = executor.submit {
                accessStarted.countDown()
                playback.saveNativeAuthentication("")
            }
            assertTrue(accessStarted.await(30, TimeUnit.SECONDS))
            releaseChange.countDown()
            change.get(30, TimeUnit.SECONDS)

            val readError = assertThrows(ExecutionException::class.java) { lateRead.get(30, TimeUnit.SECONDS) }
            val writeError = assertThrows(ExecutionException::class.java) { lateWrite.get(30, TimeUnit.SECONDS) }
            assertTrue(readError.cause is NetflixPlaybackException)
            assertTrue(writeError.cause is NetflixPlaybackException)
            assertEquals(PROFILE_B, store.read()!!.profileGuid)
            assertEquals(NATIVE_AUTH, store.read()!!.nativePlaybackAuth)
            assertEquals(NATIVE_AUTH, SessionStore(context, preferencesName).read()?.nativePlaybackAuth)
        } finally {
            releaseChange.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun dummySession(profile: String) = SessionData(
        cookieHeader = "NetflixId=instrumented-test-only-$profile; SecureNetflixId=not-a-real-token",
        userAgent = "Velvet instrumentation fixture (no network)",
        authUrl = "test-only-auth-$profile",
        esn = "test-only-esn",
        profileGuid = profile,
        buildIdentifier = "fixture-build",
        clientVersion = "fixture-client",
        uiVersion = "fixture-ui",
        browserVersion = "fixture-browser",
        osName = "Android",
        osVersion = "35",
        locale = "pl-PL",
    )

    private companion object {
        const val PROFILE_A = "profile-A-test-only"
        const val PROFILE_B = "profile-B-test-only"
        const val VIDEO = "video-test-only"
        const val NATIVE_AUTH = "{\"identity\":\"fixture-native-identity\",\"mastertoken\":{\"tokendata\":\"fixture-native-master-token\"},\"useridtoken\":{\"tokendata\":\"fixture-native-user-token\"}}"
    }
}

package se.spareparts.inventory

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import se.spareparts.inventory.data.AccountUser
import se.spareparts.inventory.data.ApiClient
import se.spareparts.inventory.data.ApiException
import se.spareparts.inventory.data.MemorySnapshot
import se.spareparts.inventory.data.PendingAdjustment
import se.spareparts.inventory.data.PendingQueue
import se.spareparts.inventory.data.ServerConfig
import se.spareparts.inventory.data.Session
import se.spareparts.inventory.data.SessionManager
import se.spareparts.inventory.data.SessionPersistence
import se.spareparts.inventory.domain.Permissions
import java.net.SocketTimeoutException

/** Remembers the session in memory – what [SessionManager] writes to DataStore on a phone. */
private class MemorySessions(var stored: Session? = null) : SessionPersistence {
    var saves = 0
    override suspend fun load(): Session? = stored
    override suspend fun save(session: Session?) {
        stored = session; saves++
    }
}

class AuthTest {
    private lateinit var server: MockWebServer
    private var token = ""
    private lateinit var api: ApiClient

    @Before fun start() {
        server = MockWebServer()
        server.start()
        api = ApiClient({ ServerConfig(server.url("/").toString(), token) })
    }

    @After fun stop() = server.shutdown()

    private fun json(code: Int, body: String) = server.enqueue(
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))

    // ------------------------------------------------------------------ login

    @Test fun loginSuccessIsParsed() = runBlocking {
        json(200, """{"token":"3.3.1795163733.abc","expires":1795163733,
            "user":{"id":3,"username":"staff1","name":"Staff","role":"staff",
                    "perms":["adjust","edit_light","files","view"],"must_change":0}}""")
        val r = api.login("staff1", "staff-pass-11")
        assertEquals("3.3.1795163733.abc", r.token)
        assertEquals(1795163733L, r.expires)
        assertEquals("staff1", r.user.username)
        assertEquals("Staff", r.user.name)
        assertEquals("staff", r.user.role)
        assertFalse(r.user.mustChange)          // the server sends 0/1, not true/false
        assertEquals(setOf("adjust", "edit_light", "files", "view"), r.user.permissions.perms)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/login", req.path)
        assertEquals("""{"username":"staff1","password":"staff-pass-11"}""", req.body.readUtf8())
        // Signing in must not carry an old token along.
        assertNull(req.getHeader("X-Api-Key"))
    }

    @Test fun mustChangeIsUnderstoodAsNumberAndBoolean() = runBlocking {
        json(200, """{"token":"t","expires":0,"user":{"username":"new","must_change":1}}""")
        assertTrue(api.login("new", "x").user.mustChange)
        json(200, """{"token":"t","expires":0,"user":{"username":"new","must_change":true}}""")
        assertTrue(api.login("new", "x").user.mustChange)
    }

    @Test fun loginFailureKeepsTheServersWording() = runBlocking {
        json(401, """{"error":"Too many attempts - try again in 12 min"}""")
        try {
            api.login("staff1", "nope")
            fail("expected 401")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
            assertEquals("Too many attempts - try again in 12 min", e.message)
        }
        json(401, """{"error":"This account is switched off"}""")
        try {
            api.login("old", "x"); fail("expected 401")
        } catch (e: ApiException) {
            assertEquals("This account is switched off", e.message)
        }
    }

    @Test fun offlineLoginIsAConnectionError() = runBlocking {
        server.shutdown()
        try {
            api.login("staff1", "x"); fail("expected an IO error")
        } catch (e: Exception) {
            assertTrue(e !is ApiException)
            assertTrue(se.spareparts.inventory.data.Repository.describe(e).isNotBlank())
        }
    }

    // -------------------------------------------------------------- the token

    @Test fun tokenGoesInTheApiKeyHeaderAndXUserIsGone() = runBlocking {
        token = "3.3.999.deadbeef"
        json(200, """{"version":4,"parts":[]}""")
        api.parts(null)
        val req = server.takeRequest()
        assertEquals("3.3.999.deadbeef", req.getHeader("X-Api-Key"))
        assertNull(req.getHeader("X-User"))

        json(200, """{"user":{"id":1,"username":"admin","name":"Admin","role":"admin","kind":"person",
            "perms":["view","adjust","edit","edit_light","files","import","delete","users"],"must_change":0},
            "roles":[{"id":"admin","text":"Everything, including accounts"}],
            "mode":"cloud","can_manage_users":true}""")
        val me = api.me()
        assertEquals("admin", me.user.username)
        assertTrue(me.canManageUsers)
        assertTrue(me.user.permissions.canManageUsers)
        assertEquals("3.3.999.deadbeef", server.takeRequest().getHeader("X-Api-Key"))
    }

    @Test fun changePasswordPostsToMe() = runBlocking {
        token = "tok"
        json(200, """{"ok":true}""")
        api.changePassword("old-one-99", "new-one-99")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/me", req.path)
        assertEquals("""{"current":"old-one-99","new":"new-one-99"}""", req.body.readUtf8())
        assertEquals("tok", req.getHeader("X-Api-Key"))

        json(403, """{"error":"Current password is wrong"}""")
        try {
            api.changePassword("bad", "new-one-99"); fail("expected 403")
        } catch (e: ApiException) {
            assertEquals(403, e.code)
            assertEquals("Current password is wrong", e.message)
        }
    }

    // ------------------------------------------------- 401 ends the session

    @Test fun unauthorizedEndsTheSessionButKeepsTheQueue() = runBlocking {
        val store = MemorySessions()
        val session = SessionManager(store)
        val queue = PendingQueue(MemorySnapshot())
        session.signIn(Session("tok", 0, AccountUser(username = "staff1", role = "staff",
            perms = listOf("view", "adjust"))))
        queue.add(PendingAdjustment("a", "P1", delta = -1.0))
        queue.add(PendingAdjustment("b", "P2", setTo = 4.0))
        assertTrue(session.signedIn)

        token = "tok"
        json(401, """{"error":"Sign-in required"}""")
        val failure = runCatching { api.parts(null) }.exceptionOrNull()!!
        assertTrue(session.onApiError(failure))

        assertNull(session.current)
        assertFalse(session.signedIn)
        assertNull(store.stored)                                   // token cleared on disk too
        assertEquals(SessionManager.SESSION_ENDED, session.notice.value)
        assertEquals(Permissions.NONE, session.perms)
        // ... and the stock changes nobody sent yet are still there, in memory and on disk.
        assertEquals(2, queue.size)
        assertEquals(listOf("a", "b"), queue.value.map { it.id })
        assertEquals(2, PendingQueue(MemorySnapshot(queue.value)).also { it.load() }.size)
    }

    @Test fun otherErrorsLeaveTheSessionAlone() = runBlocking {
        val session = SessionManager(MemorySessions())
        session.signIn(Session("tok", 0, AccountUser(username = "staff1")))
        assertFalse(session.onApiError(ApiException(403, "Your account (viewer) is not allowed to do this")))
        assertFalse(session.onApiError(ApiException(500, "boom")))
        assertFalse(session.onApiError(SocketTimeoutException("timeout")))
        assertTrue(session.signedIn)
        assertNull(session.notice.value)
    }

    @Test fun sessionSurvivesARestartAndSignOutClearsIt() = runBlocking {
        val store = MemorySessions()
        val first = SessionManager(store)
        val user = AccountUser(1, "manager1", "Manager", "manager", "person",
            listOf("view", "adjust", "edit", "edit_light", "files", "import", "delete"), false)
        first.signIn(Session("tok", 0, user))

        val afterRestart = SessionManager(store)
        afterRestart.load()
        assertEquals(user, afterRestart.user)
        assertTrue(afterRestart.perms.canDelete)

        afterRestart.signOut()
        assertNull(store.stored)
        assertNull(SessionManager(store).also { it.load() }.current)
    }

    @Test fun anExpiredTokenIsNotUsedOnStart() = runBlocking {
        val past = System.currentTimeMillis() / 1000 - 60
        val m = SessionManager(MemorySessions(Session("tok", past, AccountUser(username = "x"))))
        m.load()
        assertNull(m.current)
        // A token without a known expiry (device token) is kept.
        val d = SessionManager(MemorySessions(Session("dev", 0, AccountUser(username = "scanner", kind = "device"), device = true)))
        d.load()
        assertNotNull(d.current)
    }

    @Test fun roleChangesTakeEffectWithoutReinstalling() = runBlocking {
        val store = MemorySessions()
        val session = SessionManager(store)
        session.signIn(Session("tok", 0, AccountUser(username = "u", role = "staff",
            perms = listOf("view", "adjust", "edit_light", "files"))))
        assertTrue(session.perms.canAdjust)
        // What a sync does with the answer from GET /api/me
        session.updateUser(AccountUser(username = "u", role = "viewer", perms = listOf("view")))
        assertFalse(session.perms.canAdjust)
        assertEquals("viewer", store.stored!!.user.role)
        assertEquals("tok", store.stored!!.token)          // same token, new role
    }
}

/** The pure mapping from the server's `perms` list to what the UI offers. */
class PermissionsTest {
    private val admin = Permissions.of(listOf("view", "adjust", "edit_light", "edit", "files", "import", "delete", "users"))
    private val manager = Permissions.of(listOf("view", "adjust", "edit_light", "edit", "files", "import", "delete"))
    private val staff = Permissions.of(listOf("view", "adjust", "edit_light", "files"))
    private val viewer = Permissions.of(listOf("view"))

    @Test fun viewerSeesNoControls() {
        assertTrue(viewer.canView)
        assertFalse(viewer.canAdjust)       // no −/+ stepper, no Take/Return/Receive/Set count
        assertFalse(viewer.canEditLight)    // location, notes and minimum are plain text
        assertFalse(viewer.canEdit)
        assertFalse(viewer.canDelete)
        assertFalse(viewer.canUploadFiles)
        assertTrue(viewer.readOnly)
    }

    @Test fun staffBooksStockAndEditsTheLightFields() {
        assertTrue(staff.canAdjust)
        assertTrue(staff.canEditLight)
        assertTrue(staff.canUploadFiles)
        assertFalse(staff.canEdit)
        assertFalse(staff.canDelete)
        assertFalse(staff.canImport)
        assertFalse(staff.canManageUsers)
        assertFalse(staff.readOnly)
        listOf("location", "notes", "min_qty").forEach { assertTrue(it, staff.canEditField(it)) }
        listOf("price", "name", "pn", "category").forEach { assertFalse(it, staff.canEditField(it)) }
    }

    @Test fun managerAndAdmin() {
        assertTrue(manager.canEdit)
        assertTrue(manager.canDelete)
        assertTrue(manager.canImport)
        assertFalse(manager.canManageUsers)
        assertTrue(admin.canManageUsers)
        listOf("price", "name", "location").forEach { assertTrue(it, admin.canEditField(it)) }
    }

    @Test fun editImpliesTheLightFields() {
        // A role with 'edit' but no explicit 'edit_light' may still edit location and notes.
        val odd = Permissions.of(listOf("view", "edit"))
        assertTrue(odd.canEditLight)
        assertTrue(odd.canEditField("notes"))
    }

    @Test fun noPermissionsAtAll() {
        assertEquals(Permissions.NONE, Permissions.of(null))
        assertEquals(Permissions.NONE, Permissions.of(emptyList()))
        assertFalse(Permissions.NONE.canView)
        assertTrue(Permissions.NONE.readOnly)
        // Whitespace and case from a hand-written token do not confuse it.
        assertTrue(Permissions.of(listOf(" Adjust ", "VIEW", "")).canAdjust)
    }

    @Test fun rolesFromTheServerMatchTheirLabels() {
        assertEquals("Viewer", AccountUser(role = "viewer").roleLabel)
        assertEquals("Read only", AccountUser(role = "viewer").roleText)
        assertEquals("Staff", AccountUser(role = "staff").roleLabel)
        assertEquals("scanner", AccountUser(username = "scanner", kind = "device").displayName)
        assertTrue(AccountUser(kind = "device").isDevice)
    }
}

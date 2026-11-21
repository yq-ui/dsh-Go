package io.github.ccch1mneyyy.dsh_app.phone

import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual Core parsing/gating; only Android device operations are recorded by fixtures. */
class PhoneToolSchemaTest {
    private val intents = mutableListOf<IntentSpec>()
    private val actions = mutableListOf<PhoneAction>()
    private val pages = mutableListOf<Int>()
    private var confirmations = 0
    private val confirmationText = mutableListOf<String>()
    private val driver = object : PhoneDriver {
        override val screen = PhoneScreen.MAIN
        override fun availability() = Availability(true)
        override fun foregroundPackage() = "example.app"
        override fun observe(detail: Boolean, image: Boolean) = ScreenSnapshot("s1", "Example", "Example", "example.app")
        override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult {
            pages += maxPages
            return CollectResult(emptyList(), 0, "done", null)
        }
        override fun target(screenId: String, elementId: Int): TargetInfo? = error("unexpected target")
        override fun perform(action: PhoneAction): ActionResult { actions += action; return ActionResult(true, "done") }
        override fun open(target: PhoneAppIdentity): ActionResult = error("unexpected open")
    }
    private val core = PhoneCore(object : PhoneEnv {
        override fun enabled() = true
        override fun policies() = PhonePolicies()
        override fun isLocked() = false
        override fun a11yState() = A11yState.CONNECTED
        override fun backgroundUnavailable() = "unavailable"
        override fun zone() = ZoneId.of("Asia/Shanghai")
        override fun notifyFix(kind: String) {}
        override fun sleep(ms: Long) {}
        override fun driver(screen: PhoneScreen) = if (screen == PhoneScreen.MAIN) driver else null
        override fun gate() = object : UserGate {
            override fun confirm(request: ConfirmRequest): Decision { confirmations++; confirmationText += request.sentence; return Decision.ALLOW_ONCE }
            override fun handoff(reason: String, timeoutMs: Long): HandoffResult = error("unexpected handoff")
            override fun status(text: String?) {}
        }
        override fun system() = object : SystemApi {
            override fun apps() = emptyList<AppEntry>()
            override fun isInstalled(pkg: String) = false
            override fun prepareLaunch(app: AppEntry): Either<String, SystemLaunch> = Either.Right(TestSystemLaunch(SystemTarget.Known(app)))
            override fun prepareUrl(url: String): Either<String, SystemLaunch> = error("unexpected URL")
            override fun prepareIntent(spec: IntentSpec, selectedApp: AppEntry?): Either<String, SystemLaunch> =
                Either.Right(TestSystemLaunch(SystemTarget.UserChoice, "intent", spec))
            override fun launch(plan: SystemLaunch, dispatch: PhoneTasks.SystemDispatch): SysResult {
                check(dispatch.commitStart())
                intents += (plan as TestSystemLaunch).spec!!
                return SysResult(true, "attempted", plan.target)
            }
            override fun notify(title: String, text: String): SysResult = error("unexpected notification")
            override fun clipboardSet(text: String): SysResult = error("unexpected clipboard")
            override fun clipboardGet(): SysResult = error("unexpected clipboard")
        }
    }, PhoneTasks(), ActionLog())

    @After fun close() = core.worker.shutdown()

    @Test fun sdkSchemaKeepsIntegerFieldsAndMovesBoundsIntoDescriptions() {
        val server = McpServer(PhoneToolCatalog.listJson(), { _, _, _ -> error("tools/list must not dispatch a tool") })
        val json = checkNotNull(server.handle(Json.write(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list"))))
        val listed = ((Json.parse(json) as Map<*, *>)["result"] as Map<*, *>)["tools"] as List<*>
        assertEquals(PhoneToolCatalog.listJson(), listed)
        assertEquals(listOf("android_shell", "root_device_info", "apps", "open", "intent", "notify", "clipboard", "screen", "collect", "tap", "type", "scroll", "key", "tap_xy", "handoff", "diagnostics"),
            listed.map { (it as Map<*, *>)["name"] })
        for (tool in listed) {
            val schema = (tool as Map<*, *>)["inputSchema"] as Map<*, *>
            assertEquals(false, schema["additionalProperties"])
        }
        assertFalse(json.contains("\"minimum\""))
        assertFalse(json.contains("\"maximum\""))
        assertEquals(16, PhoneToolCatalog.tools.size)
        val rootInfo = PhoneToolCatalog.tools.single { it.name == PhoneToolCatalog.ROOT_DEVICE_INFO }
        assertEquals(listOf("operation", "path"), rootInfo.properties.keys.toList())
        assertEquals(listOf("operation", "path"), rootInfo.required)
        assertEquals(listOf("list", "metadata"), (rootInfo.properties["operation"] as Map<*, *>)["enum"])
        assertTrue(rootInfo.description.contains("用户已从 Android Shell 设置显式连接 Root"))
        assertTrue(rootInfo.description.contains("本工具不会自行启动 su"))
        assertTrue(rootInfo.description.contains("每次读取均需单独原生确认"))
        val rootPath = rootInfo.properties["path"] as Map<*, *>
        assertTrue((rootPath["description"] as String).contains("固定只读系统分区"))
        val shell = PhoneToolCatalog.tools.single { it.name == PhoneToolCatalog.ANDROID_SHELL }
        val shellDescription = shell.description
        assertTrue(shellDescription.contains("Root (su)"))
        assertTrue(shellDescription.contains("UID=0"))
        assertTrue(shellDescription.contains("PRoot"))
        assertTrue(shellDescription.contains("PRoot 中的 UID 0 不等于主机 root"))
        val key = PhoneToolCatalog.tools.single { it.name == PhoneToolCatalog.KEY }
        assertEquals(listOf("key", "screen"), key.properties.keys.toList())
        assertEquals(listOf("back", "home", "recents", "notifications"), (key.properties["key"] as Map<*, *>)["enum"])
        val tapElement = PhoneToolCatalog.tools.single { it.name == PhoneToolCatalog.TAP }
        assertEquals(listOf("screen", "id", "long"), tapElement.properties.keys.toList())
        assertFalse(tapElement.properties.containsKey("resourceId"))
        val tap = PhoneToolCatalog.tools.single { it.name == PhoneToolCatalog.TAP_XY }
        assertEquals(listOf("observation", "x", "y"), tap.required)
        assertEquals(listOf("observation", "x", "y", "action", "x2", "y2", "duration_ms"), tap.properties.keys.toList())
        val gesture = tap.properties["action"] as Map<*, *>
        assertEquals("string", gesture["type"])
        assertEquals(listOf("tap", "long", "swipe"), gesture["enum"])
        for (key in listOf("x", "y")) {
            val field = tap.properties[key] as Map<*, *>
            assertEquals("integer", field["type"])
            assertTrue((field["description"] as String).contains("从 0 开始"))
        }
        for (key in listOf("x2", "y2", "duration_ms")) assertEquals("integer", (tap.properties[key] as Map<*, *>)["type"])
        val duration = (tap.properties["duration_ms"] as Map<*, *>)["description"] as String
        assertTrue(duration.contains("100–2000"))
        assertTrue(duration.contains("400"))
        val collect = PhoneToolCatalog.tools.single { it.name == PhoneToolCatalog.COLLECT }
        val count = collect.properties["max_pages"] as Map<*, *>
        assertEquals("integer", count["type"])
        assertTrue((count["description"] as String).contains("1–30"))
        val intent = PhoneToolCatalog.tools.single { it.name == PhoneToolCatalog.INTENT }
        val days = intent.properties["days"] as Map<*, *>
        assertEquals(mapOf("type" to "integer"), days["items"])
        assertTrue((days["description"] as String).contains("1=周一 … 7=周日"))
    }

    @Test fun rootDeviceInfoRejectsArbitraryPathsAndShellMetacharacters() {
        for (path in listOf("/", "/data", "/system/../data", "/system//bin", "/system\\bin", "/system/bin;id", "/vendorized", "/productish")) {
            assertNull("must reject path=$path", RootDeviceInfoTool.parse(Args(mapOf("operation" to "metadata", "path" to path))) )
        }
        assertNull(RootDeviceInfoTool.parse(Args(mapOf("operation" to "list", "path" to "/system", "recursive" to true))))
        assertNull(RootDeviceInfoTool.parse(Args(mapOf("operation" to "execute", "path" to "/system"))))
    }

    @Test fun rootDeviceInfoOnlyBuildsQuotedReadOnlyNonRecursiveCommands() {
        val list = checkNotNull(RootDeviceInfoTool.parse(Args(mapOf("operation" to "list", "path" to "/system/bin"))))
        assertTrue(list.command().contains("target=\$(/system/bin/readlink -f '/system/bin')"))
        assertTrue(list.command().contains("canonical=\$(/system/bin/readlink -f \"\$root\""))
        assertTrue(list.command().contains("/system/bin/readlink -f"))
        assertTrue(list.command().contains("/system/bin/ls -la"))
        assertTrue(list.command().contains("allowed=0; for root in /system /system_ext /vendor /product /odm"))
        assertTrue(list.command().contains("readlink -f \"\$root\""))
        assertTrue(list.command().contains("[ \"\$canonical\" != / ]"))
        assertTrue(list.command().contains("case \"\$target\" in \"\$canonical\"|\"\$canonical\"/*)"))
        assertFalse(list.command().contains("readlink -f --"))
        assertFalse(list.command().contains("ls -la --"))
        assertFalse(list.command().contains("rm "))
        assertFalse(list.command().contains("find "))
        val metadata = checkNotNull(RootDeviceInfoTool.parse(Args(mapOf("operation" to "metadata", "path" to "/vendor/etc"))))
        assertTrue(metadata.command().contains("/system/bin/ls -ld"))
        val quoted = checkNotNull(RootDeviceInfoTool.parse(Args(mapOf("operation" to "metadata", "path" to "/system/a'b"))))
        assertTrue(quoted.command().contains("'/system/a'\\''b'"))
    }

    @Test fun alarmTimeAndWeekdayBoundsStillRejectBeforeSystemDispatch() {
        for (args in listOf(
            mapOf("hour" to -1), mapOf("hour" to 24),
            mapOf("hour" to 1, "minute" to -1), mapOf("hour" to 1, "minute" to 60),
            mapOf("hour" to 1, "days" to listOf(0)), mapOf("hour" to 1, "days" to listOf(8)),
        )) assertTrue(core.call(PhoneToolCatalog.INTENT, args + ("kind" to "alarm")).isError)
        assertTrue(intents.isEmpty())
        assertFalse(core.call(PhoneToolCatalog.INTENT, mapOf("kind" to "alarm", "hour" to 0, "minute" to 0, "days" to listOf(7, 1, 7))).isError)
        assertFalse(core.call(PhoneToolCatalog.INTENT, mapOf("kind" to "alarm", "hour" to 23, "minute" to 59)).isError)
        assertEquals(listOf(IntentSpec.Alarm(0, 0, null, listOf(1, 7)), IntentSpec.Alarm(23, 59, null, emptyList())), intents)
    }

    @Test fun timerBoundsStillRejectAndAcceptBothEndpoints() {
        for (seconds in listOf(0, 86401)) {
            assertTrue(core.call(PhoneToolCatalog.INTENT, mapOf("kind" to "timer", "seconds" to seconds)).isError)
        }
        assertTrue(intents.isEmpty())
        for (seconds in listOf(1, 86400)) {
            assertFalse(core.call(PhoneToolCatalog.INTENT, mapOf("kind" to "timer", "seconds" to seconds)).isError)
        }
        assertEquals(listOf(IntentSpec.Timer(1, null), IntentSpec.Timer(86400, null)), intents)
    }

    @Test fun collectKeepsDefaultAndClampsInsteadOfWideningItsBudget() {
        val args = mapOf("screen" to "s1", "list" to 1)
        assertFalse(core.call(PhoneToolCatalog.COLLECT, args).isError)
        for (count in listOf(0, 1, 30, 31)) {
            assertFalse(core.call(PhoneToolCatalog.COLLECT, args + ("max_pages" to count)).isError)
        }
        assertEquals(listOf(10, 1, 1, 30, 30), pages)
    }

    @Test fun tapXYKeepsNonnegativeCoordinatesAndUserConfirmation() {
        for ((x, y) in listOf(-1 to 0, 0 to -1)) {
            assertTrue(core.call(PhoneToolCatalog.TAP_XY, mapOf("observation" to "s1", "x" to x, "y" to y)).isError)
        }
        assertTrue(actions.isEmpty())
        assertEquals(0, confirmations)
        assertFalse(core.call(PhoneToolCatalog.TAP_XY, mapOf("observation" to "s1", "x" to 0, "y" to 0)).isError)
        assertEquals(listOf(PhoneAction.TapXY("s1", 0, 0)), actions)
        assertEquals(1, confirmations)
    }
    @Test fun coordinateGesturesValidateEndpointsAndDescribeTheActualConfirmedOperation() {
        val base = mapOf("observation" to "s1", "x" to 10, "y" to 20)
        for (extra in listOf(
            mapOf("action" to "unknown"), mapOf("action" to "swipe"),
            mapOf("action" to 1),
            mapOf("action" to "swipe", "x2" to 30),
            mapOf("action" to "swipe", "y2" to 40),
            mapOf("action" to "swipe", "x2" to -1, "y2" to 40),
            mapOf("action" to "swipe", "x2" to 30, "y2" to -1),
            mapOf("action" to "swipe", "x2" to 30.5, "y2" to 40),
            mapOf("action" to "swipe", "x2" to 30, "y2" to 40, "duration_ms" to "fast"),
            mapOf("action" to "swipe", "x2" to 30, "y2" to 40, "duration_ms" to 99),
            mapOf("action" to "swipe", "x2" to 30, "y2" to 40, "duration_ms" to 2001),
            mapOf("action" to "long", "x2" to 30),
            mapOf("action" to "long", "duration_ms" to 400),
            mapOf("action" to "tap", "y2" to 40),
            mapOf("action" to "tap", "duration_ms" to 400),
        )) assertTrue(core.call(PhoneToolCatalog.TAP_XY, base + extra).isError)
        assertTrue(actions.isEmpty())
        assertEquals(0, confirmations)
        assertFalse(core.call(PhoneToolCatalog.TAP_XY, base + ("action" to "long")).isError)
        assertEquals(PhoneAction.CoordinateGesture.LONG, (actions.last() as PhoneAction.TapXY).gesture)
        assertTrue(confirmationText.last().contains("长按"))
        assertFalse(core.call(PhoneToolCatalog.TAP_XY, base + mapOf("action" to "swipe", "x2" to 30, "y2" to 40, "duration_ms" to 2000)).isError)
        assertEquals(PhoneAction.TapXY("s1", 10, 20, PhoneAction.CoordinateGesture.SWIPE, 30, 40, 2000), actions.last())
        assertTrue(confirmationText.last().contains("(30,40)"))
        assertTrue(confirmationText.last().contains("2000"))
        for (extra in listOf(emptyMap(), mapOf("duration_ms" to 100))) {
            assertFalse(core.call(PhoneToolCatalog.TAP_XY, base + mapOf("action" to "swipe", "x2" to 30, "y2" to 40) + extra).isError)
            val expected = (extra["duration_ms"] ?: 400).toLong()
            assertEquals(PhoneAction.TapXY("s1", 10, 20, PhoneAction.CoordinateGesture.SWIPE, 30, 40, expected), actions.last())
            assertTrue(confirmationText.last().contains(expected.toString()))
        }
        assertEquals(4, confirmations)
    }

    @Test fun allFixedIntentKindsUseThePreparedLaunchConsumer() {
        val calls = listOf(
            mapOf("kind" to "alarm", "hour" to 9, "minute" to 30),
            mapOf("kind" to "timer", "seconds" to 60),
            mapOf("kind" to "calendar", "title" to "Synthetic", "begin" to "2030-01-02T10:00:00Z", "end" to "2030-01-02T11:00:00Z"),
            mapOf("kind" to "dial", "number" to "123"),
            mapOf("kind" to "sms_draft", "text" to "Synthetic"),
            mapOf("kind" to "share", "text" to "Synthetic"),
            mapOf("kind" to "navigate", "destination" to "Synthetic"),
            mapOf("kind" to "settings", "page" to "wifi"),
        )
        for (args in calls) {
            val out = core.call(PhoneToolCatalog.INTENT, args)
            assertFalse(out.text, out.isError)
        }
        assertEquals(8, intents.size)
        assertEquals(listOf(IntentSpec.Alarm::class, IntentSpec.Timer::class, IntentSpec.Calendar::class,
            IntentSpec.Dial::class, IntentSpec.SmsDraft::class, IntentSpec.Share::class,
            IntentSpec.Navigate::class, IntentSpec.Settings::class), intents.map { it::class })
    }

}

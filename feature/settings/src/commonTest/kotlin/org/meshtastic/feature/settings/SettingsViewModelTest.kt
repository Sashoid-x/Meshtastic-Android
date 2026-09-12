/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.feature.settings

import app.cash.turbine.test
import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.core.common.BuildConfigProvider
import org.meshtastic.core.common.state.HiddenFeaturesUnlock
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.domain.usecase.settings.ExportDataUseCase
import org.meshtastic.core.domain.usecase.settings.ExportMessagesUseCase
import org.meshtastic.core.domain.usecase.settings.ExportNodeDatabaseUseCase
import org.meshtastic.core.domain.usecase.settings.ImportMessagesFromCsvUseCase
import org.meshtastic.core.domain.usecase.settings.ImportMessagesUseCase
import org.meshtastic.core.domain.usecase.settings.IsOtaCapableUseCase
import org.meshtastic.core.domain.usecase.settings.SetMeshLogSettingsUseCase
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.MeshLog
import org.meshtastic.core.model.MessageImportResult
import org.meshtastic.core.repository.FileService
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.testing.FakeAppPreferences
import org.meshtastic.core.testing.FakeDatabaseManager
import org.meshtastic.core.testing.FakeMeshLogRepository
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.core.testing.FakeNotificationPrefs
import org.meshtastic.core.testing.FakeRadioController
import org.meshtastic.core.testing.TestDataFactory
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: SettingsViewModel
    private lateinit var nodeRepository: FakeNodeRepository
    private lateinit var radioController: FakeRadioController
    private lateinit var appPreferences: FakeAppPreferences
    private lateinit var meshLogRepository: FakeMeshLogRepository
    private lateinit var databaseManager: FakeDatabaseManager
    private lateinit var notificationPrefs: FakeNotificationPrefs
    private val radioConfigRepository: RadioConfigRepository = mock(MockMode.autofill)
    private val buildConfigProvider: BuildConfigProvider = mock(MockMode.autofill)
    private val fileService: FileService = mock(MockMode.autofill)
    private val exportMessagesUseCase: ExportMessagesUseCase = mock(MockMode.autofill)
    private val importMessagesUseCase: ImportMessagesUseCase = mock(MockMode.autofill)
    private val importMessagesFromCsvUseCase: ImportMessagesFromCsvUseCase = mock(MockMode.autofill)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        nodeRepository = FakeNodeRepository()
        radioController = FakeRadioController()
        appPreferences = FakeAppPreferences()
        meshLogRepository = FakeMeshLogRepository()
        databaseManager = FakeDatabaseManager()
        notificationPrefs = FakeNotificationPrefs()

        every { radioConfigRepository.localConfigFlow } returns MutableStateFlow(LocalConfig())
        every { buildConfigProvider.versionName } returns "3.0.0-test"

        val isOtaCapableUseCase: IsOtaCapableUseCase = mock(MockMode.autofill)
        every { isOtaCapableUseCase() } returns flowOf(true)

        val uiPrefs = appPreferences.ui
        val setMeshLogSettingsUseCase = SetMeshLogSettingsUseCase(meshLogRepository, appPreferences.meshLog)
        val exportDataUseCase = ExportDataUseCase(nodeRepository, meshLogRepository)
        val exportNodeDatabaseUseCase = ExportNodeDatabaseUseCase(nodeRepository)

        viewModel =
            SettingsViewModel(
                radioConfigRepository = radioConfigRepository,
                radioController = radioController,
                nodeRepository = nodeRepository,
                uiPrefs = uiPrefs,
                buildConfigProvider = buildConfigProvider,
                databaseManager = databaseManager,
                meshLogPrefs = appPreferences.meshLog,
                notificationPrefs = notificationPrefs,
                setMeshLogSettingsUseCase = setMeshLogSettingsUseCase,
                exportDataUseCase = exportDataUseCase,
                exportNodeDatabaseUseCase = exportNodeDatabaseUseCase,
                exportMessagesUseCase = exportMessagesUseCase,
                importMessagesUseCase = importMessagesUseCase,
                importMessagesFromCsvUseCase = importMessagesFromCsvUseCase,
                isOtaCapableUseCase = isOtaCapableUseCase,
                fileService = fileService,
                hiddenFeaturesUnlock = HiddenFeaturesUnlock(),
            )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialization() {
        assertNotNull(viewModel)
        assertEquals("3.0.0-test", viewModel.appVersionName)
    }

    @Test
    fun `isConnected flow emits updates using Turbine`() = runTest {
        viewModel.isConnected.test {
            expectMostRecentItem() shouldBe false // Default in FakeRadioController is Disconnected

            radioController.setConnectionState(ConnectionState.Connected)
            runCurrent()
            expectMostRecentItem() shouldBe true

            radioController.setConnectionState(ConnectionState.Disconnected)
            runCurrent()
            expectMostRecentItem() shouldBe false
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isOtaCapable flow works`() = runTest {
        viewModel.isOtaCapable.test {
            expectMostRecentItem() shouldBe true
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `notification settings update prefs`() = runTest {
        viewModel.setMessagesEnabled(false)
        notificationPrefs.messagesEnabled.value shouldBe false

        viewModel.setNodeEventsEnabled(false)
        notificationPrefs.nodeEventsEnabled.value shouldBe false

        viewModel.setLowBatteryEnabled(false)
        notificationPrefs.lowBatteryEnabled.value shouldBe false
    }

    @Test
    fun `mesh log logging setting updates prefs`() = runTest {
        viewModel.setMeshLogLoggingEnabled(false)
        appPreferences.meshLog.loggingEnabled.value shouldBe false

        viewModel.setMeshLogLoggingEnabled(true)
        appPreferences.meshLog.loggingEnabled.value shouldBe true
    }

    @Test
    fun `unlockHiddenFeatures updates state`() = runTest {
        viewModel.hiddenFeaturesUnlocked.value shouldBe false
        viewModel.unlockHiddenFeatures()
        viewModel.hiddenFeaturesUnlocked.value shouldBe true
    }

    @Test
    fun `provideLocation flows based on current node`() = runTest {
        val myNodeNum = 456
        nodeRepository.setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = myNodeNum))
        runCurrent()

        viewModel.provideLocation.test {
            expectMostRecentItem() shouldBe true // Default in FakeUiPrefs is true

            appPreferences.ui.setShouldProvideNodeLocation(myNodeNum, false)
            runCurrent()
            expectMostRecentItem() shouldBe false
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startProvidingLocation and stopProvidingLocation delegate to RadioController`() {
        viewModel.startProvidingLocation()
        radioController.startProvideLocationCalled shouldBe true

        viewModel.stopProvidingLocation()
        radioController.stopProvideLocationCalled shouldBe true
    }

    @Test
    fun `test property based bounds for mesh log retention days`() = runTest {
        checkAll(Arb.int(-100, 500)) { input ->
            viewModel.setMeshLogRetentionDays(input)
            viewModel.meshLogRetentionDays.value shouldBeInRange -1..365
        }
    }

    @Test
    fun `setTheme updates prefs`() = runTest {
        viewModel.setTheme(2)
        appPreferences.ui.theme.value shouldBe 2
    }

    @Test
    fun `setShowFullMessageTimestamps updates prefs`() = runTest {
        viewModel.setShowFullMessageTimestamps(true)

        appPreferences.ui.showFullMessageTimestamps.value shouldBe true
    }

    @Test
    fun `setTextCompressionEnabled updates prefs`() = runTest {
        viewModel.setTextCompressionEnabled(true)

        appPreferences.ui.textCompressionEnabled.value shouldBe true
    }

    @Test
    fun `appearance preferences update prefs`() = runTest {
        viewModel.setAdvThemeColorsJson("{\"primary\":123}")
        appPreferences.ui.advThemeColorsJson.value shouldBe "{\"primary\":123}"

        viewModel.setMessageBubbleSpacing(8)
        appPreferences.ui.messageBubbleSpacing.value shouldBe 8

        viewModel.setMessageBubblePadding(12)
        appPreferences.ui.messageBubblePadding.value shouldBe 12

        viewModel.setMessageFontSizeScale(1.2f)
        appPreferences.ui.messageFontSizeScale.value shouldBe 1.2f

        viewModel.setReactionChipSpacing(6)
        appPreferences.ui.reactionChipSpacing.value shouldBe 6
    }

    @Test
    fun `setLocale updates prefs`() = runTest {
        viewModel.setLocale("fr")
        appPreferences.ui.locale.value shouldBe "fr"
    }

    @Test
    fun `showAppIntro updates prefs`() = runTest {
        viewModel.showAppIntro()
        appPreferences.ui.appIntroCompleted.value shouldBe false
    }

    @Test
    fun `setProvideLocation updates prefs for current node`() = runTest {
        val myNodeNum = 123
        nodeRepository.setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = myNodeNum))

        viewModel.setProvideLocation(true)
        appPreferences.ui.shouldProvideNodeLocation(myNodeNum).value shouldBe true

        viewModel.setProvideLocation(false)
        appPreferences.ui.shouldProvideNodeLocation(myNodeNum).value shouldBe false
    }

    @Test
    fun `saveDataCsv writes filtered export via file service`() = runTest {
        val myNodeNum = 456
        val senderNodeNum = 123
        nodeRepository.setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = myNodeNum))
        nodeRepository.setNodes(
            listOf(TestDataFactory.createTestNode(num = senderNodeNum, longName = "Sender Node", shortName = "SN")),
        )
        meshLogRepository.setLogs(
            listOf(
                MeshLog(
                    uuid = "match",
                    message_type = "TEXT",
                    received_date = 1_700_000_000_000,
                    raw_message = "",
                    fromNum = senderNodeNum,
                    portNum = PortNum.TEXT_MESSAGE_APP.value,
                    fromRadio =
                    FromRadio(
                        packet =
                        MeshPacket(
                            from = senderNodeNum,
                            rx_snr = 5.0f,
                            decoded =
                            Data(
                                portnum = PortNum.TEXT_MESSAGE_APP,
                                payload = "Hello settings".encodeUtf8(),
                            ),
                        ),
                    ),
                ),
                MeshLog(
                    uuid = "filtered-out",
                    message_type = "RANGE",
                    received_date = 1_700_000_001_000,
                    raw_message = "",
                    fromNum = senderNodeNum,
                    portNum = PortNum.RANGE_TEST_APP.value,
                    fromRadio =
                    FromRadio(
                        packet =
                        MeshPacket(
                            from = senderNodeNum,
                            rx_snr = 6.0f,
                            decoded = Data(
                                portnum = PortNum.RANGE_TEST_APP,
                                payload = "Ignore me".encodeUtf8(),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val buffer = Buffer()
        everySuspend { fileService.write(any(), any()) } calls
            { args ->
                val block = args.arg<suspend (BufferedSink) -> Unit>(1)
                block(buffer)
                true
            }

        val uri = CommonUri.parse("content://test/export.csv")
        viewModel.saveDataCsv(uri, filterPortnum = PortNum.TEXT_MESSAGE_APP.value)
        runCurrent()

        verifySuspend { fileService.write(uri, any()) }

        val csvOutput = buffer.readUtf8()
        assertTrue(csvOutput.startsWith("\"date\",\"time\",\"from\""))
        assertTrue(csvOutput.contains("\"123\",\"Sender Node\""))
        assertTrue(csvOutput.contains("Hello settings"))
        assertFalse(csvOutput.contains("Ignore me"))
    }

    @Test
    fun `saveNodeDbJson writes node database export via file service`() = runTest {
        nodeRepository.setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = 456))
        nodeRepository.setNodes(
            listOf(TestDataFactory.createTestNode(num = 123, longName = "Sender Node", shortName = "SN")),
        )

        val buffer = Buffer()
        everySuspend { fileService.write(any(), any()) } calls
            { args ->
                val block = args.arg<suspend (BufferedSink) -> Unit>(1)
                block(buffer)
                true
            }

        val uri = CommonUri.parse("content://test/nodedb.json")
        viewModel.saveNodeDbJson(uri)
        runCurrent()

        verifySuspend { fileService.write(uri, any()) }

        val jsonOutput = buffer.readUtf8()
        assertTrue(jsonOutput.contains("\"schemaVersion\": 1"))
        assertTrue(jsonOutput.contains("\"myNodeNum\": 456"))
        assertTrue(jsonOutput.contains("\"num\": 123"))
        assertTrue(jsonOutput.contains("\"longName\": \"Sender Node\""))
    }

    @Test
    fun `setDbCacheLimit updates manager`() = runTest {
        viewModel.setDbCacheLimit(200)
        databaseManager.cacheLimit.value shouldBe 10 // Clamped to MAX_CACHE_LIMIT
    }

    @Test
    fun `cachedDeviceCountExceeding is zero when nothing would be evicted`() = runTest {
        databaseManager.existingDatabases.addAll(listOf("a", "b", "c"))
        viewModel.cachedDeviceCountExceeding(5) shouldBe 0
    }

    @Test
    fun `cachedDeviceCountExceeding counts devices past the new limit`() = runTest {
        databaseManager.existingDatabases.addAll(listOf("a", "b", "c", "d", "e"))
        viewModel.cachedDeviceCountExceeding(2) shouldBe 3
    }

    @Test
    fun `exportMessages writes via fileService and invokes callback`() = runTest {
        everySuspend { fileService.write(any(), any()) } calls
            { args ->
                val block = args.arg<suspend (BufferedSink) -> Unit>(1)
                val buffer = Buffer()
                block(buffer)
                true
            }
        everySuspend { exportMessagesUseCase(any(), any()) } returns 42

        var reportedSuccess = false
        var reportedCount = 0
        val uri = CommonUri.parse("content://test/backup.json")
        viewModel.exportMessages(uri) { success, count ->
            reportedSuccess = success
            reportedCount = count
        }
        runCurrent()

        assertTrue(reportedSuccess)
        assertEquals(42, reportedCount)
    }

    @Test
    fun `importMessages routes JSON to importMessagesUseCase`() = runTest {
        val jsonContent = "{\"messages\":[]}"
        everySuspend { fileService.read(any(), any()) } calls
            { args ->
                val block = args.arg<suspend (BufferedSource) -> Unit>(1)
                val buffer = Buffer().writeUtf8(jsonContent)
                block(buffer)
                true
            }
        val expectedResult =
            MessageImportResult(importedPackets = 5, skippedPackets = 1, importedReactions = 2, totalPackets = 6)
        everySuspend { importMessagesUseCase(any(), any()) } returns expectedResult

        var result: MessageImportResult? = null
        val uri = CommonUri.parse("content://test/backup.json")
        viewModel.importMessages(uri) { success, res, _ -> if (success) result = res }
        runCurrent()

        assertEquals(expectedResult, result)
    }

    @Test
    fun `importMessages routes CSV to importMessagesFromCsvUseCase`() = runTest {
        val csvContent = "packet_id,sender\n1,alice"
        everySuspend { fileService.read(any(), any()) } calls
            { args ->
                val block = args.arg<suspend (BufferedSource) -> Unit>(1)
                val buffer = Buffer().writeUtf8(csvContent)
                block(buffer)
                true
            }
        val expectedResult =
            MessageImportResult(importedPackets = 3, skippedPackets = 0, importedReactions = 1, totalPackets = 3)
        everySuspend { importMessagesFromCsvUseCase(any(), any()) } returns expectedResult

        var result: MessageImportResult? = null
        val uri = CommonUri.parse("content://test/backup.csv")
        viewModel.importMessages(uri) { success, res, _ -> if (success) result = res }
        runCurrent()

        assertEquals(expectedResult, result)
    }

    @Test
    fun `importMessages routes JSON with UTF-8 BOM to importMessagesUseCase`() = runTest {
        val jsonContent = "{\"messages\":[]}"
        everySuspend { fileService.read(any(), any()) } calls
            { args ->
                val block = args.arg<suspend (BufferedSource) -> Unit>(1)
                val buffer = Buffer()
                buffer.writeByte(0xEF)
                buffer.writeByte(0xBB)
                buffer.writeByte(0xBF)
                buffer.writeUtf8(jsonContent)
                block(buffer)
                true
            }
        val expectedResult =
            MessageImportResult(importedPackets = 10, skippedPackets = 0, importedReactions = 0, totalPackets = 10)
        everySuspend { importMessagesUseCase(any(), any()) } returns expectedResult

        var result: MessageImportResult? = null
        val uri = CommonUri.parse("content://test/backup_bom.json")
        viewModel.importMessages(uri) { success, res, _ -> if (success) result = res }
        runCurrent()

        assertEquals(expectedResult, result)
        assertEquals(false, viewModel.isImporting.value)
    }

    @Test
    fun `exportMessages updates isExporting state`() = runTest {
        everySuspend { fileService.write(any(), any()) } returns true
        everySuspend { exportMessagesUseCase(any(), any()) } returns 5

        val uri = CommonUri.parse("content://test/export.json")
        viewModel.exportMessages(uri) { _, _ -> }
        runCurrent()

        assertEquals(false, viewModel.isExporting.value)
    }
}

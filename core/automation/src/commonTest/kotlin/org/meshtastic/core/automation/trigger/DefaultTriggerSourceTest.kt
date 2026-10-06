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
package org.meshtastic.core.automation.trigger

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.core.automation.engine.TriggerEvent
import org.meshtastic.core.automation.model.AutomationTrigger
import org.meshtastic.core.automation.util.CronExpression
import org.meshtastic.core.model.Node
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.core.testing.FakeServiceRepository
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.User
import kotlin.test.BeforeTest
import kotlin.test.Test

class DefaultTriggerSourceTest {

    private lateinit var fakeNodeRepo: FakeNodeRepository
    private lateinit var fakeServiceRepo: FakeServiceRepository
    private lateinit var triggerSource: DefaultTriggerSource

    @BeforeTest
    fun setUp() {
        fakeNodeRepo = FakeNodeRepository()
        fakeServiceRepo = FakeServiceRepository()
        triggerSource = DefaultTriggerSource(fakeNodeRepo, fakeServiceRepo)
    }

    // ─── CronExpression Parser Tests ─────────────────────────────────────────

    @Test
    fun `valid cron expressions are recognized correctly`() {
        CronExpression.isValid("* * * * *") shouldBe true
        CronExpression.isValid("*/5 * * * *") shouldBe true
        CronExpression.isValid("0 0 * * *") shouldBe true
        CronExpression.isValid("0 9-17 * * 1-5") shouldBe true
        CronExpression.isValid("0,15,30,45 * * * *") shouldBe true
        CronExpression.isValid("30 4 1,15 * 5") shouldBe true
    }

    @Test
    fun `invalid cron expressions are rejected`() {
        CronExpression.isValid("* * *") shouldBe false // Only 3 fields
        CronExpression.isValid("60 * * * *") shouldBe false // Minute 60 out of bounds
        CronExpression.isValid("* 24 * * *") shouldBe false // Hour 24 out of bounds
        CronExpression.isValid("* * 0 * *") shouldBe false // Day 0 out of bounds
        CronExpression.isValid("* * 32 * *") shouldBe false // Day 32 out of bounds
        CronExpression.isValid("* * * 13 *") shouldBe false // Month 13 out of bounds
        CronExpression.isValid("* * * * 8") shouldBe false // Day of week 8 out of bounds
        CronExpression.isValid("not a cron") shouldBe false
    }

    @Test
    fun `cron matching evaluates field constraints properly`() {
        val parsed = CronExpression.parse("*/15 14 * * 1-5")
        val cron = parsed ?: error("Cron parsing failed")
        cron.matches(minute = 0, hour = 14, dayOfMonth = 10, month = 5, dayOfWeek = 2) shouldBe true
        cron.matches(minute = 15, hour = 14, dayOfMonth = 10, month = 5, dayOfWeek = 5) shouldBe true
        cron.matches(minute = 10, hour = 14, dayOfMonth = 10, month = 5, dayOfWeek = 2) shouldBe false
        cron.matches(minute = 0, hour = 15, dayOfMonth = 10, month = 5, dayOfWeek = 2) shouldBe false
        cron.matches(minute = 0, hour = 14, dayOfMonth = 10, month = 5, dayOfWeek = 6) shouldBe false
    }

    // ─── Message Trigger Tests ────────────────────────────────────────────────

    @Test
    fun `MessageReceived trigger ignores emoji reactions`() = runTest {
        val trigger = AutomationTrigger.MessageReceived()
        val flow = triggerSource.flowFor(trigger)

        val collected = mutableListOf<TriggerEvent>()
        val job = launch { flow.collect { collected.add(it) } }
        testScheduler.advanceUntilIdle()

        // 1. Emit an emoji reaction (emoji != 0)
        val reactionPacket =
            MeshPacket.Builder()
                .also { wb ->
                    wb.id = 1
                    wb.from = 100
                    wb.channel = 0
                    wb.decoded =
                        Data.Builder()
                            .also { db ->
                                db.portnum = PortNum.TEXT_MESSAGE_APP
                                db.payload = "👍".encodeUtf8()
                                db.emoji = 0x1F44D
                            }
                            .build()
                }
                .build()

        fakeServiceRepo.emitMeshPacket(reactionPacket)
        testScheduler.advanceUntilIdle()

        collected.isEmpty() shouldBe true

        // 2. Emit a normal text message (emoji == 0)
        val textPacket =
            MeshPacket.Builder()
                .also { wb ->
                    wb.id = 2
                    wb.from = 100
                    wb.channel = 0
                    wb.decoded =
                        Data.Builder()
                            .also { db ->
                                db.portnum = PortNum.TEXT_MESSAGE_APP
                                db.payload = "Hello mesh".encodeUtf8()
                                db.emoji = 0
                            }
                            .build()
                }
                .build()

        fakeServiceRepo.emitMeshPacket(textPacket)
        testScheduler.advanceUntilIdle()

        collected.size shouldBe 1
        collected.first().messageText shouldBe "Hello mesh"
        job.cancel()
    }

    @Test
    fun `ReactionReceived trigger filters by emoji`() = runTest {
        val trigger = AutomationTrigger.ReactionReceived(emoji = "🚨")
        val flow = triggerSource.flowFor(trigger)

        val collected = mutableListOf<TriggerEvent>()
        val job = launch { flow.collect { collected.add(it) } }
        testScheduler.advanceUntilIdle()

        // Plain text packet should be ignored
        val plainTextPacket =
            MeshPacket.Builder()
                .also { wb ->
                    wb.id = 1
                    wb.from = 200
                    wb.channel = 0
                    wb.decoded =
                        Data.Builder()
                            .also { db ->
                                db.portnum = PortNum.TEXT_MESSAGE_APP
                                db.payload = "Emergency".encodeUtf8()
                                db.emoji = 0
                            }
                            .build()
                }
                .build()
        fakeServiceRepo.emitMeshPacket(plainTextPacket)
        testScheduler.advanceUntilIdle()
        collected.isEmpty() shouldBe true

        // Different reaction emoji should be ignored
        val thumbReactionPacket =
            MeshPacket.Builder()
                .also { wb ->
                    wb.id = 2
                    wb.from = 200
                    wb.channel = 0
                    wb.decoded =
                        Data.Builder()
                            .also { db ->
                                db.portnum = PortNum.TEXT_MESSAGE_APP
                                db.payload = "👍".encodeUtf8()
                                db.emoji = 1
                            }
                            .build()
                }
                .build()
        fakeServiceRepo.emitMeshPacket(thumbReactionPacket)
        testScheduler.advanceUntilIdle()
        collected.isEmpty() shouldBe true

        // Matching emoji should trigger
        val sirenReactionPacket =
            MeshPacket.Builder()
                .also { wb ->
                    wb.id = 3
                    wb.from = 200
                    wb.channel = 0
                    wb.decoded =
                        Data.Builder()
                            .also { db ->
                                db.portnum = PortNum.TEXT_MESSAGE_APP
                                db.payload = "🚨".encodeUtf8()
                                db.emoji = 1
                            }
                            .build()
                }
                .build()
        fakeServiceRepo.emitMeshPacket(sirenReactionPacket)
        testScheduler.advanceUntilIdle()

        collected.size shouldBe 1
        collected.first().emoji shouldBe "🚨"
        job.cancel()
    }

    // ─── NodeBatteryLow Edge Detection ────────────────────────────────────────

    @Test
    fun `NodeBatteryLow triggers only on threshold crossing edge`() = runTest {
        val trigger = AutomationTrigger.NodeBatteryLow(nodeId = 42, thresholdPercent = 20)
        val flow = triggerSource.flowFor(trigger)

        val collected = mutableListOf<TriggerEvent>()
        val job = launch { flow.collect { collected.add(it) } }
        testScheduler.advanceUntilIdle()

        fun makeNode(battery: Int) = Node(
            num = 42,
            user = User.Builder().also { it.long_name = "Node-42" }.build(),
            deviceMetrics = DeviceMetrics.Builder().also { it.battery_level = battery }.build(),
        )

        // 1. Initial state: battery is 50% (no trigger)
        fakeNodeRepo.setNodes(listOf(makeNode(battery = 50)))
        testScheduler.advanceUntilIdle()
        collected.isEmpty() shouldBe true

        // 2. Battery drops to 15% (crosses <= 20% from > 20% -> must trigger!)
        fakeNodeRepo.setNodes(listOf(makeNode(battery = 15)))
        testScheduler.advanceUntilIdle()
        collected.size shouldBe 1
        collected.first().batteryLevel shouldBe 15

        // 3. Battery drops to 10% (was already <= 20% -> edge detection must NOT re-trigger)
        fakeNodeRepo.setNodes(listOf(makeNode(battery = 10)))
        testScheduler.advanceUntilIdle()
        collected.size shouldBe 1

        job.cancel()
    }
}

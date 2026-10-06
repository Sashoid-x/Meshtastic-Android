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
package org.meshtastic.core.automation.engine

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.automation.model.AutomationAction
import org.meshtastic.core.automation.model.AutomationCondition
import org.meshtastic.core.automation.model.AutomationLog
import org.meshtastic.core.automation.model.AutomationRule
import org.meshtastic.core.automation.model.AutomationTrigger
import org.meshtastic.core.automation.model.LogicalOperator
import org.meshtastic.core.automation.repository.AutomationRepository
import org.meshtastic.core.automation.trigger.TriggerSource
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Unit tests for [AutomationEngine].
 *
 * All tests use in-memory fakes instead of Mokkery to avoid a KSP dependency on the new module.
 */
class AutomationEngineTest {

    // ─── Fakes ───────────────────────────────────────────────────────────────

    private val executedActions = mutableListOf<Pair<AutomationAction, TriggerEvent>>()
    private val savedLogs = mutableListOf<AutomationLog>()
    private val savedFires = mutableListOf<Pair<String, Long>>()

    private val nodeAppearedFlow = MutableSharedFlow<TriggerEvent>(extraBufferCapacity = 16)

    private val fakeActionExecutor =
        object : ActionExecutor {
            override suspend fun execute(action: AutomationAction, event: TriggerEvent, dryRun: Boolean): String? {
                executedActions += action to event
                return null
            }
        }

    private val fakeTriggerSource =
        object : TriggerSource {
            override fun flowFor(trigger: AutomationTrigger): Flow<TriggerEvent> = when (trigger) {
                is AutomationTrigger.NodeAppeared -> nodeAppearedFlow.asSharedFlow()
                else -> emptyFlow()
            }
        }

    private lateinit var fakeRepo: FakeAutomationRepository
    private lateinit var engine: AutomationEngine

    // ─── Setup ───────────────────────────────────────────────────────────────

    @BeforeTest
    fun setUp() {
        fakeRepo = FakeAutomationRepository(savedFires, savedLogs)
        engine = AutomationEngine(fakeRepo, fakeTriggerSource, fakeActionExecutor)
        executedActions.clear()
        savedLogs.clear()
        savedFires.clear()
    }

    // ─── Tests ───────────────────────────────────────────────────────────────

    @Test
    fun `enabled rule fires action when trigger emits`() = runTest {
        val rule =
            buildRule(
                trigger = AutomationTrigger.NodeAppeared(),
                actions = listOf(AutomationAction.SendMessage("hello", channelIndex = 0)),
            )
        fakeRepo.addRule(rule)
        engine.start(this)
        // Let the engine subscribe to the trigger flow before emitting
        testScheduler.advanceUntilIdle()

        nodeAppearedFlow.emit(TriggerEvent(nodeId = 42, nodeName = "TEST"))
        testScheduler.advanceUntilIdle()
        engine.stop()

        executedActions.size shouldBe 1
        val (action, event) = executedActions[0]
        (action as AutomationAction.SendMessage).text shouldBe "hello"
        event.nodeId shouldBe 42
    }

    @Test
    fun `disabled rule does not start observer`() = runTest {
        val rule =
            buildRule(
                isEnabled = false,
                trigger = AutomationTrigger.NodeAppeared(),
                actions = listOf(AutomationAction.SendMessage("never")),
            )
        fakeRepo.addRule(rule)
        engine.start(this)
        testScheduler.advanceUntilIdle()

        nodeAppearedFlow.emit(TriggerEvent(nodeId = 1))
        testScheduler.advanceUntilIdle()
        engine.stop()

        executedActions shouldBe emptyList()
    }

    @Test
    fun `condition NodeNameContains filters events`() = runTest {
        val rule =
            buildRule(
                trigger = AutomationTrigger.NodeAppeared(),
                conditions = listOf(AutomationCondition.NodeNameContains("MESHTASTIC")),
                actions = listOf(AutomationAction.ShowNotification("Alert", "Node found")),
            )
        fakeRepo.addRule(rule)
        engine.start(this)
        testScheduler.advanceUntilIdle()

        // Name does NOT match → should be filtered
        nodeAppearedFlow.emit(TriggerEvent(nodeId = 1, nodeName = "RANDOM_NODE"))
        testScheduler.advanceUntilIdle()
        executedActions.size shouldBe 0

        // Name DOES match → should execute
        nodeAppearedFlow.emit(TriggerEvent(nodeId = 2, nodeName = "MESHTASTIC_GW"))
        testScheduler.advanceUntilIdle()
        engine.stop()

        executedActions.size shouldBe 1
    }

    @Test
    fun `rate limit prevents more than MAX_FIRES_PER_WINDOW fires`() = runTest {
        val rule =
            buildRule(
                trigger = AutomationTrigger.NodeAppeared(),
                actions = listOf(AutomationAction.PlaySound()),
            )
        fakeRepo.addRule(rule)
        engine.start(this)
        testScheduler.advanceUntilIdle()

        repeat(AutomationEngine.MAX_FIRES_PER_WINDOW + 5) {
            nodeAppearedFlow.emit(TriggerEvent(nodeId = it))
            testScheduler.advanceUntilIdle()
        }
        engine.stop()

        executedActions.size shouldBe AutomationEngine.MAX_FIRES_PER_WINDOW
    }

    @Test
    fun `editing existing rule immediately updates actions and observer without engine restart`() = runTest {
        val originalRule =
            buildRule(
                id = "rule-edit-1",
                trigger = AutomationTrigger.NodeAppeared(),
                actions = listOf(AutomationAction.SendMessage("original message")),
            )
        fakeRepo.addRule(originalRule)
        engine.start(this)
        testScheduler.advanceUntilIdle()

        // First trigger fires original action
        nodeAppearedFlow.emit(TriggerEvent(nodeId = 1, nodeName = "NODE1"))
        testScheduler.advanceUntilIdle()
        executedActions.size shouldBe 1
        (executedActions[0].first as AutomationAction.SendMessage).text shouldBe "original message"

        // User edits the rule: changes the action
        val updatedRule = originalRule.copy(actions = listOf(AutomationAction.SendMessage("MODIFIED message")))
        fakeRepo.saveRule(updatedRule)
        testScheduler.advanceUntilIdle()

        // Next trigger should execute the MODIFIED action!
        nodeAppearedFlow.emit(TriggerEvent(nodeId = 2, nodeName = "NODE2"))
        testScheduler.advanceUntilIdle()
        engine.stop()

        executedActions.size shouldBe 2
        (executedActions[1].first as AutomationAction.SendMessage).text shouldBe "MODIFIED message"
    }

    @Test
    fun `conditions with OR operator pass if any condition is met`() = runTest {
        val rule =
            buildRule(
                trigger = AutomationTrigger.NodeAppeared(),
                conditionOperator = LogicalOperator.OR,
                conditions =
                listOf(
                    AutomationCondition.NodeNameContains("ALPHA"),
                    AutomationCondition.NodeNameContains("BETA"),
                ),
                actions = listOf(AutomationAction.PlaySound()),
            )
        fakeRepo.addRule(rule)
        engine.start(this)
        testScheduler.advanceUntilIdle()

        // Matches neither
        nodeAppearedFlow.emit(TriggerEvent(nodeId = 1, nodeName = "GAMMA"))
        testScheduler.advanceUntilIdle()
        executedActions.size shouldBe 0

        // Matches second condition (BETA)
        nodeAppearedFlow.emit(TriggerEvent(nodeId = 2, nodeName = "TEST_BETA"))
        testScheduler.advanceUntilIdle()
        executedActions.size shouldBe 1

        // Matches first condition (ALPHA)
        nodeAppearedFlow.emit(TriggerEvent(nodeId = 3, nodeName = "ALPHA_LEADER"))
        testScheduler.advanceUntilIdle()
        engine.stop()

        executedActions.size shouldBe 2
    }

    @Test
    fun `supports extended actions including vibrate reaction location and clipboard`() = runTest {
        val rule =
            buildRule(
                trigger = AutomationTrigger.NodeAppeared(),
                actions =
                listOf(
                    AutomationAction.PlaySound("beep"),
                    AutomationAction.VibrateDevice("double"),
                    AutomationAction.SendReaction("👍"),
                    AutomationAction.BroadcastLocation(),
                    AutomationAction.CopyToClipboard("Node {node_name}"),
                ),
            )
        fakeRepo.addRule(rule)
        engine.start(this)
        testScheduler.advanceUntilIdle()

        nodeAppearedFlow.emit(TriggerEvent(nodeId = 99, nodeName = "MY_NODE"))
        testScheduler.advanceUntilIdle()
        engine.stop()

        executedActions.size shouldBe 5
        executedActions[0].first shouldBe AutomationAction.PlaySound("beep")
        executedActions[1].first shouldBe AutomationAction.VibrateDevice("double")
        executedActions[2].first shouldBe AutomationAction.SendReaction("👍")
        executedActions[3].first shouldBe AutomationAction.BroadcastLocation()
        executedActions[4].first shouldBe AutomationAction.CopyToClipboard("Node {node_name}")
    }

    @Test
    fun `events failing conditions do not consume rate limit quota`() = runTest {
        val rule =
            buildRule(
                trigger = AutomationTrigger.NodeAppeared(),
                conditions = listOf(AutomationCondition.NodeNameContains("TARGET")),
                actions = listOf(AutomationAction.PlaySound()),
            )
        fakeRepo.addRule(rule)
        engine.start(this)
        testScheduler.advanceUntilIdle()

        // 20 non-matching events
        repeat(20) {
            nodeAppearedFlow.emit(TriggerEvent(nodeId = it, nodeName = "OTHER_$it"))
            testScheduler.advanceUntilIdle()
        }
        executedActions.size shouldBe 0

        // Now emit matching events: they must not be rate-limited by the 20 filtered events
        repeat(5) {
            nodeAppearedFlow.emit(TriggerEvent(nodeId = 100 + it, nodeName = "TARGET_$it"))
            testScheduler.advanceUntilIdle()
        }
        engine.stop()

        executedActions.size shouldBe 5
    }

    @Test
    fun `failing action records log failure and does not update lastFiredAt`() = runTest {
        val failingExecutor =
            object : ActionExecutor {
                override suspend fun execute(action: AutomationAction, event: TriggerEvent, dryRun: Boolean): String? =
                    throw IllegalStateException("Transmission failed")
            }
        val testEngine = AutomationEngine(fakeRepo, fakeTriggerSource, failingExecutor)
        val rule =
            buildRule(
                id = "rule-failing",
                trigger = AutomationTrigger.NodeAppeared(),
                actions = listOf(AutomationAction.SendMessage("fail me")),
            )
        fakeRepo.addRule(rule)
        testEngine.start(this)
        testScheduler.advanceUntilIdle()

        nodeAppearedFlow.emit(TriggerEvent(nodeId = 1))
        testScheduler.advanceUntilIdle()
        testEngine.stop()

        fakeRepo.getRule("rule-failing")?.lastFiredAt shouldBe 0L
        savedLogs.size shouldBe 1
        savedLogs[0].success shouldBe false
        savedLogs[0].detail shouldBe "Transmission failed"
    }

    @Test
    fun `failing action does not abort subsequent actions in same rule`() = runTest {
        val mixedExecutor =
            object : ActionExecutor {
                override suspend fun execute(action: AutomationAction, event: TriggerEvent, dryRun: Boolean): String? {
                    if (action is AutomationAction.SendMessage) throw IllegalStateException("RF offline")
                    executedActions += action to event
                    return null
                }
            }
        val testEngine = AutomationEngine(fakeRepo, fakeTriggerSource, mixedExecutor)
        val rule =
            buildRule(
                id = "rule-mixed",
                trigger = AutomationTrigger.NodeAppeared(),
                actions =
                listOf(
                    AutomationAction.SendMessage("will fail"),
                    AutomationAction.PlaySound("beep"),
                ),
            )
        fakeRepo.addRule(rule)
        testEngine.start(this)
        testScheduler.advanceUntilIdle()

        nodeAppearedFlow.emit(TriggerEvent(nodeId = 1))
        testScheduler.advanceUntilIdle()
        testEngine.stop()

        // Second action (PlaySound) must still have executed!
        executedActions.size shouldBe 1
        executedActions[0].first shouldBe AutomationAction.PlaySound("beep")
        savedLogs.size shouldBe 1
        savedLogs[0].success shouldBe false
        savedLogs[0].detail shouldBe "RF offline"
    }

    @Test
    fun `TimeOfDay overnight condition handles midnight rollover`() = runTest {
        val lateNightClock =
            object : kotlin.time.Clock {
                override fun now() = kotlin.time.Instant.parse("2026-10-05T23:00:00Z")
            }
        val lateEngine = AutomationEngine(fakeRepo, fakeTriggerSource, fakeActionExecutor, clock = lateNightClock)
        val rule =
            buildRule(
                id = "rule-overnight",
                trigger = AutomationTrigger.NodeAppeared(),
                conditions = listOf(AutomationCondition.TimeOfDay(startHour = 22, endHour = 6)),
                actions = listOf(AutomationAction.PlaySound()),
            )
        fakeRepo.addRule(rule)
        lateEngine.start(this)
        testScheduler.advanceUntilIdle()

        nodeAppearedFlow.emit(TriggerEvent(nodeId = 1))
        testScheduler.advanceUntilIdle()
        lateEngine.stop()

        executedActions.size shouldBe 1
    }

    @Test
    fun `renaming rule preserves configuration equality`() {
        val rule1 =
            buildRule(
                name = "Old Name",
                trigger = AutomationTrigger.NodeAppeared(),
                actions = listOf(AutomationAction.PlaySound()),
            )
        val rule2 = rule1.copy(name = "New Name")
        rule1.hasSameConfiguration(rule2) shouldBe true
    }

    @Test
    fun `chain depth limit aborts recursion`() = runTest {
        val ruleA =
            buildRule(
                id = "rule-a",
                trigger = AutomationTrigger.NodeAppeared(),
                actions = listOf(AutomationAction.TriggerRule("rule-b")),
            )
        val ruleB =
            buildRule(
                id = "rule-b",
                trigger = AutomationTrigger.NodeAppeared(),
                actions = listOf(AutomationAction.TriggerRule("rule-a")),
            )
        fakeRepo.addRule(ruleA)
        fakeRepo.addRule(ruleB)
        engine.start(this)
        testScheduler.advanceUntilIdle()

        nodeAppearedFlow.emit(TriggerEvent(nodeId = 1))
        testScheduler.advanceUntilIdle()
        engine.stop()

        savedLogs.isNotEmpty() shouldBe true
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun buildRule(
        id: String = "rule-test-1",
        name: String = "Test Rule",
        isEnabled: Boolean = true,
        trigger: AutomationTrigger,
        conditionOperator: LogicalOperator = LogicalOperator.AND,
        conditions: List<AutomationCondition> = emptyList(),
        actions: List<AutomationAction>,
    ) = AutomationRule(
        id = id,
        name = name,
        isEnabled = isEnabled,
        trigger = trigger,
        conditionOperator = conditionOperator,
        conditions = conditions,
        actions = actions,
        createdAt = 0L,
    )
}

// ─── FakeAutomationRepository ─────────────────────────────────────────────────

private class FakeAutomationRepository(
    private val savedFiresList: MutableList<Pair<String, Long>>? = null,
    private val savedLogsList: MutableList<AutomationLog>? = null,
) : AutomationRepository {
    private val rules = mutableListOf<AutomationRule>()
    private val rulesFlow = MutableSharedFlow<List<AutomationRule>>(replay = 1)

    fun addRule(rule: AutomationRule) {
        rules.add(rule)
        rulesFlow.tryEmit(rules.toList())
    }

    override fun observeRules(): Flow<List<AutomationRule>> = rulesFlow

    override fun observeEnabledRules(): Flow<List<AutomationRule>> = rulesFlow.map { it.filter { r -> r.isEnabled } }

    override suspend fun getRule(id: String): AutomationRule? = rules.firstOrNull { it.id == id }

    override suspend fun saveRule(rule: AutomationRule) {
        val idx = rules.indexOfFirst { it.id == rule.id }
        if (idx >= 0) {
            rules[idx] = rule
        } else {
            rules.add(rule)
        }
        rulesFlow.tryEmit(rules.toList())
    }

    override suspend fun deleteRule(id: String) {
        rules.removeAll { it.id == id }
        rulesFlow.tryEmit(rules.toList())
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {}

    override suspend fun recordFire(id: String, timestamp: Long) {
        val idx = rules.indexOfFirst { it.id == id }
        if (idx >= 0) {
            rules[idx] = rules[idx].copy(lastFiredAt = timestamp, fireCount = rules[idx].fireCount + 1)
        }
        savedFiresList?.add(id to timestamp)
    }

    override fun observeLogs(ruleId: String, limit: Int): Flow<List<AutomationLog>> = emptyFlow()

    override suspend fun addLog(log: AutomationLog) {
        savedLogsList?.add(log)
    }
}

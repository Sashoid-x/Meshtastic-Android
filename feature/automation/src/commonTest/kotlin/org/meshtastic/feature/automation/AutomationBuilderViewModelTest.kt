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
package org.meshtastic.feature.automation

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.meshtastic.core.automation.engine.ActionExecutor
import org.meshtastic.core.automation.engine.TriggerEvent
import org.meshtastic.core.automation.model.AutomationAction
import org.meshtastic.core.automation.model.AutomationCondition
import org.meshtastic.core.automation.model.AutomationLog
import org.meshtastic.core.automation.model.AutomationRule
import org.meshtastic.core.automation.model.AutomationTrigger
import org.meshtastic.core.automation.repository.AutomationRepository
import org.meshtastic.core.navigation.AutomationRoute
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationBuilderViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val savedRules = mutableMapOf<String, AutomationRule>()

    private val fakeRepo =
        object : AutomationRepository {
            override fun observeRules(): Flow<List<AutomationRule>> = emptyFlow()

            override fun observeEnabledRules(): Flow<List<AutomationRule>> = emptyFlow()

            override suspend fun getRule(id: String): AutomationRule? = savedRules[id]

            override suspend fun saveRule(rule: AutomationRule) {
                savedRules[rule.id] = rule
            }

            override suspend fun deleteRule(id: String) {
                savedRules.remove(id)
            }

            override suspend fun setEnabled(id: String, enabled: Boolean) = Unit

            override fun observeLogs(ruleId: String, limit: Int): Flow<List<AutomationLog>> = emptyFlow()

            override suspend fun recordFire(id: String, timestamp: Long) = Unit

            override suspend fun addLog(log: AutomationLog) = Unit
        }

    private val fakeActionExecutor =
        object : ActionExecutor {
            override suspend fun execute(action: AutomationAction, event: TriggerEvent, dryRun: Boolean): String =
                "Preview: ${action::class.simpleName}"
        }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        savedRules.clear()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `new rule builder initializes with clean state`() = runTest(testDispatcher) {
        val viewModel = AutomationBuilderViewModel(repository = fakeRepo)

        val state = viewModel.uiState.value
        state.isDirty shouldBe false
        state.isLoading shouldBe false
        state.name shouldBe ""
    }

    @Test
    fun `templateId pre-populates state and marks dirty`() = runTest(testDispatcher) {
        val viewModel =
            AutomationBuilderViewModel(
                route = AutomationRoute.AutomationBuilder(templateId = "panic_button"),
                repository = fakeRepo,
            )

        val state = viewModel.uiState.value
        state.isDirty shouldBe true
        state.name shouldBe "Panic Alarm (🚨)"
        state.trigger shouldBe AutomationTrigger.ReactionReceived(emoji = "🚨")
        state.actions.size shouldBe 2
    }

    @Test
    fun `auto_responder template pre-populates state and marks dirty`() = runTest(testDispatcher) {
        val viewModel =
            AutomationBuilderViewModel(
                route = AutomationRoute.AutomationBuilder(templateId = "auto_responder"),
                repository = fakeRepo,
            )

        val state = viewModel.uiState.value
        state.isDirty shouldBe true
        state.name shouldBe "Auto-Responder (test)"
        state.trigger shouldBe AutomationTrigger.MessageReceived(pattern = "test")
        state.actions shouldBe listOf(AutomationAction.SendReaction(emoji = "{HOP_REACTION}"))
    }

    @Test
    fun `mutations set isDirty to true`() = runTest(testDispatcher) {
        val viewModel = AutomationBuilderViewModel(repository = fakeRepo)

        viewModel.setName("My Custom Rule")
        viewModel.uiState.value.isDirty shouldBe true

        viewModel.addCondition(AutomationCondition.TimeOfDay(startHour = 8, endHour = 18))
        viewModel.uiState.value.conditions.size shouldBe 1
    }

    @Test
    fun `validation catches empty name and empty actions`() = runTest(testDispatcher) {
        val viewModel = AutomationBuilderViewModel(repository = fakeRepo)

        // Clear default action
        viewModel.removeAction(0)
        viewModel.setName("")

        val errors = viewModel.validate()
        errors shouldContain "Rule name cannot be empty"
        errors shouldContain "Rule must have at least one action"
    }

    @Test
    fun `validation catches invalid cron expression in schedule trigger`() = runTest(testDispatcher) {
        val viewModel = AutomationBuilderViewModel(repository = fakeRepo)
        viewModel.setName("Cron Rule")
        viewModel.setTrigger(AutomationTrigger.Schedule(cronExpression = "invalid cron"))

        val errors = viewModel.validate()
        errors.any { it.contains("Invalid cron expression") } shouldBe true
    }

    @Test
    fun `test action executes dry-run and updates uiState`() = runTest(testDispatcher) {
        val viewModel =
            AutomationBuilderViewModel(
                repository = fakeRepo,
                actionExecutor = fakeActionExecutor,
            )

        viewModel.testAction(AutomationAction.PlaySound("beep"))
        testScheduler.advanceUntilIdle()

        viewModel.uiState.value.testActionResult shouldContain "Preview: PlaySound"
    }

    @Test
    fun `saveRule persists rule to repository and resets isDirty`() = runTest(testDispatcher) {
        val viewModel = AutomationBuilderViewModel(repository = fakeRepo)
        viewModel.setName("Saved Rule")

        var savedCallbackCalled = false
        viewModel.saveRule { savedCallbackCalled = true }
        testScheduler.advanceUntilIdle()

        savedCallbackCalled shouldBe true
        viewModel.uiState.value.isSaved shouldBe true
        viewModel.uiState.value.isDirty shouldBe false
        savedRules.size shouldBe 1
        savedRules.values.first().name shouldBe "Saved Rule"
    }
}

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
package org.meshtastic.core.data.manager

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single
import org.meshtastic.core.common.di.ServiceScope
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.common.util.safeCatchingAll
import org.meshtastic.core.model.MqttConnectionState
import org.meshtastic.core.model.MqttProbeStatus
import org.meshtastic.core.model.TopologySource
import org.meshtastic.core.network.repository.MQTTRepository
import org.meshtastic.core.network.repository.MQTT_KEEPALIVE_SECONDS
import org.meshtastic.core.network.repository.isCredentialRejection
import org.meshtastic.core.network.repository.mqttTlsConfig
import org.meshtastic.core.network.repository.resolveEndpoint
import org.meshtastic.core.repository.MqttManager
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketHandler
import org.meshtastic.core.repository.ServiceStateWriter
import org.meshtastic.core.repository.TopologyManager
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.getStringSuspend
import org.meshtastic.core.resources.mqtt_error_connection_lost
import org.meshtastic.core.resources.mqtt_error_credentials_rejected
import org.meshtastic.core.resources.mqtt_error_proxy_failed
import org.meshtastic.core.resources.mqtt_error_rejected
import org.meshtastic.core.resources.unknown
import org.meshtastic.mqtt.ConnectionState
import org.meshtastic.mqtt.MqttClient
import org.meshtastic.mqtt.MqttException
import org.meshtastic.mqtt.ProbeResult
import org.meshtastic.mqtt.plus
import org.meshtastic.mqtt.probe
import org.meshtastic.mqtt.transport.tcp.TcpTransportFactory
import org.meshtastic.mqtt.transport.ws.WebSocketTransportFactory
import org.meshtastic.proto.MqttClientProxyMessage
import org.meshtastic.proto.ToRadio
import kotlin.uuid.Uuid

private const val RATE_WINDOW_MS = 5_000L
private const val RATE_WINDOW_SECONDS = 5.0f

@Single
@Suppress("TooManyFunctions")
class MqttManagerImpl(
    private val mqttRepository: MQTTRepository,
    private val packetHandler: PacketHandler,
    private val serviceStateWriter: ServiceStateWriter,
    private val nodeRepository: NodeRepository,
    private val scope: ServiceScope,
    private val topologyManager: Lazy<TopologyManager>,
) : MqttManager {
    private var mqttMessageFlow: Job? = null
    private val _proxyActive = MutableStateFlow(false)
    override val proxyActive: StateFlow<Boolean> = _proxyActive.asStateFlow()

    private val _isClientEnabled = MutableStateFlow(false)
    override val isClientEnabled: StateFlow<Boolean> = _isClientEnabled.asStateFlow()

    private val _messageRate = MutableStateFlow(0f)
    override val messageRate: StateFlow<Float> = _messageRate.asStateFlow()

    private val isRunningFlow = combine(_proxyActive, _isClientEnabled) { proxy, client -> proxy || client }

    private val messageTimestamps = ArrayDeque<Long>()
    private val rateMutex = Mutex()
    private var decayJob: Job? = null

    override val mqttConnectionState: StateFlow<MqttConnectionState> =
        combine(isRunningFlow, mqttRepository.connectionState, mqttRepository.subscriptionRefusal) {
                active,
                libState,
                refusal,
            ->
            when {
                !active -> MqttConnectionState.Inactive

                libState is ConnectionState.Connected && refusal != null ->
                    MqttConnectionState.SubscriptionRefused(
                        refused = refusal.refused.mapValues { (_, code) -> code.name },
                        granted = refusal.granted.size,
                    )

                else -> libState.toAppState()
            }
        }
            .stateIn(scope, SharingStarted.Eagerly, MqttConnectionState.Inactive)

    override fun startProxy(enabled: Boolean, proxyToClientEnabled: Boolean) {
        _proxyActive.value = enabled && proxyToClientEnabled
        syncConnection()
    }

    override fun setClientEnabled(enabled: Boolean) {
        _isClientEnabled.value = enabled
        syncConnection()
    }

    override fun stop() {
        _proxyActive.value = false
        _isClientEnabled.value = false
        syncConnection()
    }

    private fun syncConnection() {
        val shouldBeActive = _proxyActive.value || _isClientEnabled.value
        if (shouldBeActive) {
            startMqttStream()
        } else {
            stopMqttStream()
        }
    }

    private fun startMqttStream() {
        if (mqttMessageFlow?.isActive == true) return
        mqttMessageFlow =
            mqttRepository.proxyMessageFlow
                .onEach { message ->
                    recordIncomingMessage()
                    message.data_?.let { data ->
                        val bytes = data.toByteArray()
                        val envelope = runCatching {
                            org.meshtastic.proto.ServiceEnvelope.ADAPTER.decode(bytes)
                        }
                            .getOrNull()
                        val meshPacket =
                            envelope?.packet
                                ?: runCatching {
                                    org.meshtastic.proto.MeshPacket.ADAPTER.decode(bytes)
                                }
                                    .getOrNull()
                        if (meshPacket != null) {
                            topologyManager.value.processPacket(
                                packet = meshPacket,
                                source = TopologySource.MQTT,
                                gatewayId = envelope?.gateway_id,
                            )
                        }
                    }
                    if (_proxyActive.value) {
                        packetHandler.sendToRadio(
                            ToRadio.Builder().also { wb -> wb.mqttClientProxyMessage = message }.build(),
                        )
                    }
                }
                .catch { throwable ->
                    _proxyActive.value = false
                    _isClientEnabled.value = false
                    stopRateTracking()
                    val message = safeCatchingAll {
                        when {
                            throwable is MqttException.ConnectionRejected && throwable.isCredentialRejection() ->
                                getStringSuspend(Res.string.mqtt_error_credentials_rejected)

                            throwable is MqttException.ConnectionRejected ->
                                getStringSuspend(Res.string.mqtt_error_rejected, throwable.detail())

                            throwable is MqttException.ConnectionLost ->
                                getStringSuspend(Res.string.mqtt_error_connection_lost)

                            else -> getStringSuspend(Res.string.mqtt_error_proxy_failed, throwable.detail())
                        }
                    }
                        .getOrDefault("")
                    serviceStateWriter.setErrorMessage(text = message, severity = Severity.Warn)
                }
                .launchIn(scope)
    }

    private fun stopMqttStream() {
        if (mqttMessageFlow?.isActive == true) {
            Logger.i { "Stopping MQTT connection" }
            mqttMessageFlow?.cancel()
            mqttMessageFlow = null
        }
        stopRateTracking()
    }

    private fun recordIncomingMessage() {
        val now = nowMillis
        scope.launch {
            rateMutex.withLock {
                messageTimestamps.addLast(now)
                pruneTimestamps(now)
                _messageRate.value = messageTimestamps.size / RATE_WINDOW_SECONDS
                decayJob?.cancel()
                decayJob = scope.launch {
                    delay(RATE_WINDOW_MS)
                    rateMutex.withLock {
                        pruneTimestamps(nowMillis)
                        _messageRate.value = messageTimestamps.size / RATE_WINDOW_SECONDS
                    }
                }
            }
        }
    }

    private fun pruneTimestamps(now: Long) {
        val windowStart = now - RATE_WINDOW_MS
        while (messageTimestamps.isNotEmpty() && messageTimestamps.first() < windowStart) {
            messageTimestamps.removeFirst()
        }
    }

    private fun stopRateTracking() {
        decayJob?.cancel()
        decayJob = null
        messageTimestamps.clear()
        _messageRate.value = 0f
    }

    override fun handleMqttProxyMessage(message: MqttClientProxyMessage) {
        val topic = message.topic
        Logger.d { "[mqttClientProxyMessage] $topic" }
        val retained = message.retained == true
        when {
            message.text != null -> {
                mqttRepository.publish(topic, message.text!!.encodeToByteArray(), retained)
            }

            message.data_ != null -> {
                mqttRepository.publish(topic, message.data_!!.toByteArray(), retained)
            }

            else -> {}
        }
    }

    private fun ConnectionState.toAppState(): MqttConnectionState = when (this) {
        is ConnectionState.Connecting -> MqttConnectionState.Connecting

        is ConnectionState.Connected -> MqttConnectionState.Connected

        is ConnectionState.Reconnecting ->
            MqttConnectionState.Reconnecting(attempt = attempt, lastError = lastError?.message)

        is ConnectionState.Disconnected ->
            reason?.let { MqttConnectionState.Disconnected(reason = it.message) }
                ?: MqttConnectionState.Disconnected.Idle
    }

    override suspend fun probe(
        address: String,
        tlsEnabled: Boolean,
        username: String?,
        password: String?,
    ): MqttProbeStatus {
        val endpoint = resolveEndpoint(address, tlsEnabled)
        val result =
            MqttClient.probe(endpoint = endpoint) {
                // probe() requires a transportFactory in 0.4.0 (errors otherwise); mirror the live client,
                // including its scoped private-CA trust hook — otherwise a probe would fail where a connect succeeds.
                val tls = mqttTlsConfig()
                transportFactory = TcpTransportFactory(tls) + WebSocketTransportFactory(tls)
                // Mirror the live client's keepalive too: the library default is 0 (no keepalive),
                // which some brokers reject — misleadingly, as CLIENT_IDENTIFIER_NOT_VALID.
                keepAliveSeconds = MQTT_KEEPALIVE_SECONDS
                // Per-connection random suffix: myId identifies the node (and is null →
                // "unknown" before the node record loads), so two probes can collide on one
                // client-id and evict each other (SESSION_TAKEN_OVER). See MQTTRepositoryImpl.
                clientId = "MeshtasticAndroidMqttProbe-${nodeRepository.myId.value ?: "unknown"}-${Uuid.random()}"
                val user = username?.takeUnless { it.isEmpty() }
                val pass = password?.takeUnless { it.isEmpty() }
                if (user != null) this.username = user
                if (pass != null) password(pass)
            }
        return result.toAppStatus()
    }

    private fun ProbeResult.toAppStatus(): MqttProbeStatus = when (this) {
        is ProbeResult.Success -> {
            val info = serverInfo
            val summary = buildList {
                info.assignedClientIdentifier?.let { add("client=$it") }
                info.maximumQosOrdinal?.let { add("maxQoS=$it") }
                info.serverKeepAliveSeconds?.let { add("keepalive=${it}s") }
            }
                .joinToString(", ")
                .ifEmpty { null }
            MqttProbeStatus.Success(serverInfo = summary)
        }

        is ProbeResult.Rejected ->
            MqttProbeStatus.Rejected(
                reasonCode = reasonCode.value,
                reason = message,
                serverReference = serverReference,
            )

        is ProbeResult.DnsFailure -> MqttProbeStatus.DnsFailure(message = cause.message)

        is ProbeResult.TcpFailure -> MqttProbeStatus.TcpFailure(message = cause.message)

        is ProbeResult.TlsFailure -> MqttProbeStatus.TlsFailure(message = cause.message)

        is ProbeResult.Timeout -> MqttProbeStatus.Timeout(timeoutMs = durationMs)

        is ProbeResult.Other -> MqttProbeStatus.Other(message = cause.message)
    }
}

/** Failure detail for a user-facing message; a throwable without a message still needs a placeholder to substitute. */
private suspend fun Throwable.detail(): String = message ?: getStringSuspend(Res.string.unknown)

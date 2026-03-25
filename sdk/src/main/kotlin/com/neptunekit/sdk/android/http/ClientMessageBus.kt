package com.neptunekit.sdk.android.http

import com.neptunekit.sdk.android.model.IngestLogRecord
import java.time.Instant

internal enum class BusDirection(val rawValue: String) {
    CLI_TO_CLIENT("cli_to_client"),
    CLIENT_TO_CLI("client_to_cli"),
}

internal enum class BusKind(val rawValue: String) {
    COMMAND("command"),
    EVENT("event"),
    LOG("log"),
}

internal enum class BusAckStatus(val rawValue: String) {
    OK("ok"),
    ERROR("error"),
}

internal data class ClientBusEvent(
    val name: String,
    val attributes: Map<String, String>? = null,
)

internal data class BusEnvelope(
    val requestId: String? = null,
    val direction: String,
    val kind: String,
    val command: String? = null,
    val logRecord: IngestLogRecord? = null,
    val event: ClientBusEvent? = null,
    val timestamp: String? = null,
)

internal data class BusAck(
    val requestId: String? = null,
    val command: String? = null,
    val status: String,
    val message: String? = null,
    val timestamp: String,
)

internal class ClientMessageBus(
    private val now: () -> Instant = { Instant.now() },
) {
    fun makeLogEnvelope(record: IngestLogRecord, requestId: String? = null): BusEnvelope =
        BusEnvelope(
            requestId = requestId,
            direction = BusDirection.CLIENT_TO_CLI.rawValue,
            kind = BusKind.LOG.rawValue,
            logRecord = record,
            timestamp = now().toString(),
        )

    fun acknowledgeInboundCommand(envelope: BusEnvelope): BusAck {
        if (envelope.direction != BusDirection.CLI_TO_CLIENT.rawValue) {
            return makeAck(envelope, BusAckStatus.ERROR, "unsupported direction")
        }
        if (envelope.kind != BusKind.COMMAND.rawValue) {
            return makeAck(envelope, BusAckStatus.ERROR, "unsupported message kind")
        }

        val command = envelope.command?.trim().orEmpty()
        if (command.isEmpty()) {
            return makeAck(envelope, BusAckStatus.ERROR, "missing command")
        }
        if (command != "ping") {
            return makeAck(envelope, BusAckStatus.ERROR, "unsupported command")
        }
        return makeAck(envelope, BusAckStatus.OK, null)
    }

    private fun makeAck(envelope: BusEnvelope, status: BusAckStatus, message: String?): BusAck =
        BusAck(
            requestId = envelope.requestId,
            command = envelope.command?.trim()?.ifEmpty { null },
            status = status.rawValue,
            message = message,
            timestamp = now().toString(),
        )
}

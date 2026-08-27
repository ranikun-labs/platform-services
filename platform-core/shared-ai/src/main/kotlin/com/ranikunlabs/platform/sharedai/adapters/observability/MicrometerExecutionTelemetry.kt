package com.ranikunlabs.platform.sharedai.adapters.observability

import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.ports.ExecutionMeasurement
import com.ranikunlabs.platform.sharedai.ports.ExecutionTelemetry
import io.micrometer.core.instrument.MeterRegistry
import java.lang.System.Logger.Level
import java.util.concurrent.TimeUnit

class MicrometerExecutionTelemetry(private val meterRegistry: MeterRegistry) : ExecutionTelemetry {
    private val logger = System.getLogger(MicrometerExecutionTelemetry::class.java.name)

    override fun record(
        context: ExecutionContext,
        skillId: String,
        measurement: ExecutionMeasurement,
    ) {
        meterRegistry.timer(
            "platform.shared_ai.execution",
            "skill",
            skillId,
            "stage",
            measurement.stage.name.lowercase(),
            "failure",
            measurement.failureCategory ?: "none",
        ).record(measurement.durationNanos, TimeUnit.NANOSECONDS)
        logger.log(
            Level.DEBUG,
            "Shared AI execution={0} skill={1} stage={2} durationNanos={3} failure={4}",
            context.executionReference,
            skillId,
            measurement.stage,
            measurement.durationNanos,
            measurement.failureCategory ?: "none",
        )
    }
}

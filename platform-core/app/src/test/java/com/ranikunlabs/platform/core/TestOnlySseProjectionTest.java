package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.sharedai.application.ExecutionStreamCancellation;
import com.ranikunlabs.platform.sharedai.application.ExecutionStreamEvent;
import com.ranikunlabs.platform.sharedai.application.TechnicalFailureCategory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestOnlySseProjectionTest {
    @Test
    void mapsFailureToNamedErrorAndIgnoresLaterEvents() {
        var sender = new RecordingSender();
        var projection = new TestOnlySseProjection(sender);

        projection.onEvent(new ExecutionStreamEvent.Started("execution-a"));
        projection.onEvent(new ExecutionStreamEvent.Failed(
                "execution-a", TechnicalFailureCategory.RATE_LIMITED));
        projection.onEvent(new ExecutionStreamEvent.ContentDelta("execution-a", "late"));

        assertThat(sender.eventNames).containsExactly("started", "error");
        assertThat(sender.completions).isEqualTo(1);
    }

    @Test
    void disconnectCancellationPropagatesExactlyOnce() {
        var sender = new RecordingSender();
        var projection = new TestOnlySseProjection(sender);
        var cancellations = new AtomicInteger();
        projection.attach((ExecutionStreamCancellation) cancellations::incrementAndGet);

        projection.cancel();
        projection.cancel();

        assertThat(cancellations).hasValue(1);
    }

    @Test
    void deliveryFailureCancelsTheProvider() {
        var sender = new RecordingSender();
        sender.failDelivery = true;
        var projection = new TestOnlySseProjection(sender);
        var cancellations = new AtomicInteger();
        projection.attach((ExecutionStreamCancellation) cancellations::incrementAndGet);

        assertThatThrownBy(() -> projection.onEvent(
                new ExecutionStreamEvent.ContentDelta("execution-a", "chunk")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cancellations).hasValue(1);
        assertThat(sender.errors).isEqualTo(1);
    }

    private static final class RecordingSender implements TestOnlySseProjection.SseEventSender {
        private final List<String> eventNames = new ArrayList<>();
        private int completions;
        private int errors;
        private boolean failDelivery;

        @Override
        public void send(String eventName, Object data) throws IOException {
            if (failDelivery) {
                throw new IOException("disconnected");
            }
            eventNames.add(eventName);
        }

        @Override
        public void complete() {
            completions++;
        }

        @Override
        public void completeWithError(Throwable failure) {
            errors++;
        }
    }
}

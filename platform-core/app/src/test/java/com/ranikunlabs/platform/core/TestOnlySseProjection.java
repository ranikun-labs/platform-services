package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.sharedai.application.ExecutionStreamCancellation;
import com.ranikunlabs.platform.sharedai.application.ExecutionStreamEvent;
import com.ranikunlabs.platform.sharedai.application.ExecutionStreamObserver;
import com.ranikunlabs.platform.sharedai.core.EvidenceItem;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class TestOnlySseProjection implements ExecutionStreamObserver {
    private final SseEventSender sender;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private final AtomicReference<ExecutionStreamCancellation> cancellation = new AtomicReference<>();

    TestOnlySseProjection(SseEventSender sender) {
        this.sender = sender;
    }

    void attach(ExecutionStreamCancellation cancellation) {
        this.cancellation.set(cancellation);
        if (cancellationRequested.get()) {
            cancellation.cancel();
        }
    }

    void cancel() {
        if (terminal.compareAndSet(false, true)) {
            cancellationRequested.set(true);
            var current = cancellation.get();
            if (current != null) {
                current.cancel();
            }
        }
    }

    @Override
    public void onEvent(ExecutionStreamEvent event) {
        if (terminal.get()) {
            return;
        }
        try {
            if (event instanceof ExecutionStreamEvent.Started started) {
                sender.send("started", Map.of("executionReference", started.getExecutionReference()));
            } else if (event instanceof ExecutionStreamEvent.ContentDelta delta) {
                sender.send("delta", Map.of(
                        "executionReference", delta.getExecutionReference(),
                        "text", delta.getText()));
            } else if (event instanceof ExecutionStreamEvent.Completed completed) {
                sender.send("completed", new CompletedData(
                        completed.getExecutionReference(),
                        completed.getModelSourceReference(),
                        completed.getEvidence().getItems().stream()
                                .map(EvidenceItem::getKind)
                                .map(Enum::name)
                                .toList()));
                if (terminal.compareAndSet(false, true)) {
                    sender.complete();
                }
            } else if (event instanceof ExecutionStreamEvent.Failed failed) {
                sender.send("error", Map.of(
                        "executionReference", failed.getExecutionReference(),
                        "category", failed.getCategory().name()));
                if (terminal.compareAndSet(false, true)) {
                    sender.complete();
                }
            }
        } catch (IOException | RuntimeException transportFailure) {
            cancellationRequested.set(true);
            terminal.set(true);
            var current = cancellation.get();
            if (current != null) {
                current.cancel();
            }
            sender.completeWithError(transportFailure);
            throw new IllegalStateException("SSE delivery failed", transportFailure);
        }
    }

    record CompletedData(
            String executionReference,
            String modelSourceReference,
            List<String> evidenceCategories) {
    }

    interface SseEventSender {
        void send(String eventName, Object data) throws IOException;

        void complete();

        void completeWithError(Throwable failure);
    }
}

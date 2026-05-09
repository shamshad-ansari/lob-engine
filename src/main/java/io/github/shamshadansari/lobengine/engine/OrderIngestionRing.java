package io.github.shamshadansari.lobengine.engine;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import io.github.shamshadansari.lobengine.domain.EngineEvent;
import io.github.shamshadansari.lobengine.validation.OrderSubmission;
import io.github.shamshadansari.lobengine.validation.OrderValidator;
import io.github.shamshadansari.lobengine.validation.ValidatedOrderCommand;
import io.github.shamshadansari.lobengine.validation.ValidationResult;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;

public final class OrderIngestionRing implements AutoCloseable {

    private static final EventTranslatorOneArg<EngineEvent, ValidatedOrderCommand> COMMAND_TRANSLATOR =
        (event, sequence, command) -> copyCommand(command, event);

    private final Disruptor<EngineEvent> disruptor;

    private RingBuffer<EngineEvent> ringBuffer;
    private boolean started;

    public OrderIngestionRing(MatchingEngine engine, int bufferSize) {
        this(new EngineEventHandler(engine), bufferSize);
    }

    public OrderIngestionRing(EngineEventHandler handler, int bufferSize) {
        if (bufferSize <= 0 || Integer.bitCount(bufferSize) != 1) {
            throw new IllegalArgumentException("bufferSize must be a positive power of two");
        }

        this.disruptor = new Disruptor<>(
            EngineEvent::new,
            bufferSize,
            consumerThreadFactory(),
            ProducerType.MULTI,
            new BlockingWaitStrategy()
        );
        this.disruptor.handleEventsWith(Objects.requireNonNull(handler, "handler"));
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        ringBuffer = disruptor.start();
        started = true;
    }

    public void publishValidated(ValidatedOrderCommand command) {
        Objects.requireNonNull(command, "command");
        if (!started) {
            throw new IllegalStateException("OrderIngestionRing must be started before publishing");
        }
        ringBuffer.publishEvent(COMMAND_TRANSLATOR, command);
    }

    public ValidationResult publishNew(OrderSubmission submission, OrderValidator validator) {
        ValidationResult result = Objects.requireNonNull(validator, "validator").validateNew(submission);
        publishIfValid(result);
        return result;
    }

    public ValidationResult publishCancel(EngineEvent event, OrderValidator validator) {
        ValidationResult result = Objects.requireNonNull(validator, "validator").validateCancel(event);
        publishIfValid(result);
        return result;
    }

    public ValidationResult publishModify(EngineEvent event, OrderValidator validator) {
        ValidationResult result = Objects.requireNonNull(validator, "validator").validateModify(event);
        publishIfValid(result);
        return result;
    }

    public synchronized void shutdown() {
        if (started) {
            disruptor.shutdown();
            started = false;
            ringBuffer = null;
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    private void publishIfValid(ValidationResult result) {
        if (result.ok) {
            publishValidated(result.command);
        }
    }

    private static void copyCommand(ValidatedOrderCommand command, EngineEvent event) {
        event.type           = command.type;
        event.instrumentId   = command.instrumentId;
        event.timestampNanos = command.timestampNanos;
        event.orderId        = command.orderId;
        event.clientOrderId  = command.clientOrderId;
        event.side           = command.side;
        event.orderType      = command.orderType;
        event.priceTicks     = command.priceTicks;
        event.qty            = command.qty;
        event.targetOrderId  = command.targetOrderId;
        event.newPriceTicks  = command.newPriceTicks;
        event.newQty         = command.newQty;
    }

    private static ThreadFactory consumerThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "lob-engine-disruptor-consumer");
            thread.setDaemon(true);
            return thread;
        };
    }
}

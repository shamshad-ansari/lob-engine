package io.github.shamshadansari.lobengine.engine;

import com.lmax.disruptor.EventHandler;
import io.github.shamshadansari.lobengine.domain.EngineEvent;

import java.util.Objects;

/**
 * Single-consumer Disruptor handler that dispatches validated engine events.
 */
public final class EngineEventHandler implements EventHandler<EngineEvent> {

    private final MatchingEngine engine;

    public EngineEventHandler(MatchingEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public void onEvent(EngineEvent event, long sequence, boolean endOfBatch) {
        try {
            engine.processEvent(event);
        } finally {
            event.reset();
        }
    }
}

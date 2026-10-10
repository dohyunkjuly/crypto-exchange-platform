package com.exchange.bff.marketws;

import com.exchange.bff.common.errors.BffWsErrorCode;
import com.exchange.core.api.exception.ApiException;
import com.exchange.marketdata.stream.ProtocolErrors;
import com.exchange.marketdata.stream.WsMessage;
import com.exchange.marketdata.stream.exception.InvalidGroupingException;
import com.exchange.marketdata.stream.exception.InvalidIntervalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * This app's codes for the shared market socket -- what {@code HttpApiExceptionHandler} does for
 * HTTP, for this transport. A named failure goes out with its code; the shared channels' plain
 * exceptions map here, per AGENTS.md; anything else is a bug, loud in the log and opaque on the
 * wire.
 */
@Component
public class BffProtocolErrors implements ProtocolErrors {

    private static final Logger log = LoggerFactory.getLogger(BffProtocolErrors.class);

    @Override
    public WsMessage<Void> malformed() {
        return error(null, BffWsErrorCode.MALFORMED_MESSAGE, null);
    }

    @Override
    public WsMessage<Void> symbolRequired(Long id) {
        return error(id, BffWsErrorCode.SYMBOL_REQUIRED, null);
    }

    @Override
    public WsMessage<Void> unknownAction(Long id, String action) {
        return error(id, BffWsErrorCode.UNKNOWN_ACTION, "Unknown action: " + action);
    }

    @Override
    public WsMessage<Void> tooManySubscriptions(Long id) {
        return error(id, BffWsErrorCode.TOO_MANY_SUBSCRIPTIONS, null);
    }

    @Override
    public WsMessage<Void> unknownChannel(Long id, String channel) {
        return error(id, BffWsErrorCode.UNKNOWN_CHANNEL, "Unknown channel: " + channel);
    }

    @Override
    public WsMessage<Void> render(Long id, Throwable failure) {
        if (failure instanceof InvalidIntervalException e) {
            return error(id, BffWsErrorCode.INVALID_INTERVAL, e.getMessage());
        }
        if (failure instanceof InvalidGroupingException e) {
            return error(id, BffWsErrorCode.INVALID_GROUPING, e.getMessage());
        }
        if (failure instanceof ApiException e) {
            return WsMessage.error(id, e.getErrorCode().getCode(), e.getMessage());
        }
        log.error("Unhandled exception on the market WebSocket", failure);
        return error(id, BffWsErrorCode.INTERNAL_ERROR, null);
    }

    private static WsMessage<Void> error(Long id, BffWsErrorCode code, String message) {
        return WsMessage.error(id, code.getCode(), message == null ? code.getMessage() : message);
    }
}

package com.exchange.spot.openapi.ws;

import com.exchange.core.api.exception.ApiException;
import com.exchange.marketdata.stream.ProtocolErrors;
import com.exchange.marketdata.stream.WsMessage;
import com.exchange.marketdata.stream.exception.InvalidGroupingException;
import com.exchange.marketdata.stream.exception.InvalidIntervalException;
import com.exchange.spot.openapi.common.errors.OpenApiWsErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** This app's codes for the shared market socket; see web-bff's {@code BffProtocolErrors} for the
 *  same mapping with that app's numbers. An {@code ApiException} keeps whatever code it carries,
 *  which is how the signed-request family reaches the socket at {@code auth}. */
@Component
public class OpenApiProtocolErrors implements ProtocolErrors {

    private static final Logger log = LoggerFactory.getLogger(OpenApiProtocolErrors.class);

    @Override
    public WsMessage<Void> malformed() {
        return error(null, OpenApiWsErrorCode.MALFORMED_MESSAGE, null);
    }

    @Override
    public WsMessage<Void> symbolRequired(Long id) {
        return error(id, OpenApiWsErrorCode.SYMBOL_REQUIRED, null);
    }

    @Override
    public WsMessage<Void> unknownAction(Long id, String action) {
        return error(id, OpenApiWsErrorCode.UNKNOWN_ACTION, "Unknown action: " + action);
    }

    @Override
    public WsMessage<Void> tooManySubscriptions(Long id) {
        return error(id, OpenApiWsErrorCode.TOO_MANY_SUBSCRIPTIONS, null);
    }

    @Override
    public WsMessage<Void> unknownChannel(Long id, String channel) {
        return error(id, OpenApiWsErrorCode.UNKNOWN_CHANNEL, "Unknown channel: " + channel);
    }

    @Override
    public WsMessage<Void> render(Long id, Throwable failure) {
        if (failure instanceof InvalidIntervalException e) {
            return error(id, OpenApiWsErrorCode.INVALID_INTERVAL, e.getMessage());
        }
        if (failure instanceof InvalidGroupingException e) {
            return error(id, OpenApiWsErrorCode.INVALID_GROUPING, e.getMessage());
        }
        if (failure instanceof ApiException e) {
            return WsMessage.error(id, e.getErrorCode().getCode(), e.getMessage());
        }
        log.error("Unhandled exception on the open-api socket", failure);
        return error(id, OpenApiWsErrorCode.INTERNAL_ERROR, null);
    }

    static WsMessage<Void> error(Long id, OpenApiWsErrorCode code, String message) {
        return WsMessage.error(id, code.getCode(), message == null ? code.getMessage() : message);
    }
}

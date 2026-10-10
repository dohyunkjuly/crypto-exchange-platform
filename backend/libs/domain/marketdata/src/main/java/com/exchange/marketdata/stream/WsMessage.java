package com.exchange.marketdata.stream;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The one shape every frame on this socket takes.
 *
 * <pre>
 *   { "type": "trade",      "data": { ... } }
 *   { "type": "subscribed", "id": 7, "data": { "symbol": "BTCUSDT" } }
 *   { "type": "error",      "id": 7, "code": 7003, "message": "Unknown action: subscrbe" }
 * </pre>
 *
 * <p><b>Deliberately not {@code HttpApiResponse}'s shape.</b> That envelope carries
 * {@code status}, {@code code: 0} and {@code message: "OK"} because HTTP has a status line and
 * one response per request. Most frames here are unsolicited pushes, so success carries
 * {@code data} alone and only failures carry {@code code} and {@code message}. What is shared
 * is the part that matters: an error's code is from the owning app's own contract, so 7003
 * means one thing on either transport.
 *
 * <p>The payload nests under {@code data} rather than flattening, so envelope and payload never
 * share a namespace and a client can route on {@code type} without knowing the payload's shape.
 *
 * <p>Absent fields are omitted, not null -- a client testing {@code "id" in msg} must not find
 * a phantom one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WsMessage<T>(String type, Long id, T data, Integer code, String message) {

    /** Unprompted: a trade nobody asked for by message, so there is no id to echo. */
    public static <T> WsMessage<T> push(String type, T data) {
        return new WsMessage<>(type, null, data, null, null);
    }

    /** The reply to a request, carrying back whatever id it supplied (null if none). */
    public static <T> WsMessage<T> reply(Long id, String type, T data) {
        return new WsMessage<>(type, id, data, null, null);
    }

    /** A number and a message, not an {@code ErrorCode}: this module owns no codes and depends on
     *  no app's, so the app that renders a failure passes its own code's values. */
    public static WsMessage<Void> error(Long id, int code, String message) {
        return new WsMessage<>("error", id, null, code, message);
    }
}

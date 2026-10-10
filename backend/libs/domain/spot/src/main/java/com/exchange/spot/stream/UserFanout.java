package com.exchange.spot.stream;

import com.exchange.marketdata.stream.WsMessage;

/** What the private consumers need from a socket: deliver one frame to every connection a user
 *  has open here. web-bff's cookie-authenticated user socket and open-api's key-authenticated
 *  one both implement it; the consumers never see which. */
public interface UserFanout {
    void sendTo(Long userId, WsMessage<?> payload);
}

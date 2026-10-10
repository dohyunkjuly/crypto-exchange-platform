package com.exchange.core.api.error;

import org.springframework.http.HttpStatus;

/** An {@link ErrorCode} rendered over HTTP -- adds the status line a REST response needs
 *  that a WebSocket error frame wouldn't. */
public interface HttpErrorCode extends ErrorCode {

    HttpStatus getStatus();
}

package com.exchange.bff.copy;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.copy.follow.exception.AlreadyFollowingException;
import com.exchange.copy.leader.exception.AlreadyLeaderException;
import com.exchange.copy.follow.exception.CopyAmountTooSmallException;
import com.exchange.copy.follow.exception.CopyBalanceBelowMinimumException;
import com.exchange.copy.follow.exception.CopyLimitReachedException;
import com.exchange.copy.leader.exception.DisplayNameTakenException;
import com.exchange.copy.follow.exception.FollowNotActiveException;
import com.exchange.copy.follow.exception.FollowNotFoundException;
import com.exchange.copy.leader.exception.LeadBalanceBelowMinimumException;
import com.exchange.copy.leader.exception.LeaderNotAcceptingException;
import com.exchange.copy.leader.exception.LeaderNotFoundException;
import com.exchange.copy.leader.exception.LeadAccountNotFlatException;
import com.exchange.copy.leader.exception.LeadAmountOutOfRangeException;
import com.exchange.copy.leader.exception.LeaderStatusException;
import com.exchange.copy.leader.exception.NotALeaderException;
import com.exchange.copy.follow.exception.SelfFollowException;
import com.exchange.core.api.response.HttpApiResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The copy domain's code-less exceptions, as this app's codes (docs/reference/error-codes.md). */
@RestControllerAdvice
public class CopyExceptionAdvice {

    private static final Map<Class<? extends RuntimeException>, BffHttpErrorCode> CODES = Map.ofEntries(
            Map.entry(LeaderNotFoundException.class, BffHttpErrorCode.LEADER_NOT_FOUND),
            Map.entry(LeaderNotAcceptingException.class, BffHttpErrorCode.LEADER_NOT_ACCEPTING),
            Map.entry(AlreadyFollowingException.class, BffHttpErrorCode.ALREADY_FOLLOWING),
            Map.entry(CopyLimitReachedException.class, BffHttpErrorCode.COPY_LIMIT_REACHED),
            Map.entry(CopyAmountTooSmallException.class, BffHttpErrorCode.COPY_AMOUNT_TOO_SMALL),
            Map.entry(SelfFollowException.class, BffHttpErrorCode.CANNOT_FOLLOW_YOURSELF),
            Map.entry(FollowNotFoundException.class, BffHttpErrorCode.FOLLOW_NOT_FOUND),
            Map.entry(FollowNotActiveException.class, BffHttpErrorCode.FOLLOW_NOT_ACTIVE),
            Map.entry(CopyBalanceBelowMinimumException.class, BffHttpErrorCode.COPY_BALANCE_BELOW_MINIMUM),
            Map.entry(AlreadyLeaderException.class, BffHttpErrorCode.ALREADY_LEADER),
            Map.entry(DisplayNameTakenException.class, BffHttpErrorCode.DISPLAY_NAME_TAKEN),
            Map.entry(NotALeaderException.class, BffHttpErrorCode.NOT_A_LEADER),
            Map.entry(LeadBalanceBelowMinimumException.class, BffHttpErrorCode.LEAD_BALANCE_BELOW_MINIMUM),
            Map.entry(LeaderStatusException.class, BffHttpErrorCode.LEADER_NOT_ACTIVE),
            Map.entry(LeadAccountNotFlatException.class, BffHttpErrorCode.LEAD_ACCOUNT_NOT_FLAT),
            Map.entry(LeadAmountOutOfRangeException.class, BffHttpErrorCode.LEAD_AMOUNT_OUT_OF_RANGE));

    @ExceptionHandler({LeaderNotFoundException.class, LeaderNotAcceptingException.class,
            AlreadyFollowingException.class, CopyLimitReachedException.class, CopyAmountTooSmallException.class,
            SelfFollowException.class, FollowNotFoundException.class, FollowNotActiveException.class,
            CopyBalanceBelowMinimumException.class, AlreadyLeaderException.class, DisplayNameTakenException.class,
            NotALeaderException.class, LeadBalanceBelowMinimumException.class, LeaderStatusException.class,
            LeadAccountNotFlatException.class, LeadAmountOutOfRangeException.class})
    public ResponseEntity<HttpApiResponse<Void>> handle(RuntimeException e) {
        BffHttpErrorCode code = CODES.get(e.getClass());
        return ResponseEntity.status(code.getStatus()).body(HttpApiResponse.error(code));
    }
}

package com.exchange.bff.copy;

import com.exchange.bff.copy.dto.CopyViews.PortfolioRequest;
import org.springframework.web.bind.annotation.PatchMapping;
import com.exchange.bff.apikey.dto.ApiKeyView;
import com.exchange.bff.apikey.dto.CreatedApiKeyView;
import com.exchange.bff.subaccount.dto.CreateSubAccountApiKeyRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import com.exchange.bff.common.security.CurrentUser;
import com.exchange.bff.common.security.RequiresTrading;
import com.exchange.bff.copy.dto.CopyViews.ApplyRequest;
import com.exchange.bff.copy.dto.CopyViews.Follow;
import com.exchange.bff.copy.dto.CopyViews.CopyLine;
import com.exchange.bff.copy.dto.CopyViews.FollowDetail;
import com.exchange.bff.copy.dto.CopyViews.LeadTrade;
import com.exchange.bff.copy.dto.CopyViews.ShareLine;
import com.exchange.bff.copy.dto.CopyViews.FollowRequest;
import com.exchange.bff.copy.dto.CopyViews.FundsRequest;
import com.exchange.bff.copy.dto.CopyViews.LeadPage;
import com.exchange.bff.copy.dto.CopyViews.LeadWithdrawRequest;
import com.exchange.bff.copy.dto.CopyViews.LeaderProfile;
import com.exchange.bff.copy.dto.CopyViews.Leaders;
import com.exchange.bff.copy.dto.CopyViews.MyLead;
import com.exchange.user.identity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Copy trading from the web (docs/reference/copy-trading.md). The leaders are public, like the
 * markets: who can be followed is nobody's private data. Everything about a copy account is its
 * owner's, from their session.
 *
 * <p>Following and adding funds are trading, and gated like placing an order. Taking funds out
 * and stopping are not: someone whose verification lapsed must still be able to get out.
 */
@RestController
@RequestMapping("/bff/copy")
public class CopyController {

    private final CopyService copy;

    public CopyController(CopyService copy) {
        this.copy = copy;
    }

    @GetMapping("/leaders")
    public Leaders leaders() {
        return copy.leaders();
    }

    /** Public, like the profile: empty while the leader keeps the portfolio private. */
    @GetMapping("/leaders/{id}/trades")
    public List<LeadTrade> leaderTrades(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page) {
        return copy.leaderTrades(id, page);
    }

    @GetMapping("/leaders/{id}")
    public LeaderProfile leader(@PathVariable UUID id) {
        return copy.leader(id);
    }

    @PostMapping("/follows")
    @RequiresTrading
    @ResponseStatus(HttpStatus.CREATED)
    public Follow follow(@Valid @RequestBody FollowRequest request, @CurrentUser User user, HttpServletRequest http) {
        return copy.follow(user.getId(), request, http.getRemoteAddr());
    }

    @GetMapping("/follows")
    public List<Follow> mine(@CurrentUser User user) {
        return copy.mine(user.getId());
    }

    @GetMapping("/follows/{id}")
    public FollowDetail detail(@PathVariable UUID id, @CurrentUser User user) {
        return copy.detail(user.getId(), id);
    }

    @GetMapping("/follows/{id}/shares")
    public List<ShareLine> shares(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                                  @CurrentUser User user) {
        return copy.shares(user.getId(), id, page);
    }

    @GetMapping("/follows/{id}/copies")
    public List<CopyLine> copies(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                                 @CurrentUser User user) {
        return copy.copies(user.getId(), id, page);
    }

    @PostMapping("/follows/{id}/deposits")
    @RequiresTrading
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addFunds(@PathVariable UUID id, @Valid @RequestBody FundsRequest request, @CurrentUser User user) {
        copy.addFunds(user.getId(), id, request);
    }

    @PostMapping("/follows/{id}/withdrawals")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFunds(@PathVariable UUID id, @Valid @RequestBody FundsRequest request, @CurrentUser User user) {
        copy.removeFunds(user.getId(), id, request);
    }

    /** 204, as every action here with nothing to return: the web client reads no body only from a 204.
     *  The account finishes closing in the background; the follow reads CLOSING meanwhile. */
    @PostMapping("/follows/{id}/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable UUID id, @CurrentUser User user) {
        copy.stop(user.getId(), id);
    }

    /** The customer's own leadership: the application, or the lead account once there is one. */
    @GetMapping("/lead")
    public LeadPage lead(@CurrentUser User user) {
        return copy.lead(user.getId());
    }

    @PostMapping("/lead/apply")
    @RequiresTrading
    @ResponseStatus(HttpStatus.CREATED)
    public MyLead apply(@Valid @RequestBody ApplyRequest request, @CurrentUser User user, HttpServletRequest http) {
        return copy.apply(user.getId(), request, http.getRemoteAddr());
    }

    @PostMapping("/lead/deposits")
    @RequiresTrading
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void fundLead(@Valid @RequestBody FundsRequest request, @CurrentUser User user) {
        copy.fundLead(user.getId(), request);
    }

    @PostMapping("/lead/withdrawals")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdrawLead(@Valid @RequestBody LeadWithdrawRequest request, @CurrentUser User user) {
        copy.withdrawLead(user.getId(), request);
    }

    /** The leader's switch: show the lead account's holdings and trades on the profile, or keep them private. */
    @PatchMapping("/lead/portfolio")
    public MyLead setPortfolioShown(@RequestBody PortfolioRequest request, @CurrentUser User user) {
        return copy.setPortfolioShown(user.getId(), request.shown());
    }

    @PostMapping("/lead/close")
    public MyLead closeLead(@CurrentUser User user) {
        return copy.closeLead(user.getId());
    }

    /** A key for the lead account, behind its owner's authenticator code, as for any sub-account's. */
    @PostMapping("/lead/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedApiKeyView createLeadKey(@Valid @RequestBody CreateSubAccountApiKeyRequest request,
                                           @CurrentUser User user, HttpServletRequest http) {
        return copy.createLeadKey(user.getId(), request, http.getRemoteAddr());
    }

    @GetMapping("/lead/api-keys")
    public List<ApiKeyView> leadKeys(@CurrentUser User user) {
        return copy.leadKeys(user.getId());
    }

    @DeleteMapping("/lead/api-keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeLeadKey(@PathVariable UUID keyId, @CurrentUser User user, HttpServletRequest http) {
        copy.revokeLeadKey(user.getId(), keyId, http.getRemoteAddr());
    }
}

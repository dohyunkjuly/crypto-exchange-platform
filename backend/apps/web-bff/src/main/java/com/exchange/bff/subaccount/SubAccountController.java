package com.exchange.bff.subaccount;

import com.exchange.bff.apikey.dto.ApiKeyView;
import com.exchange.bff.apikey.dto.CreatedApiKeyView;
import com.exchange.bff.common.security.CurrentUser;
import com.exchange.bff.order.dto.CancelAllResponse;
import com.exchange.bff.order.dto.CancelOrderResponse;
import com.exchange.bff.order.dto.OrderView;
import com.exchange.bff.trade.dto.MyTradeView;
import com.exchange.bff.subaccount.dto.CreateSubAccountApiKeyRequest;
import com.exchange.bff.subaccount.dto.CreateSubAccountRequest;
import com.exchange.bff.subaccount.dto.FrozenRequest;
import com.exchange.bff.subaccount.dto.SubAccountView;
import com.exchange.bff.subaccount.dto.TransferRequest;
import com.exchange.bff.subaccount.dto.TransferView;
import com.exchange.bff.wallet.dto.BalanceView;
import com.exchange.common.id.Ulid;
import com.exchange.common.money.Money;
import com.exchange.spot.history.TimeRange;
import com.exchange.spot.order.OpenOrderScope;
import com.exchange.spot.order.OrderQueryService;
import com.exchange.spot.order.OrderService;
import com.exchange.spot.order.OrderTimeField;
import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.Side;
import com.exchange.spot.trade.TradeQueryService;
import com.exchange.spot.trade.dto.UserTrade;
import com.exchange.user.apikey.ApiKeyRevokedReason;
import com.exchange.user.apikey.ApiKeyService;
import com.exchange.user.identity.User;
import com.exchange.user.subaccount.SubAccountService;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.transfer.TransferService;
import com.exchange.wallet.transfer.WalletTransfer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A master's sub-accounts, from the master's own session: docs/reference/sub-accounts.md.
 * Every path checks the sub is this master's before touching it. Keys for a sub are made
 * here and only here -- key creation stays on the web, behind the master's TOTP, as for the
 * master's own keys.
 */
@RestController
@RequestMapping("/bff/sub-accounts")
public class SubAccountController {

    private static final int TRANSFER_HISTORY = 100;
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final SubAccountService subAccounts;
    private final TransferService transfers;
    private final BalanceService balances;
    private final AssetService assets;
    private final ApiKeyService apiKeys;
    private final OrderService orders;
    private final OrderQueryService orderQueries;
    private final TradeQueryService trades;
    private final ObjectMapper json;

    public SubAccountController(SubAccountService subAccounts, TransferService transfers, BalanceService balances,
                                AssetService assets, ApiKeyService apiKeys, OrderService orders,
                                OrderQueryService orderQueries, TradeQueryService trades, ObjectMapper json) {
        this.subAccounts = subAccounts;
        this.transfers = transfers;
        this.balances = balances;
        this.assets = assets;
        this.apiKeys = apiKeys;
        this.orders = orders;
        this.orderQueries = orderQueries;
        this.trades = trades;
        this.json = json;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubAccountView create(@Valid @RequestBody CreateSubAccountRequest request, @CurrentUser User user,
                                 HttpServletRequest http) {
        return SubAccountView.of(subAccounts.create(user.getId(), request.label(), http.getRemoteAddr()));
    }

    @GetMapping
    public List<SubAccountView> list(@CurrentUser User user) {
        return subAccounts.list(user.getId()).stream().map(SubAccountView::of).toList();
    }

    /**
     * Every sub's balances in one call, keyed by sub id: the asset-management screen lists all
     * of them at once, and one request per sub would be a round trip per row.
     */
    @GetMapping("/balances")
    public Map<Long, List<BalanceView>> allBalances(@CurrentUser User user) {
        List<Long> subIds = subAccounts.list(user.getId()).stream().map(User::getId).toList();
        List<Balance> rows = balances.list(subIds);
        Map<Long, Asset> byId = assets.listByIds(rows.stream().map(Balance::getAssetId).toList())
                .stream().collect(Collectors.toMap(Asset::getId, Function.identity()));
        Map<Long, List<BalanceView>> bySub = subIds.stream()
                .collect(Collectors.toMap(Function.identity(), id -> new ArrayList<>()));
        rows.forEach(b -> bySub.get(b.getUserId()).add(BalanceView.of(b, byId.get(b.getAssetId()))));
        return bySub;
    }

    @GetMapping("/{id}/balances")
    public List<BalanceView> balances(@PathVariable Long id, @CurrentUser User user) {
        List<Balance> rows = balances.list(subAccounts.get(user.getId(), id).getId());
        Map<Long, Asset> byId = assets.listByIds(rows.stream().map(Balance::getAssetId).toList())
                .stream().collect(Collectors.toMap(Asset::getId, Function.identity()));
        return rows.stream().map(b -> BalanceView.of(b, byId.get(b.getAssetId()))).toList();
    }

    @PatchMapping("/{id}/frozen")
    public SubAccountView setFrozen(@PathVariable Long id, @RequestBody FrozenRequest request,
                                    @CurrentUser User user, HttpServletRequest http) {
        return SubAccountView.of(subAccounts.setFrozen(user.getId(), id, request.frozen(), http.getRemoteAddr()));
    }

    /** Either side may be the master or one of its subs; a frozen sub is neither. */
    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferView transfer(@Valid @RequestBody TransferRequest request, @CurrentUser User user) {
        subAccounts.requireTransferable(user.getId(), request.fromUserId(), request.toUserId());
        Asset asset = assets.getBySymbol(request.asset());
        UUID id = request.transferId() == null ? Ulid.randomUuid() : UUID.fromString(request.transferId());
        WalletTransfer made = transfers.transfer(id, request.fromUserId(), request.toUserId(), asset.getId(),
                Money.toWire(request.amount()), user.getId());
        return TransferView.of(made, asset.getSymbol());
    }

    @GetMapping("/transfers")
    public List<TransferView> transfers(@CurrentUser User user) {
        List<WalletTransfer> rows = transfers.history(user.getId(), subAccounts.familyIds(user.getId()),
                TRANSFER_HISTORY);
        Map<Long, Asset> byId = assets.listByIds(rows.stream().map(WalletTransfer::getAssetId).toList())
                .stream().collect(Collectors.toMap(Asset::getId, Function.identity()));
        return rows.stream().map(t -> TransferView.of(t, byId.get(t.getAssetId()).getSymbol())).toList();
    }

    @PostMapping("/{id}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedApiKeyView createKey(@PathVariable Long id, @Valid @RequestBody CreateSubAccountApiKeyRequest r,
                                       @CurrentUser User user, HttpServletRequest http) {
        User sub = subAccounts.get(user.getId(), id);
        return CreatedApiKeyView.of(apiKeys.createForSubAccount(user.getId(), sub.getId(), r.label(), r.canRead(),
                r.canTrade(), r.ipAllowlist(), r.expiresAt(), r.mfaCode(), http.getRemoteAddr()));
    }

    /** Every sub's keys in one call, keyed by sub id, for the screen that lists all of them. */
    @GetMapping("/api-keys")
    public Map<Long, List<ApiKeyView>> allKeys(@CurrentUser User user) {
        List<Long> subIds = subAccounts.list(user.getId()).stream().map(User::getId).toList();
        Map<Long, List<ApiKeyView>> bySub = subIds.stream()
                .collect(Collectors.toMap(Function.identity(), id -> new ArrayList<>()));
        apiKeys.list(subIds).forEach(key -> bySub.get(key.getUserId()).add(ApiKeyView.of(key, json)));
        return bySub;
    }

    @GetMapping("/{id}/api-keys")
    public List<ApiKeyView> keys(@PathVariable Long id, @CurrentUser User user) {
        return apiKeys.list(subAccounts.get(user.getId(), id).getId()).stream()
                .map(key -> ApiKeyView.of(key, json)).toList();
    }

    @DeleteMapping("/{id}/api-keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeKey(@PathVariable Long id, @PathVariable UUID keyId, @CurrentUser User user,
                          HttpServletRequest http) {
        apiKeys.revoke(subAccounts.get(user.getId(), id).getId(), keyId, ApiKeyRevokedReason.REVOKED_BY_USER,
                http.getRemoteAddr());
    }

    // ------------------------------------------------------------------ orders and trades

    /** The sub's, read and cancelled by its master: the same shapes as /bff/orders and /bff/trades.
     *  Placing is not offered here -- a sub trades through its keys. */
    @GetMapping("/{id}/orders")
    public List<OrderView> openOrders(@PathVariable Long id, @CurrentUser User user) {
        return orderQueries.openOrders(subAccounts.get(user.getId(), id).getId()).stream().map(OrderView::of).toList();
    }

    @GetMapping("/{id}/orders/history")
    public List<OrderView> orderHistory(@PathVariable Long id, @CurrentUser User user,
                                        @RequestParam(required = false) String symbol,
                                        @RequestParam(required = false) Side side,
                                        @RequestParam(required = false) Instant from,
                                        @RequestParam(required = false) Instant to,
                                        @RequestParam(defaultValue = "CREATED_AT") OrderTimeField timeField,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "" + PAGE_SIZE) int size) {
        Long subId = subAccounts.get(user.getId(), id).getId();
        return orderQueries.history(subId, symbol, side, TimeRange.of(from, to, Instant.now()), timeField,
                Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)).stream().map(OrderView::of).toList();
    }

    @GetMapping("/{id}/trades")
    public List<MyTradeView> tradeHistory(@PathVariable Long id, @CurrentUser User user,
                                          @RequestParam(required = false) String symbol,
                                          @RequestParam(required = false) UserTrade.Side side,
                                          @RequestParam(required = false) Instant from,
                                          @RequestParam(required = false) Instant to,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "" + PAGE_SIZE) int size) {
        Long subId = subAccounts.get(user.getId(), id).getId();
        return trades.history(subId, symbol, side, TimeRange.of(from, to, Instant.now()), Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE)).stream().map(MyTradeView::of).toList();
    }

    @DeleteMapping("/{id}/orders/{orderId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CancelOrderResponse cancelOrder(@PathVariable Long id, @PathVariable UUID orderId, @CurrentUser User user) {
        orders.cancelOrder(subAccounts.get(user.getId(), id).getId(), orderId);
        return new CancelOrderResponse(orderId.toString());
    }

    @DeleteMapping("/{id}/orders")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CancelAllResponse cancelAll(@PathVariable Long id, @CurrentUser User user,
                                       @RequestParam(required = false) String symbol,
                                       @RequestParam(required = false) Side side,
                                       @RequestParam(required = false) OrderType type,
                                       @RequestParam(defaultValue = "ALL") OpenOrderScope scope) {
        return new CancelAllResponse(orders.cancelAllOrders(subAccounts.get(user.getId(), id).getId(), symbol, side,
                type, scope));
    }
}

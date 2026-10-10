package com.exchange.spot.openapi.subaccount;

import com.exchange.common.id.Ulid;
import com.exchange.common.money.Money;
import com.exchange.spot.openapi.common.ratelimit.Weight;
import com.exchange.spot.openapi.common.security.CurrentUser;
import com.exchange.spot.openapi.common.security.Permission;
import com.exchange.spot.openapi.common.security.RequiresPermission;
import com.exchange.spot.openapi.subaccount.dto.CreateSubAccountRequest;
import com.exchange.spot.openapi.subaccount.dto.FrozenRequest;
import com.exchange.spot.openapi.subaccount.dto.SubAccountView;
import com.exchange.spot.openapi.subaccount.dto.TransferRequest;
import com.exchange.spot.openapi.subaccount.dto.TransferView;
import com.exchange.spot.openapi.wallet.dto.BalanceView;
import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.subaccount.SubAccountService;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.transfer.TransferService;
import com.exchange.wallet.transfer.WalletTransfer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A master's sub-accounts, signed with the master's key: docs/reference/sub-accounts.md.
 * A sub-account's own key sees nothing here (2111): it is an account like any other, and
 * manages nothing. No key creation over the API, as for the master's own keys -- that is
 * the web UI's, behind a TOTP. Writes need TRADE: a transfer moves money the way an order
 * does, and READ is what a monitoring key is given.
 */
@RestController
@RequestMapping("/v1/sub-accounts")
public class SubAccountController {

    private static final int TRANSFER_HISTORY = 100;

    private final SubAccountService subAccounts;
    private final TransferService transfers;
    private final BalanceService balances;
    private final AssetService assets;

    public SubAccountController(SubAccountService subAccounts, TransferService transfers, BalanceService balances,
                                AssetService assets) {
        this.subAccounts = subAccounts;
        this.transfers = transfers;
        this.balances = balances;
        this.assets = assets;
    }

    @PostMapping
    @RequiresPermission(Permission.TRADE)
    @ResponseStatus(HttpStatus.CREATED)
    public SubAccountView create(@Valid @RequestBody CreateSubAccountRequest request, @CurrentUser UserApiKey key,
                                 HttpServletRequest http) {
        return SubAccountView.of(subAccounts.create(key.getUserId(), request.label(), http.getRemoteAddr()));
    }

    @GetMapping
    @Weight(5)
    public List<SubAccountView> list(@CurrentUser UserApiKey key) {
        return subAccounts.list(key.getUserId()).stream().map(SubAccountView::of).toList();
    }

    @GetMapping("/{id}/balances")
    @Weight(10)
    public List<BalanceView> balances(@PathVariable Long id, @CurrentUser UserApiKey key) {
        List<Balance> rows = balances.list(subAccounts.get(key.getUserId(), id).getId());
        Map<Long, Asset> byId = assets.listByIds(rows.stream().map(Balance::getAssetId).toList())
                .stream().collect(Collectors.toMap(Asset::getId, Function.identity()));
        return rows.stream().map(b -> BalanceView.of(b, byId.get(b.getAssetId()))).toList();
    }

    @PatchMapping("/{id}/frozen")
    @RequiresPermission(Permission.TRADE)
    public SubAccountView setFrozen(@PathVariable Long id, @RequestBody FrozenRequest request,
                                    @CurrentUser UserApiKey key, HttpServletRequest http) {
        return SubAccountView.of(subAccounts.setFrozen(key.getUserId(), id, request.frozen(), http.getRemoteAddr()));
    }

    @PostMapping("/transfers")
    @RequiresPermission(Permission.TRADE)
    @ResponseStatus(HttpStatus.CREATED)
    public TransferView transfer(@Valid @RequestBody TransferRequest request, @CurrentUser UserApiKey key) {
        subAccounts.requireTransferable(key.getUserId(), request.fromUserId(), request.toUserId());
        Asset asset = assets.getBySymbol(request.asset());
        UUID id = request.transferId() == null ? Ulid.randomUuid() : UUID.fromString(request.transferId());
        WalletTransfer made = transfers.transfer(id, request.fromUserId(), request.toUserId(), asset.getId(),
                Money.toWire(request.amount()), key.getUserId());
        return TransferView.of(made, asset.getSymbol());
    }

    @GetMapping("/transfers")
    @Weight(5)
    public List<TransferView> transfers(@CurrentUser UserApiKey key) {
        List<WalletTransfer> rows = transfers.history(key.getUserId(), subAccounts.familyIds(key.getUserId()),
                TRANSFER_HISTORY);
        Map<Long, Asset> byId = assets.listByIds(rows.stream().map(WalletTransfer::getAssetId).toList())
                .stream().collect(Collectors.toMap(Asset::getId, Function.identity()));
        return rows.stream().map(t -> TransferView.of(t, byId.get(t.getAssetId()).getSymbol())).toList();
    }
}

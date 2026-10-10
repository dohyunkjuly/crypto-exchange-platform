package com.exchange.spot.openapi.wallet;

import com.exchange.spot.openapi.common.ratelimit.Weight;
import com.exchange.spot.openapi.common.security.CurrentUser;
import com.exchange.spot.openapi.wallet.dto.BalanceView;
import com.exchange.user.apikey.UserApiKey;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceService;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/wallet}: balances only. Deposits and withdrawals do not exist here on purpose
 *  (AGENTS.md: nothing in this product touches a chain). */
@RestController
@RequestMapping("/v1/wallet")
public class BalanceController {

    private final BalanceService balances;
    private final AssetService assets;

    public BalanceController(BalanceService balances, AssetService assets) {
        this.balances = balances;
        this.assets = assets;
    }

    @GetMapping("/balances")
    @Weight(10)
    public List<BalanceView> balances(@CurrentUser UserApiKey key) {
        List<Balance> rows = balances.list(key.getUserId());
        Map<Long, Asset> byId = assets.listByIds(rows.stream().map(Balance::getAssetId).toList())
                .stream().collect(Collectors.toMap(Asset::getId, Function.identity()));
        return rows.stream().map(b -> BalanceView.of(b, byId.get(b.getAssetId()))).toList();
    }
}

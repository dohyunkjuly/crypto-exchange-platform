package com.exchange.bff.wallet;

import com.exchange.bff.common.security.TradingAccount;
import com.exchange.bff.wallet.dto.AssetView;
import com.exchange.bff.wallet.dto.BalanceView;
import com.exchange.user.identity.User;
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

@RestController
@RequestMapping("/bff/wallet")
public class BalanceController {

    private final BalanceService balanceService;
    private final AssetService assetService;

    public BalanceController(BalanceService balanceService, AssetService assetService) {
        this.balanceService = balanceService;
        this.assetService = assetService;
    }

    @GetMapping("/assets")
    public List<AssetView> assets() {
        return assetService.listTradable().stream().map(AssetView::of).toList();
    }

    @GetMapping("/balances")
    public List<BalanceView> balances(@TradingAccount User user) {
        List<Balance> balances = balanceService.list(user.getId());
        Map<Long, Asset> assetsById = assetService
                .listByIds(balances.stream().map(Balance::getAssetId).toList())
                .stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity()));
        return balances.stream()
                .map(balance -> BalanceView.of(balance, assetsById.get(balance.getAssetId())))
                .toList();
    }
}

package com.exchange.bff.subaccount.dto;

import com.exchange.common.money.Money;
import com.exchange.wallet.transfer.WalletTransfer;
import java.time.Instant;

public record TransferView(String transferId, Long fromUserId, Long toUserId, String asset, String amount,
                           Instant createdAt) {

    public static TransferView of(WalletTransfer t, String asset) {
        return new TransferView(t.getId().toString(), t.getFromUserId(), t.getToUserId(), asset,
                Money.toDecimal(t.getAmount()), t.getCreatedAt());
    }
}

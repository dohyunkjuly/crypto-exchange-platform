package com.exchange.spot.copy.lead;

import com.exchange.copy.leadorder.CopyAccountOrderHook;
import com.exchange.common.money.Money;
import com.exchange.contracts.spot.v1.OrderAmended;
import com.exchange.copy.leadorder.CopyLeadOrder;
import com.exchange.copy.leadorder.CopyLeadOrderRepository;
import com.exchange.spot.copy.copy.CopyService;
import com.exchange.spot.order.Side;
import java.math.BigInteger;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Follows a lead account's order from the event stream until it closes, then has it copied
 * (docs/reference/copy-trading.md).
 *
 * <p>The order's row here was written when it was placed ({@code CopyAccountOrderHook}). This
 * adds to it what only the engine knows: how much filled and at what price, whether an amend
 * took, and that it has closed -- by filling, which is read from the amounts, or by a cancel or
 * a rejection. Each is safe to apply twice -- a trade by its sequence, an
 * amend because it moves the row to the same place, a closing by {@code copiedAt}.
 */
@Service
public class LeadOrderService {

    private final CopyLeadOrderRepository leadOrders;
    private final CopyService copies;

    public LeadOrderService(CopyLeadOrderRepository leadOrders, CopyService copies) {
        this.leadOrders = leadOrders;
        this.copies = copies;
    }

    /**
     * One trade of the order. It has closed when what has filled covers what was ordered -- the
     * trade's own status cannot say so, since every trade of a taker carries the order's final one.
     */
    public void traded(CopyLeadOrder lead, long sequence, long quantity, long quoteQuantity) {
        if (lead.addFill(sequence, quantity, quoteQuantity)) {
            leadOrders.save(lead);
        }
        if (lead.isFilled()) {
            closed(lead);
        }
    }

    /**
     * The engine took an amend: the order is for a different amount now, so its fraction of the
     * leader's balance moves by the same ratio. Read from the engine's answer rather than the
     * request, because only the answer says the amend happened.
     */
    public void amended(CopyLeadOrder lead, OrderAmended amended) {
        long amount;
        if (lead.getSide() == Side.SELL) {
            amount = amended.getNewQuantity();
        } else if (amended.hasNewPrice()) {
            amount = Money.multiply(amended.getNewPrice(), amended.getNewQuantity());
        } else {
            return; // a buy with no price is sized in the quote asset, which an amend does not change
        }
        if (amount <= 0 || amount == lead.getAmount()) {
            return;
        }
        long fraction = BigInteger.valueOf(lead.getFraction()).multiply(BigInteger.valueOf(amount))
                .divide(BigInteger.valueOf(lead.getAmount())).min(BigInteger.valueOf(CopyLeadOrder.WHOLE))
                .max(BigInteger.ONE).longValueExact();
        lead.setFraction(fraction);
        lead.setAmount(amount);
        lead.setQuantity(amended.getNewQuantity());
        leadOrders.save(lead);
    }

    /**
     * Filled, cancelled or rejected: nothing more will happen to it. Copied if anything filled,
     * and marked either way -- last, so a copier that stopped halfway copies the rest next time.
     */
    public void closed(CopyLeadOrder lead) {
        if (lead.getCopiedAt() != null) {
            return;
        }
        if (lead.getFilledQuantity() > 0) {
            copies.copy(lead);
        }
        lead.setCopiedAt(Instant.now());
        leadOrders.save(lead);
    }
}

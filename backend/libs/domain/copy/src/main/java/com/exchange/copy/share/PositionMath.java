package com.exchange.copy.share;

import com.exchange.copy.position.CopyPosition;
import com.exchange.spot.trade.SpotTrade;
import java.math.BigInteger;

/**
 * Average cost, trade by trade (docs/reference/copy-trading.md). A buy adds what was received
 * to the holding and what was paid, fees included, to its cost; a sell takes the sold share of
 * that cost out and realizes the proceeds, net of fees, over it. Nothing is valued at a market
 * price: profit exists only once something has been sold.
 */
public final class PositionMath {

    private PositionMath() {
    }

    /**
     * Applies one fill to the position and returns what it realized, signed; zero for a buy.
     *
     * @param base the market's base asset, which is what the position holds
     */
    public static long apply(CopyPosition position, SpotTrade trade, Long account, Long base, Long quote) {
        long realized = 0;
        if (account.equals(trade.getBuyerUserId())) {
            long received = trade.getQuantity() - feeIn(base, trade.getBuyerFeeAssetId(), trade.getBuyerFee());
            long paid = trade.getQuoteQuantity() + feeIn(quote, trade.getBuyerFeeAssetId(), trade.getBuyerFee());
            position.setQuantity(position.getQuantity() + received);
            position.setCost(position.getCost() + paid);
        } else {
            long sold = trade.getQuantity();
            long proceeds = trade.getQuoteQuantity() - feeIn(quote, trade.getSellerFeeAssetId(), trade.getSellerFee());
            // More sold than counted -- coins that reached the account some other way -- had no
            // cost here, so the whole cost goes and the rest of the proceeds is profit.
            long costOut = position.getQuantity() == 0 || sold >= position.getQuantity() ? position.getCost()
                    : BigInteger.valueOf(position.getCost()).multiply(BigInteger.valueOf(sold))
                            .divide(BigInteger.valueOf(position.getQuantity())).longValueExact();
            realized = proceeds - costOut;
            position.setQuantity(Math.max(0, position.getQuantity() - sold));
            position.setCost(position.getCost() - costOut);
        }
        position.setLastSequence(trade.getSequence());
        return realized;
    }

    private static long feeIn(Long asset, Long feeAsset, long fee) {
        return asset.equals(feeAsset) ? fee : 0;
    }
}

package com.exchange.spot.copy.kafka;

import com.exchange.common.id.Ulid;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.TradeExecuted;
import com.exchange.copy.leadorder.CopyLeadOrder;
import com.exchange.copy.leadorder.CopyLeadOrderRepository;
import com.exchange.copy.order.CopyOrder;
import com.exchange.copy.order.CopyOrderReason;
import com.exchange.copy.order.CopyOrderRepository;
import com.exchange.spot.copy.copy.CopyService;
import com.exchange.spot.copy.lead.LeadOrderService;
import com.google.protobuf.ByteString;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Picks, out of everything the exchange does, the events about a lead account's order or a
 * copy's order, and hands each to whoever keeps it (docs/reference/copy-trading.md).
 *
 * <p>Almost every event is about neither. So a batch costs two reads -- which of its orders
 * are lead orders still open, which are copies -- however many events it holds, and an event
 * about nobody's order costs nothing after that.
 *
 * <p>In the order the engine published them: a lead order's fills before its closing, a copy's
 * trade before its cancel.
 */
@Component
public class CopyEventHandler {

    private final CopyLeadOrderRepository leadOrders;
    private final CopyOrderRepository copyOrders;
    private final LeadOrderService leads;
    private final CopyService copies;

    public CopyEventHandler(CopyLeadOrderRepository leadOrders, CopyOrderRepository copyOrders,
                            LeadOrderService leads, CopyService copies) {
        this.leadOrders = leadOrders;
        this.copyOrders = copyOrders;
        this.leads = leads;
        this.copies = copies;
    }

    public void handle(List<Event> events) {
        Set<UUID> orderIds = orderIds(events);
        if (orderIds.isEmpty()) {
            return;
        }
        Map<UUID, CopyLeadOrder> lead = new HashMap<>();
        for (CopyLeadOrder order : leadOrders.findAllById(orderIds)) {
            if (order.getCopiedAt() == null) {
                lead.put(order.getOrderId(), order);
            }
        }
        Map<UUID, CopyOrder> copy = new HashMap<>();
        for (CopyOrder order : copyOrders.findByOrderIdIn(orderIds)) {
            copy.put(order.getOrderId(), order);
        }
        if (lead.isEmpty() && copy.isEmpty()) {
            return;
        }

        for (Event event : events) {
            switch (event.getPayloadCase()) {
                case TRADE_EXECUTED -> traded(event, lead, copy);
                case ORDER_CANCELED -> {
                    UUID id = id(event.getOrderCanceled().getOrderId());
                    if (lead.containsKey(id)) {
                        leads.closed(lead.get(id));
                    }
                    if (copy.containsKey(id)) {
                        copies.notFilled(copy.get(id), CopyOrderReason.NOT_FILLED);
                    }
                }
                case ORDER_REJECTED -> {
                    UUID id = id(event.getOrderRejected().getOrderId());
                    if (lead.containsKey(id)) {
                        leads.closed(lead.get(id));
                    }
                    if (copy.containsKey(id)) {
                        copies.notFilled(copy.get(id), CopyOrderReason.REJECTED);
                    }
                }
                case ORDER_AMENDED -> {
                    UUID id = id(event.getOrderAmended().getOrderId());
                    if (lead.containsKey(id)) {
                        leads.amended(lead.get(id), event.getOrderAmended());
                    }
                }
                default -> {
                    // Accepted, or an amend refused: neither changes what is copied.
                }
            }
        }
    }

    /**
     * Both sides: a lead order may be the maker that was hit as well as the taker that hit. The
     * trade's status fields are not read: for a taker they carry the order's final state on
     * every one of its trades, so they cannot say which trade is the last.
     */
    private void traded(Event event, Map<UUID, CopyLeadOrder> lead, Map<UUID, CopyOrder> copy) {
        TradeExecuted trade = event.getTradeExecuted();
        for (UUID orderId : List.of(id(trade.getTakerOrderId()), id(trade.getMakerOrderId()))) {
            if (lead.containsKey(orderId)) {
                leads.traded(lead.get(orderId), event.getSequence(), trade.getQuantity(), trade.getQuoteQuantity());
            }
            if (copy.containsKey(orderId)) {
                copies.filled(copy.get(orderId));
            }
        }
    }

    private static Set<UUID> orderIds(List<Event> events) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Event event : events) {
            switch (event.getPayloadCase()) {
                case TRADE_EXECUTED -> {
                    ids.add(id(event.getTradeExecuted().getTakerOrderId()));
                    ids.add(id(event.getTradeExecuted().getMakerOrderId()));
                }
                case ORDER_CANCELED -> ids.add(id(event.getOrderCanceled().getOrderId()));
                case ORDER_REJECTED -> ids.add(id(event.getOrderRejected().getOrderId()));
                case ORDER_AMENDED -> ids.add(id(event.getOrderAmended().getOrderId()));
                default -> {
                }
            }
        }
        return ids;
    }

    private static UUID id(ByteString bytes) {
        return Ulid.toUuid(bytes.toByteArray());
    }
}

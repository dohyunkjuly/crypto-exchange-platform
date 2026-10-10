package com.exchange.admin.bff.treasury;

import com.exchange.admin.audit.AdminAction;
import com.exchange.admin.audit.AdminAuditEntry;
import com.exchange.admin.audit.AdminAuditRepository;
import com.exchange.admin.bff.audit.AdminAuditService;
import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.admin.bff.treasury.dto.TreasuryView.Move;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import com.exchange.common.money.Money;
import com.exchange.core.api.exception.ApiException;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.identity.UserType;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.exception.InsufficientBalanceException;
import com.exchange.wallet.treasury.HouseTransferService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff moving the exchange's own money (docs/reference/treasury.md): fees on to the treasury,
 * and the treasury out to a customer. There is no third kind -- a customer is never credited
 * from nothing, so the books always say whose money it was.
 *
 * <p>Each move and its audit row commit together. A repeated request (same id) moves nothing and
 * writes no second row; the audit log's {@code after} is the move's record, reason and all.
 */
@Service
public class TreasuryMoves {

    private static final List<AdminAction> MOVES = List.of(AdminAction.TREASURY_FEES_MOVED, AdminAction.TREASURY_PAID_OUT);
    private static final int HISTORY = 20;

    private final HouseTransferService transfers;
    private final HouseAccounts house;
    private final AssetService assets;
    private final UserRepository users;
    private final AdminAuditService audit;
    private final AdminAuditRepository auditLog;
    private final AdminUserRepository admins;
    private final ObjectMapper json;

    public TreasuryMoves(HouseTransferService transfers, HouseAccounts house, AssetService assets, UserRepository users,
                         AdminAuditService audit, AdminAuditRepository auditLog, AdminUserRepository admins,
                         ObjectMapper json) {
        this.transfers = transfers;
        this.house = house;
        this.assets = assets;
        this.users = users;
        this.audit = audit;
        this.auditLog = auditLog;
        this.admins = admins;
        this.json = json;
    }

    /** Fees on to the treasury: the fee account is never spent from directly. */
    @Transactional
    public void moveFees(Long by, UUID id, String asset, String amount, String reason, String ip) {
        Asset moved = assets.getBySymbol(asset);
        long wire = amount(amount);
        if (move(id, house.feeAccountId(), house.treasuryAccountId(), moved.getId(), wire)) {
            audit.record(by, AdminAction.TREASURY_FEES_MOVED, "asset", moved.getSymbol(), null,
                    detail(id, moved, wire, null, reason), ip);
        }
    }

    /** Treasury to a customer's main account; they move it to a sub themselves if they want. */
    @Transactional
    public void payOut(Long by, UUID id, String customer, String asset, String amount, String reason, String ip) {
        User to = recipient(customer);
        Asset paid = assets.getBySymbol(asset);
        long wire = amount(amount);
        if (move(id, house.treasuryAccountId(), to.getId(), paid.getId(), wire)) {
            audit.record(by, AdminAction.TREASURY_PAID_OUT, "user", to.getId().toString(), null,
                    detail(id, paid, wire, to.getEmail(), reason), ip);
        }
    }

    /** The latest moves of both kinds, newest first, read back from their audit rows. */
    @Transactional(readOnly = true)
    public List<Move> history() {
        List<AdminAuditEntry> rows = auditLog.findByActionInOrderByIdDesc(MOVES, PageRequest.of(0, HISTORY));
        Map<Long, String> emails = admins.findAllById(rows.stream().map(AdminAuditEntry::getAdminId).toList())
                .stream().collect(Collectors.toMap(AdminUser::getId, AdminUser::getEmail));
        return rows.stream().map(row -> {
            JsonNode detail = read(row.getAfter());
            return new Move(row.getCreatedAt(),
                    row.getAction() == AdminAction.TREASURY_FEES_MOVED ? "FEES_TO_TREASURY" : "PAYOUT",
                    emails.get(row.getAdminId()), detail.path("asset").asText(), detail.path("amount").asText(),
                    detail.path("to").isNull() ? null : detail.path("to").asText(null), detail.path("reason").asText());
        }).toList();
    }

    private boolean move(UUID id, Long from, Long to, Long assetId, long amount) {
        try {
            return transfers.move(id, from, to, assetId, amount);
        } catch (InsufficientBalanceException e) {
            throw new ApiException(AdminErrorCode.INSUFFICIENT_BALANCE);
        }
    }

    /** By id or by email; only a main account, never a sub, a copy account or the house. */
    private User recipient(String customer) {
        String key = customer.trim();
        Optional<User> found = key.matches("\\d+") ? users.findById(Long.valueOf(key)) : users.findByEmail(key);
        User user = found.orElseThrow(() -> new ApiException(AdminErrorCode.USER_NOT_FOUND));
        if (user.getType() != UserType.PRIMARY) {
            throw new ApiException(AdminErrorCode.INVALID_RECIPIENT);
        }
        return user;
    }

    private static long amount(String decimal) {
        long wire;
        try {
            wire = Money.toWire(decimal.trim());
        } catch (RuntimeException e) {
            throw new ApiException(AdminErrorCode.INVALID_AMOUNT);
        }
        if (wire <= 0) {
            throw new ApiException(AdminErrorCode.INVALID_AMOUNT);
        }
        return wire;
    }

    private String detail(UUID id, Asset asset, long amount, String to, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", id.toString());
        detail.put("asset", asset.getSymbol());
        detail.put("amount", Money.toDecimal(amount));
        detail.put("to", to);
        detail.put("reason", reason);
        try {
            return json.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode read(String after) {
        try {
            return json.readTree(after == null ? "{}" : after);
        } catch (JsonProcessingException e) {
            return json.createObjectNode();
        }
    }
}

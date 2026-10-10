package com.exchange.admin.bff.common.paging;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;

/**
 * A page that knows whether another follows, not how many there are. For the trade and ledger
 * lists: they are the exchange's largest tables, and counting them on every page is the one
 * cost a list there cannot afford.
 */
public record Chunk<T>(List<T> items, int page, int size, boolean more) {

    public static final int MAX_SIZE = 100;

    public static PageRequest request(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
    }

    public static <T> Chunk<T> empty(PageRequest request) {
        return new Chunk<>(List.of(), request.getPageNumber(), request.getPageSize(), false);
    }

    public static <T> Chunk<T> of(List<T> items, Slice<?> slice) {
        return new Chunk<>(items, slice.getNumber(), slice.getSize(), slice.hasNext());
    }
}

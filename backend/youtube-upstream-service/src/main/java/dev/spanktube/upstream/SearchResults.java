package dev.spanktube.upstream;

import java.util.List;

public record SearchResults(List<SearchItem> items, String nextCursor) {
}

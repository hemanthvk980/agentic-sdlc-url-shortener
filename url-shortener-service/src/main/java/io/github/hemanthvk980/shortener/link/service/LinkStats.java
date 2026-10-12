package io.github.hemanthvk980.shortener.link.service;

import java.time.LocalDate;
import java.util.List;

public record LinkStats(String code, long totalClicks, LocalDate since, List<Daily> clicksByDay, List<Referrer> topReferrers) {

    public record Daily(LocalDate day, long clicks) {}

    public record Referrer(String referrer, long clicks) {}
}

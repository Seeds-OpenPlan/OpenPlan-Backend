package com.openplan.backend.stats.dto;

/** 구간(DAWN/MORNING/AFTERNOON/NIGHT) 1행. {@code totalCount=0}이면 {@code completionRate=null}. */
public record TimeSlotResponse(String slot, int totalCount, int completedCount, Double completionRate) {
}

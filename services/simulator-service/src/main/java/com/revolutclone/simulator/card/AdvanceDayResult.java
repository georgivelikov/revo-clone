package com.revolutclone.simulator.card;

/** {@code POST /simulator/clock/advance-day}'s answer: the new day, and how many settled. */
public record AdvanceDayResult(int day, int settledCount) {
}

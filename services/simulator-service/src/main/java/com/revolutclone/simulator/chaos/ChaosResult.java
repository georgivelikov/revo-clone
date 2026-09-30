package com.revolutclone.simulator.chaos;

/** Uniform response for every lever, so the caller does not need a different shape per command. */
public record ChaosResult(String lever, String detail) {
}

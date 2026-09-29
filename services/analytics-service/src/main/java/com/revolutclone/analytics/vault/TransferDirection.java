package com.revolutclone.analytics.vault;

/**
 * Which way money moves relative to the vault - plans/phase-06-vaults-analytics.md's "Vaults"
 * section: {@code IN} is wallet {@literal ->} vault, {@code OUT} is vault {@literal ->} wallet.
 */
public enum TransferDirection {
    IN,
    OUT
}

package com.revolutclone.analytics.categorisation;

/**
 * What {@link CategorisationResolver#resolve} decided: a {@link Category} and which
 * {@link CategorisationSource} layer produced it.
 */
public record CategorisationOutcome(String category, String source) {
}

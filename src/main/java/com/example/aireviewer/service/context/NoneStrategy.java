package com.example.aireviewer.service.context;

/**
 * Diff-only review — no extra context, no tools. The cheapest, lowest-token end of the
 * spectrum and the original baseline behavior. Stateless singleton.
 */
public final class NoneStrategy implements ContextStrategy {

    public static final NoneStrategy INSTANCE = new NoneStrategy();

    private NoneStrategy() {}

    @Override
    public String name() {
        return "none";
    }
}

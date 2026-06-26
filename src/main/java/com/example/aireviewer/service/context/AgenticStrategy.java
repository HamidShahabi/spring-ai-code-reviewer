package com.example.aireviewer.service.context;

import com.example.aireviewer.tools.RepoContextTools;

/**
 * Agentic "context ceiling": exposes {@link RepoContextTools} so the model pulls the context it
 * decides it needs, on demand, at the reviewed SHA. Highest potential coverage, but depends on a
 * tool-capable model (weaker models may not call the tools and may not emit clean structured
 * output). <b>Experimental</b> — see {@code reviewer.context.strategy}.
 */
public class AgenticStrategy implements ContextStrategy {

    private final RepoContextTools tools;

    public AgenticStrategy(RepoContextTools tools) {
        this.tools = tools;
    }

    @Override
    public String name() {
        return "agentic";
    }

    @Override
    public Object tools() {
        return tools;
    }
}

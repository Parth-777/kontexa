package com.example.BACKEND.catalogue.decision.playbooks;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.IntentResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Selects the appropriate {@link Playbook} for a resolved intent.
 *
 * Spring collects all Playbook beans automatically. The router
 * tries each one in declaration order; last resort is a no-op
 * {@link GeneralPlaybook} that applies no overrides.
 */
@Component
public class PlaybookRouter {

    private static final Logger log = LoggerFactory.getLogger(PlaybookRouter.class);

    private final List<Playbook> playbooks;
    private final GeneralPlaybook fallback;

    public PlaybookRouter(List<Playbook> playbooks, GeneralPlaybook fallback) {
        this.playbooks = playbooks;
        this.fallback  = fallback;
    }

    public Playbook route(IntentResolution intent) {
        return playbooks.stream()
                .filter(p -> p.supports(intent.objectiveKey()))
                .findFirst()
                .map(p -> {
                    log.info("[playbook] selected={} objective={}", p.playbookKey(), intent.objectiveKey());
                    return p;
                })
                .orElseGet(() -> {
                    log.info("[playbook] fallback=GENERAL objective={}", intent.objectiveKey());
                    return fallback;
                });
    }
}

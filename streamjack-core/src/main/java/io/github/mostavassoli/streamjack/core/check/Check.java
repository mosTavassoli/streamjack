package io.github.mostavassoli.streamjack.core.check;

import io.github.mostavassoli.streamjack.core.evidence.Evidence;
import io.github.mostavassoli.streamjack.core.scenario.Scenario;
import java.util.List;

public interface Check {

    String id();

    Category category();

    List<Finding> evaluate(Scenario scenario, Evidence evidence);
}

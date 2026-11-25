package io.github.mostavassoli.streamjack.sample.share;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Control: processing (0.5 s) is far below the lock (10 s), so SmallRye's renewal never starts. This checks the
 * harness, not renewal. (With a 2 s lock, renewal can start while a fast record is still queued, so it is not a
 * clean control.)
 */
@EnabledIfSystemProperty(named = "streamjack.experiments", matches = "true")
@QuarkusTest
@TestProfile(SmallRyeShareGroupFastControlTest.Profile.class)
@WithTestResource(value = ShareGroupKafkaResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS,
        initArgs = @ResourceArg(name = "lockMs", value = "10000"))
class SmallRyeShareGroupFastControlTest {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Test
    void observe() throws Exception {
        var first = ShareGroupAgentSupport.analysesOf(bootstrapServers, "T-8104", Duration.ofSeconds(10));
        var second = ShareGroupAgentSupport.analysesOf(bootstrapServers, "T-8114", Duration.ofSeconds(10),
                Duration.ofSeconds(45));
        System.out.println("SHARE-GROUP RESULT CONTROL (0.5 s work, 10 s lock): first " + first + ", second " + second);
        // A control that cannot fail is not a control: each ticket must be analyzed exactly once, to completion.
        assertThat(first).isEqualTo(new ShareGroupAgentSupport.Observation(1, 1, true));
        assertThat(second).isEqualTo(new ShareGroupAgentSupport.Observation(1, 1, true));
    }

    public static final class Profile extends ShareGroupAgentSupport.Profile {
        @Override
        long modelDelayMs() {
            return 500;
        }

        @Override
        String unprocessedRecordMaxAgeMs() {
            return null;
        }
    }
}

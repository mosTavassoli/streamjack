package io.github.mostavassoli.streamjack.sample.share;

import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Control: processing (0.5 s) stays under the lock (2 s), so no renewal is needed. */
@EnabledIfSystemProperty(named = "streamjack.experiments", matches = "true")
@QuarkusTest
@TestProfile(SmallRyeShareGroupFastControlTest.Profile.class)
@WithTestResource(value = ShareGroupKafkaResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS,
        initArgs = @ResourceArg(name = "lockMs", value = "2000"))
class SmallRyeShareGroupFastControlTest {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Test
    void observe() throws Exception {
        int first = ShareGroupAgentSupport.analysesOf(bootstrapServers, "T-8104", Duration.ofSeconds(10));
        int second = ShareGroupAgentSupport.analysesOf(bootstrapServers, "T-8114", Duration.ofSeconds(10),
                Duration.ofSeconds(45));
        System.out.println("SHARE-GROUP RESULT CONTROL (0.5 s work, 2 s lock): first=" + first + " second=" + second);
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

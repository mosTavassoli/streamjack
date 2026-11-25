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

/** B: processing (6 s) outlasts a scaled-down max age (4 s); the lock is 2 s. */
@EnabledIfSystemProperty(named = "streamjack.experiments", matches = "true")
@QuarkusTest
@TestProfile(SmallRyeShareGroupMaxAgeTest.Profile.class)
@WithTestResource(value = ShareGroupKafkaResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS,
        initArgs = @ResourceArg(name = "lockMs", value = "2000"))
class SmallRyeShareGroupMaxAgeTest {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Test
    void observe() throws Exception {
        var analyses = ShareGroupAgentSupport.analysesOf(bootstrapServers, "T-8102", Duration.ofSeconds(15));
        System.out.println("SHARE-GROUP RESULT B (4 s max age, 6 s work, 2 s lock): analyses=" + analyses);
    }

    public static final class Profile extends ShareGroupAgentSupport.Profile {
        @Override
        long modelDelayMs() {
            return 6000;
        }

        @Override
        String unprocessedRecordMaxAgeMs() {
            return "4000";
        }
    }
}

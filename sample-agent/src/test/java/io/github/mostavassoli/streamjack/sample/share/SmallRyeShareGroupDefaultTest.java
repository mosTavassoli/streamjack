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

/** A: processing (3 s) outlasts the lock (2 s) but stays under SmallRye's default 60 s max age. */
@EnabledIfSystemProperty(named = "streamjack.experiments", matches = "true")
@QuarkusTest
@TestProfile(SmallRyeShareGroupDefaultTest.Profile.class)
@WithTestResource(value = ShareGroupKafkaResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS,
        initArgs = @ResourceArg(name = "lockMs", value = "2000"))
class SmallRyeShareGroupDefaultTest {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Test
    void observe() throws Exception {
        int analyses = ShareGroupAgentSupport.analysesOf(bootstrapServers, "T-8101", Duration.ofSeconds(10));
        System.out.println("SHARE-GROUP RESULT A (default max age, 3 s work, 2 s lock): analyses=" + analyses);
        // Stall check: is a second ticket ever processed after the first one could not be acknowledged?
        int second = ShareGroupAgentSupport.analysesOf(bootstrapServers, "T-8111", Duration.ofSeconds(10),
                Duration.ofSeconds(45));
        System.out.println("SHARE-GROUP RESULT A2 (second ticket afterwards): analyses=" + second);
    }

    public static final class Profile extends ShareGroupAgentSupport.Profile {
        @Override
        long modelDelayMs() {
            return 3000;
        }

        @Override
        String unprocessedRecordMaxAgeMs() {
            return null;
        }
    }
}

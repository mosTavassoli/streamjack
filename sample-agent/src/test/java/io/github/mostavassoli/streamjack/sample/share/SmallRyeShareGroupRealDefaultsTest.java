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

/**
 * Unscaled: Kafka's default 30 s lock, SmallRye's default 5 s renewal interval and 60 s max age, and 40 s of
 * processing. Slow (several minutes); like the other share-group experiments, it runs only with
 * {@code -Dstreamjack.experiments=true}.
 */
@EnabledIfSystemProperty(named = "streamjack.experiments", matches = "true")
@QuarkusTest
@TestProfile(SmallRyeShareGroupRealDefaultsTest.Profile.class)
@WithTestResource(value = ShareGroupKafkaResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS,
        initArgs = @ResourceArg(name = "lockMs", value = "30000"))
class SmallRyeShareGroupRealDefaultsTest {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Test
    void observe() throws Exception {
        long start = System.nanoTime();
        int first = ShareGroupAgentSupport.analysesOf(bootstrapServers, "T-8201", Duration.ofSeconds(45),
                Duration.ofSeconds(300));
        System.out.printf("SHARE-GROUP RESULT REAL (30 s lock, 5 s renew, 60 s max age, 40 s work): first=%d after %d s%n",
                first, Duration.ofNanos(System.nanoTime() - start).toSeconds());
        int second = ShareGroupAgentSupport.analysesOf(bootstrapServers, "T-8211", Duration.ofSeconds(45),
                Duration.ofSeconds(150));
        System.out.printf("SHARE-GROUP RESULT REAL second ticket: analyses=%d after %d s%n",
                second, Duration.ofNanos(System.nanoTime() - start).toSeconds());
    }

    public static final class Profile extends ShareGroupAgentSupport.Profile {
        @Override
        long modelDelayMs() {
            return 40_000;
        }

        @Override
        String unprocessedRecordMaxAgeMs() {
            return null;
        }

        @Override
        String autoCommitIntervalMs() {
            return null;
        }
    }
}

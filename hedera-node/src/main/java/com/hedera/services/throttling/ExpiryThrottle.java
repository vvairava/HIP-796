package com.hedera.services.throttling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.hedera.services.files.HybridResouceLoader;
import com.hedera.services.sysfiles.domain.throttling.ThrottleBucket;
import com.hedera.services.throttles.DeterministicThrottle;
import com.hedera.services.utils.MiscUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.hedera.services.utils.MiscUtils.safeResetThrottles;
import static java.util.stream.Collectors.toMap;

@Singleton
public class ExpiryThrottle {
    private static final Logger log = LogManager.getLogger(ExpiryThrottle.class);
    private static final String FALLBACK_RESOURCE_LOC = "expiry-throttle.json";

    private final HybridResouceLoader resourceLoader;

    private DeterministicThrottle throttle;
    private Map<MapAccessType, Integer> accessReqs;

    @Inject
    public ExpiryThrottle(final HybridResouceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public boolean allow(final List<MapAccessType> accessTypes, final Instant now) {
        if (throttle != null && accessReqs != null) {
            return throttle.allow(requiredOps(accessTypes), now);
        }
        return false;
    }

    public void rebuildFromResource(final String resourceLoc) {
        try {
            resetInternalsFrom(resourceLoc);
        } catch (Exception userError) {
            log.warn("Unable to load override expiry throttle from {}", resourceLoc, userError);
            try {
                resetInternalsFrom(FALLBACK_RESOURCE_LOC);
            } catch (Exception unrecoverable) {
                log.error("Unable to load default expiry throttle, will reject all expiry work", unrecoverable);
            }
        }
    }

    public void resetToSnapshot(final DeterministicThrottle.UsageSnapshot snapshot) {
        if (throttle == null) {
            log.error("Attempt to reset expiry throttle to {} before initialization", snapshot);
        } else {
            safeResetThrottles(List.of(throttle), new DeterministicThrottle.UsageSnapshot[] { snapshot }, "expiry");
        }
    }

    @Nullable
    public DeterministicThrottle getThrottle() {
        return throttle;
    }

    @Nullable
    public DeterministicThrottle.UsageSnapshot getThrottleSnapshot() {
        return (throttle == null) ? null : throttle.usageSnapshot();
    }

    private void resetInternalsFrom(final String loc) throws JsonProcessingException {
        final var bucket = loadBucket(loc);
        final var mapping = bucket.asThrottleMapping(1);
        throttle = mapping.getKey();
        accessReqs = mapping.getValue().stream().collect(toMap(
                Pair::getKey,
                Pair::getValue,
                (a, b) -> a,
                () -> new EnumMap<>(MapAccessType.class)));
    }

    private ThrottleBucket<MapAccessType> loadBucket(final String at) throws JsonProcessingException {
        var bytes = resourceLoader.readAllBytesIfPresent(at);
        if (bytes == null) {
            throw new IllegalArgumentException("Cannot load throttle from '" + at + "'");
        }
        return parseJson(new String(bytes));
    }

    private int requiredOps(final List<MapAccessType> accessTypes) {
        var ans = 0;
        for (final var accessType : accessTypes) {
            ans += accessReqs.get(accessType);
        }
        return ans;
    }

    @VisibleForTesting
    ThrottleBucket<MapAccessType> parseJson(final String throttle) throws JsonProcessingException {
        final var om = new ObjectMapper();
        final var pojo = om.readValue(throttle, ExpiryThrottlePojo.class);
        return pojo.getBucket();
    }
}

package com.lagradost.quicknovel.extensions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareRecoveryPolicyTest {
    @Test
    fun retriesKnownCloudflareChallengeStatuses() {
        assertTrue(shouldAttemptCloudflareRecovery(403, null, null, null))
        assertTrue(shouldAttemptCloudflareRecovery(520, null, null, null))
    }

    @Test
    fun retries503OnlyWhenCloudflareIdentifiesTheResponse() {
        assertTrue(shouldAttemptCloudflareRecovery(503, "cloudflare", null, null))
        assertTrue(shouldAttemptCloudflareRecovery(503, null, "abc123", null))
        assertTrue(shouldAttemptCloudflareRecovery(503, null, null, "challenge"))
        assertTrue(shouldAttemptCloudflareRecovery(200, "cloudflare", null, "challenge"))
        assertFalse(shouldAttemptCloudflareRecovery(503, "nginx", null, null))
        assertFalse(shouldAttemptCloudflareRecovery(503, null, null, null))
    }

    @Test
    fun ignoresUnrelatedStatuses() {
        assertFalse(shouldAttemptCloudflareRecovery(200, "cloudflare", "abc123", null))
        assertFalse(shouldAttemptCloudflareRecovery(404, "cloudflare", "abc123", null))
    }
}

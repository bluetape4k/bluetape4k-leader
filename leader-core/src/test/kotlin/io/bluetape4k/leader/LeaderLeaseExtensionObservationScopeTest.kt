package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

class LeaderLeaseExtensionObservationScopeTest {

    @Test
    fun `scope is restored after nested blocking and coroutine boundaries`() = runTest {
        val outer = LeaderLeaseExtensionObservers.addScopedObserver { }
        val inner = LeaderLeaseExtensionObservers.addScopedObserver { }

        outer.use {
            inner.use {
                outer.withScope {
                    LeaderLeaseExtensionObservationScope.currentOrNull() shouldBeSameInstanceAs outer
                    inner.withScope {
                        LeaderLeaseExtensionObservationScope.currentOrNull() shouldBeSameInstanceAs inner
                    }
                    LeaderLeaseExtensionObservationScope.currentOrNull() shouldBeSameInstanceAs outer
                }
                withContext(Dispatchers.Default + outer.asContextElement()) {
                    LeaderLeaseExtensionObservationScope.currentOrNull() shouldBeSameInstanceAs outer
                }
            }
        }

        LeaderLeaseExtensionObservationScope.currentOrNull().shouldBeNull()
    }

    @Test
    fun `closed scope cannot be installed and close is idempotent`() {
        val scope = LeaderLeaseExtensionObservers.addScopedObserver { }

        scope.close()
        scope.close()

        scope.isActive().shouldBeFalse()
        scope.withScope {
            LeaderLeaseExtensionObservationScope.currentOrNull().shouldBeNull()
        }
    }

    @Test
    fun `scope handle does not reveal capability state`() {
        val scope = LeaderLeaseExtensionObservers.addScopedObserver { }

        try {
            scope.toString().contains("observer", ignoreCase = true).shouldBeFalse()
            scope.toString().contains("scope", ignoreCase = true).shouldBeTrue()
        } finally {
            scope.close()
        }
    }
}

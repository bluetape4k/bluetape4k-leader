package io.bluetape4k.leader.examples.prometheus

internal data class PrometheusScrapeResponse(
    val statusCode: Int,
    val body: String,
)

internal fun PrometheusScrapeResponse.requireSuccessful(): String {
    if (statusCode != 200) {
        throw AssertionError(
            "Prometheus scrape endpoint returned status=$statusCode (expected=200)\nbody=$body",
        )
    }
    return body
}

internal fun String.requireMetrics(metricNames: Iterable<String>) {
    val missing = metricNames.filterNot(::contains)
    if (missing.isNotEmpty()) {
        throw AssertionError(
            "Prometheus scrape is missing metrics=${missing.joinToString()}\nbody=$this",
        )
    }
}

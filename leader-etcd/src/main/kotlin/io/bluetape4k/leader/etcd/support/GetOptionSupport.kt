package io.bluetape4k.leader.etcd.support

import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.GetOption.SortOrder
import io.etcd.jetcd.options.GetOption.SortTarget

inline fun getOption(block: GetOption.Builder.() -> Unit): GetOption =
    GetOption.builder().apply(block).build()

fun getOptionOf(
    isPrefix: Boolean? = null,
    sortField: SortTarget? = null,
    sortOrder: SortOrder? = null,
    limit: Long? = null,
    block: GetOption.Builder.() -> Unit = {},
): GetOption = getOption {

    isPrefix?.let { isPrefix(it) }
    sortField?.let { withSortField(it) }
    sortOrder?.let { withSortOrder(it) }
    limit?.let { withLimit(it) }

    build()
}

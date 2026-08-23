package io.bluetape4k.leader.spring.scheduling

import java.lang.reflect.Method

/**
 * startup에 수집된 scheduled policy metadata를 immutable lookup으로 제공합니다.
 *
 * 이 registry는 scheduler, trigger, executor, subscription, Observation을 만들지 않으며
 * managed aspect bean의 target identity와 method signature만 보관합니다.
 */
class LeaderScheduledPolicyRegistry(
    configured: List<LeaderScheduledPolicyProperties.Policy>,
) {

    private val configuredBySelector: Map<String, LeaderScheduledPolicyProperties.Policy> =
        configured.associateByTo(linkedMapOf()) { policy ->
            parseSelector(policy.selector)
            policy.selector
        }.also { policies ->
            check(policies.size == configured.size) {
                "Duplicate scheduled policy selector(s): " +
                    configured.groupingBy { it.selector }.eachCount().filterValues { it > 1 }.keys
            }
    }

    private val observedSelectors = linkedSetOf<String>()
    private val mutableBindings = linkedMapOf<
        TargetIdentity,
        MutableMap<MethodSignature, LeaderScheduledPolicyProperties.Policy>,
    >()
    private val mutableSelectors = linkedMapOf<TargetIdentity, MutableMap<String, MethodSignature>>()

    private var frozen = false
    private var frozenBindings:
        Map<TargetIdentity, Map<MethodSignature, LeaderScheduledPolicyProperties.Policy>> = emptyMap()

    /** user bean의 scheduled method와 property binding을 registry에 추가합니다. */
    fun register(
        beanName: String,
        target: Any,
        method: Method,
        policy: LeaderScheduledPolicyProperties.Policy,
    ) {
        check(!frozen) { "Scheduled policy registry is already frozen" }

        val selector = selectorOf(beanName, method.name)
        val configuredPolicy = configuredBySelector[selector]
            ?: error("No configured scheduled policy for selector '$selector'")
        require(configuredPolicy == policy) {
            "Policy mismatch for scheduled selector '$selector'"
        }

        val targetIdentity = TargetIdentity(target)
        val signature = MethodSignature.from(method)
        val selectorsForTarget = mutableSelectors.getOrPut(targetIdentity) { linkedMapOf() }
        val previousSignature = selectorsForTarget[selector]
        when {
            previousSignature == null -> selectorsForTarget[selector] = signature
            previousSignature != signature -> error(
                "Ambiguous scheduled policy selector '$selector': overloaded methods are not supported",
            )
            else -> error("Duplicate scheduled policy registration for selector '$selector'")
        }

        mutableBindings.getOrPut(targetIdentity) { linkedMapOf() }[signature] = policy
        observedSelectors += selector
    }

    /** annotation이 우선되는 configured selector가 유효한 scheduled method임을 기록합니다. */
    fun markObserved(selector: String) {
        check(!frozen) { "Scheduled policy registry is already frozen" }
        require(configuredBySelector.containsKey(selector)) {
            "Unknown scheduled policy selector '$selector'"
        }
        observedSelectors += selector
    }

    /** startup 수집을 종료하고 이후 lookup을 immutable snapshot으로 전환합니다. */
    fun freeze() {
        check(!frozen) { "Scheduled policy registry is already frozen" }

        val unmatched = configuredBySelector.keys - observedSelectors
        check(unmatched.isEmpty()) {
            "No @Scheduled method matched scheduled policy selector(s): ${unmatched.sorted().joinToString()}"
        }

        frozenBindings = mutableBindings.mapValues { (_, bindings) -> bindings.toMap() }.toMap()
        frozen = true
    }

    /** woven join point의 target identity와 method signature로 policy를 조회합니다. */
    fun lookup(method: Method, target: Any): LeaderScheduledPolicyProperties.Policy? {
        check(frozen) { "Scheduled policy registry must be frozen before lookup" }
        val bindings = frozenBindings[TargetIdentity(target)] ?: return null
        val exact = bindings[MethodSignature.from(method)]
        return exact ?: bindings.entries
            .firstOrNull { (signature, _) ->
                signature.name == method.name && signature.parameterTypes == method.parameterTypes.toList()
            }?.value
    }

    private fun selectorOf(beanName: String, methodName: String): String = "$beanName#$methodName"

    private fun parseSelector(selector: String): Pair<String, String> {
        require(selector.isNotBlank() && selector.trim() == selector && selector.none(Char::isWhitespace)) {
            "Scheduled policy selector must be a non-blank exact beanName#methodName value: '$selector'"
        }
        val separator = selector.indexOf('#')
        require(separator > 0 && separator == selector.lastIndexOf('#') && separator < selector.lastIndex) {
            "Scheduled policy selector must contain exactly one '#': '$selector'"
        }
        val beanName = selector.substring(0, separator)
        val methodName = selector.substring(separator + 1)
        require(beanName.isNotBlank() && methodName.isNotBlank()) {
            "Scheduled policy selector must contain non-blank bean and method names: '$selector'"
        }
        return beanName to methodName
    }

    private class TargetIdentity(val target: Any) {
        override fun equals(other: Any?): Boolean = other is TargetIdentity && target === other.target

        override fun hashCode(): Int = System.identityHashCode(target)
    }

    private data class MethodSignature(
        val declaringClass: Class<*>,
        val name: String,
        val parameterTypes: List<Class<*>>,
    ) {
        companion object {
            fun from(method: Method): MethodSignature = MethodSignature(
                declaringClass = method.declaringClass,
                name = method.name,
                parameterTypes = method.parameterTypes.toList(),
            )
        }
    }
}

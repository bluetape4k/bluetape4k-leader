package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderSlot
import java.net.URI

/**
 * 애플리케이션이 소유한 leader snapshot을 공개 redirect target으로 매핑합니다.
 *
 * 구현체는 전달된 snapshot과 요청 context만 사용해 bounded computation을 수행해야 합니다.
 * 원시 backend identity를 URI로 노출하지 말고, mapping할 수 없거나 policy 검증을 통과하지
 * 못하는 경우 `null`을 반환합니다. 일반 `Exception`은 정책이 fail closed로 처리하며,
 * `CancellationException`과 `InterruptedException`은 호출 스레드의 취소 의미를 보존합니다.
 */
fun interface LeaderRouteRedirectResolver {

    /**
     * 한 번의 authority 평가 결과로 공개 redirect URI를 계산합니다.
     *
     * `LeaderState`는 built-in `STATE` authority가 이미 읽은 값일 때만 전달되며,
     * `CUSTOM` authority에서는 `null`일 수 있습니다.
     */
    fun resolve(context: LeaderRouteRedirectContext): URI?
}

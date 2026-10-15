package io.bluetape4k.leader.identity

/**
 * lock 이름을 입력으로 받아 해당 선거 실행의 leader ID를 생성하는 함수 계약입니다.
 */
fun interface LeaderIdProvider {
    /**
     * @param lockName ID를 생성할 선거 대상 lock 이름
     * @return 비어 있지 않은 leader ID 문자열
     */
    fun nextLeaderId(lockName: String): String
}

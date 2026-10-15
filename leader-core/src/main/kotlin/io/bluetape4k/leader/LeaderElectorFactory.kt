package io.bluetape4k.leader

/**
 * 단일 leader 선거 설정을 받아 해당 backend의 blocking elector를 생성하는 factory 계약입니다.
 */
fun interface LeaderElectorFactory {

    /**
     * @param options node ID, lock 대기 시간, lease 및 extension 설정
     * @return 전달한 backend 설정을 사용하는 blocking elector
     */
    fun create(options: LeaderElectionOptions): LeaderElector
}

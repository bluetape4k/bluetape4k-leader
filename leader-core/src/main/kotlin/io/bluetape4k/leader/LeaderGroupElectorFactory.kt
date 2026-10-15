package io.bluetape4k.leader

/**
 * 그룹 선거 설정을 받아 해당 backend의 blocking group elector를 생성하는 factory 계약입니다.
 */
fun interface LeaderGroupElectorFactory {

    /**
     * @param options node ID, 허용 slot 수, 대기 시간 및 lease 설정
     * @return 전달한 backend 설정을 사용하는 blocking group elector
     */
    fun create(options: LeaderGroupElectionOptions): LeaderGroupElector
}

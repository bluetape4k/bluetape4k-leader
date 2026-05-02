package io.bluetape4k.leader.exposed

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * leader-exposed-core 멀티 DB 테스트의 공통 베이스 클래스.
 *
 * H2, PostgreSQL, MySQL_V8 세 가지 DB를 대상으로 파라미터화 테스트를 실행.
 *
 * **주의**: 하위 클래스에서 자체 `companion object`를 정의하면 이 클래스의 `companion object`가
 * shadow되어 `@MethodSource("enableDialects")`가 `enableDialects()` 팩토리 메서드를 찾지 못함.
 * → 하위 클래스는 `companion object` 없이 `@MethodSource("enableDialects")` 직접 참조만 사용.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractExposedTableTest {

    companion object : KLogging() {
        @JvmStatic
        fun enableDialects(): List<TestDB> = listOf(
            TestDB.H2,
            TestDB.POSTGRESQL,
            TestDB.MYSQL_V8,
        )
    }
}

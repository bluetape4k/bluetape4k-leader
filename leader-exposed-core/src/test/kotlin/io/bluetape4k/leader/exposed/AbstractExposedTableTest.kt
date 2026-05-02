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
        /**
         * CI에서 `LEADER_TEST_DB` 환경 변수로 단일 DB를 선택할 수 있습니다.
         * 미설정 시 H2 / PostgreSQL / MySQL_V8 전체 실행 (로컬 개발 기본값).
         *
         * 허용 값: `H2`, `POSTGRESQL` (또는 `POSTGRES`), `MYSQL_V8` (또는 `MYSQL`)
         */
        @JvmStatic
        fun enableDialects(): List<TestDB> {
            val filter = System.getenv("LEADER_TEST_DB")?.uppercase()
                ?: return listOf(TestDB.H2, TestDB.POSTGRESQL, TestDB.MYSQL_V8)
            return when (filter) {
                "H2" -> listOf(TestDB.H2)
                "POSTGRESQL", "POSTGRES" -> listOf(TestDB.POSTGRESQL)
                "MYSQL_V8", "MYSQL" -> listOf(TestDB.MYSQL_V8)
                else -> listOf(TestDB.H2, TestDB.POSTGRESQL, TestDB.MYSQL_V8)
            }
        }
    }
}

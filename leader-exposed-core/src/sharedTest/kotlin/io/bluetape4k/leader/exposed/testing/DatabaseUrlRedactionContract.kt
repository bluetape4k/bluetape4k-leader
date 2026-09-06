package io.bluetape4k.leader.exposed.testing

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest

private const val INVALID_DATABASE_URL = "<invalid-database-url>"

internal fun databaseUrlRedactionContractTests(
    redact: (String) -> String,
): List<DynamicTest> =
    redactionCases.map { case ->
        dynamicTest(case.name) {
            val redacted = redact(case.url)

            case.expected?.let { redacted shouldBeEqualTo it }
            case.required.forEach(redacted::shouldContain)
            case.forbidden.forEach(redacted::shouldNotContain)
        }
    }

private data class RedactionCase(
    val name: String,
    val url: String,
    val expected: String? = null,
    val required: List<String> = emptyList(),
    val forbidden: List<String> = emptyList(),
)

private val redactionCases = listOf(
    RedactionCase(
        name = "JDBC userinfo password를 마스킹한다",
        url = "jdbc:postgresql://alice:jdbc-secret@db.example.com:5432/leader",
        required = listOf("jdbc:postgresql", "***", "db.example.com", "leader"),
        forbidden = listOf("alice", "jdbc-secret"),
    ),
    RedactionCase(
        name = "R2DBC userinfo password를 마스킹한다",
        url = "r2dbc:postgresql://alice:r2dbc-secret@db.example.com:5432/leader",
        required = listOf("r2dbc:postgresql", "***", "db.example.com", "leader"),
        forbidden = listOf("alice", "r2dbc-secret"),
    ),
    RedactionCase(
        name = "JDBC query credential을 제거한다",
        url = "jdbc:postgresql://db.example.com/leader?user=alice&password=query-secret",
        required = listOf("jdbc:postgresql", "db.example.com", "leader"),
        forbidden = listOf("alice", "query-secret", "?", "password="),
    ),
    RedactionCase(
        name = "R2DBC query token을 제거한다",
        url = "r2dbc:postgresql://db.example.com/leader?access_token=query-token&ssl=true",
        required = listOf("r2dbc:postgresql", "db.example.com", "leader"),
        forbidden = listOf("query-token", "?", "access_token="),
    ),
    RedactionCase(
        name = "URL fragment credential을 제거한다",
        url = "jdbc:postgresql://db.example.com/leader#token=fragment-secret",
        required = listOf("jdbc:postgresql", "db.example.com", "leader"),
        forbidden = listOf("fragment-secret", "#", "token="),
    ),
    RedactionCase(
        name = "JDBC semicolon credential을 제거한다",
        url = "jdbc:h2:mem:leader;USER=sa;PASSWORD=semicolon-secret",
        expected = "jdbc:h2:<redacted>",
        forbidden = listOf("semicolon-secret", ";", "PASSWORD="),
    ),
    RedactionCase(
        name = "R2DBC semicolon credential을 제거한다",
        url = "r2dbc:h2:mem:///leader;USER=sa;PASSWORD=semicolon-secret",
        expected = "r2dbc:h2:<redacted>",
        forbidden = listOf("semicolon-secret", ";", "PASSWORD="),
    ),
    RedactionCase(
        name = "인코딩된 JDBC semicolon credential도 fail-closed 처리한다",
        url = "jdbc:h2:mem:leader%3BUSER=sa%3BPASSWORD=encoded-secret",
        expected = "jdbc:h2:<redacted>",
        forbidden = listOf("encoded-secret", "PASSWORD=", "%3B"),
    ),
    RedactionCase(
        name = "인코딩된 R2DBC semicolon credential도 fail-closed 처리한다",
        url = "r2dbc:h2:mem:///leader%3BUSER=sa%3BPASSWORD=encoded-secret",
        expected = "r2dbc:h2:<redacted>",
        forbidden = listOf("encoded-secret", "PASSWORD=", "%3B"),
    ),
    RedactionCase(
        name = "임의 opaque JDBC URL은 backend만 유지한다",
        url = "jdbc:vendor:opaque-secret",
        expected = "jdbc:vendor:<redacted>",
        forbidden = listOf("opaque-secret"),
    ),
    RedactionCase(
        name = "SQL Server authority 뒤 semicolon credential을 제거한다",
        url = "jdbc:sqlserver://db.example.com;databaseName=leader;user=alice;password=sqlserver-secret",
        expected = "jdbc:sqlserver://db.example.com",
        forbidden = listOf("alice", "sqlserver-secret", ";", "password="),
    ),
    RedactionCase(
        name = "malformed JDBC URL은 fail-closed 처리한다",
        url = "jdbc:postgresql://alice:malformed-secret@[bad-host",
        expected = INVALID_DATABASE_URL,
        forbidden = listOf("alice", "malformed-secret"),
    ),
    RedactionCase(
        name = "malformed R2DBC URL은 fail-closed 처리한다",
        url = "r2dbc:postgresql://alice:malformed-secret@[bad-host",
        expected = INVALID_DATABASE_URL,
        forbidden = listOf("alice", "malformed-secret"),
    ),
    RedactionCase(
        name = "특수문자 JDBC password 파싱 실패도 fail-closed 처리한다",
        url = "jdbc:postgresql://alice:p@ss word@db.example.com/leader",
        expected = INVALID_DATABASE_URL,
        forbidden = listOf("alice", "p@ss word"),
    ),
    RedactionCase(
        name = "지원하지 않는 URL 형식은 fail-closed 처리한다",
        url = "not::a::valid::url?password=unknown-secret",
        expected = INVALID_DATABASE_URL,
        forbidden = listOf("unknown-secret"),
    ),
    RedactionCase(
        name = "credential 없는 JDBC URL은 유지한다",
        url = "jdbc:postgresql://db.example.com:5432/leader",
        expected = "jdbc:postgresql://db.example.com:5432/leader",
    ),
    RedactionCase(
        name = "credential 없는 opaque R2DBC H2 URL도 fail-closed 처리한다",
        url = "r2dbc:h2:mem:///leader",
        expected = "r2dbc:h2:<redacted>",
    ),
)

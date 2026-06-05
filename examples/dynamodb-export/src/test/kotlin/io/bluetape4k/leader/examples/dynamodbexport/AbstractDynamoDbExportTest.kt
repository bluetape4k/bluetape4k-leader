package io.bluetape4k.leader.examples.dynamodbexport

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElectionOptions
import io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderElector
import io.bluetape4k.testcontainers.aws.DynamoDbLocalServer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractDynamoDbExportTest {

    companion object {
        private val container = DynamoDbLocalServer.Launcher.dynamoDb
        private lateinit var syncClient: DynamoDbClient
        private lateinit var asyncClient: DynamoDbAsyncClient
        private lateinit var sharedLockTableName: String
        private lateinit var sharedExportTableName: String

        @BeforeAll
        @JvmStatic
        fun startDynamoDb() {
            val credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(container.awsAccessKey, container.awsSecretKey),
            )
            val region = Region.of(container.regionName)
            syncClient = DynamoDbClient.builder()
                .endpointOverride(container.awsEndpoint)
                .credentialsProvider(credentials)
                .region(region)
                .build()
            asyncClient = DynamoDbAsyncClient.builder()
                .endpointOverride(container.awsEndpoint)
                .credentialsProvider(credentials)
                .region(region)
                .build()

            val suffix = Base58.randomString(12)
            sharedLockTableName = "leader_export_locks_$suffix"
            sharedExportTableName = "leader_exports_$suffix"
            DynamoDbExportTable.createLockTable(syncClient, sharedLockTableName)
            DynamoDbExportTable.createExportTable(syncClient, sharedExportTableName)
        }

        @AfterAll
        @JvmStatic
        fun stopDynamoDb() {
            if (::asyncClient.isInitialized) {
                asyncClient.close()
            }
            if (::syncClient.isInitialized) {
                syncClient.close()
            }
        }
    }

    protected val exportTable: DynamoDbExportTable
        get() = DynamoDbExportTable(syncClient, sharedExportTableName)

    protected fun randomBatchId(): String =
        "billing-${Base58.randomString(8)}"

    protected fun randomLockName(): String =
        "scheduled-export-${Base58.randomString(8)}"

    protected fun randomKeyPrefix(): String =
        "test-${Base58.randomString(8)}"

    protected fun newRunner(
        nodeId: String,
        lockName: String = randomLockName(),
        keyPrefix: String = randomKeyPrefix(),
        leaderOptions: LeaderElectionOptions = LeaderElectionOptions(
            waitTime = 150.milliseconds,
            leaseTime = 5.seconds,
        ),
    ): DynamoDbScheduledExportRunner {
        val options = DynamoDbExportRunnerOptions(
            nodeId = nodeId,
            lockName = lockName,
            leaderOptions = leaderOptions,
        )
        val elector = DynamoDbSuspendLeaderElector(
            asyncClient,
            DynamoDbLeaderElectionOptions(
                leaderOptions = options.leaderOptions,
                tableName = sharedLockTableName,
                keyPrefix = keyPrefix,
                clockSkewTolerance = 10.milliseconds,
                ttlPadding = 1.seconds,
            ),
        )
        return DynamoDbScheduledExportRunner(options, elector, exportTable)
    }
}

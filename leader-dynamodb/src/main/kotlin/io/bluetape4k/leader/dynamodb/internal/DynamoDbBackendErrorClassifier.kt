package io.bluetape4k.leader.dynamodb.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException

internal object DynamoDbBackendErrorClassifier : BackendErrorClassifier {

    private val transientErrorCodes = setOf(
        "InternalServerError",
        "InternalServerErrorException",
        "ProvisionedThroughputExceededException",
        "RequestLimitExceeded",
        "RequestLimitExceededException",
        "ThrottlingException",
        "TransactionConflictException",
    )

    private val nonTransientErrorCodes = setOf(
        "AccessDeniedException",
        "ConditionalCheckFailedException",
        "ResourceNotFoundException",
        "ValidationException",
    )

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is SdkClientException -> BackendErrorKind.TRANSIENT
        is DynamoDbException -> classifyDynamoDb(cause)
        else -> null
    }

    private fun classifyDynamoDb(cause: DynamoDbException): BackendErrorKind? {
        val code = cause.awsErrorDetails()?.errorCode()
        return when {
            code in transientErrorCodes -> BackendErrorKind.TRANSIENT
            code in nonTransientErrorCodes -> BackendErrorKind.NON_TRANSIENT
            cause.statusCode() >= 500 -> BackendErrorKind.TRANSIENT
            else -> null
        }
    }
}

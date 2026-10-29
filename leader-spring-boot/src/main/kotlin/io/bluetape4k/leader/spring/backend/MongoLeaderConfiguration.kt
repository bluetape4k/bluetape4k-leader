package io.bluetape4k.leader.spring.backend

import com.mongodb.client.MongoDatabase
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.mongodb.MongoLeaderElectionOptions
import io.bluetape4k.leader.mongodb.MongoLeaderElector
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElectionOptions
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElector
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderElector
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderGroupElector
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
import io.bluetape4k.logging.KLogging
import org.bson.Document
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.mongodb.kotlin.client.coroutine.MongoDatabase as CoroutineMongoDatabase

/** MongoDB database bean이 있을 때 configured collection을 사용하는 blocking과 coroutine elector를 등록합니다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(com.mongodb.client.MongoCollection::class)
class MongoLeaderConfiguration {

    companion object: KLogging()

    private fun electionOptions(props: LeaderProperties): MongoLeaderElectionOptions =
        MongoLeaderElectionOptions(leaderOptions = PropertiesAdapter.toCommonElection(props))

    private fun groupOptions(props: LeaderProperties): MongoLeaderGroupElectionOptions =
        MongoLeaderGroupElectionOptions(leaderGroupOptions = PropertiesAdapter.toCommonGroup(props))

    @Bean
    @ConditionalOnBean(MongoDatabase::class)
    @ConditionalOnMissingBean(name = ["mongoLeaderElector"])
    fun mongoLeaderElector(
        db: MongoDatabase,
        props: LeaderProperties,
        recorderProvider: ObjectProvider<SafeLeaderHistoryRecorder>,
    ): MongoLeaderElector {
        val collection = db.getCollection(props.mongo.singleCollection, Document::class.java)
        return MongoLeaderElector(collection, electionOptions(props), recorderProvider.ifAvailable)
    }

    @Bean
    @ConditionalOnBean(MongoDatabase::class)
    @ConditionalOnMissingBean(name = ["mongoLeaderGroupElector"])
    fun mongoLeaderGroupElector(
        db: MongoDatabase,
        props: LeaderProperties,
        recorderProvider: ObjectProvider<SafeLeaderHistoryRecorder>,
    ): MongoLeaderGroupElector {
        val collection = db.getCollection(props.mongo.groupCollection, Document::class.java)
        return MongoLeaderGroupElector(collection, groupOptions(props), recorderProvider.ifAvailable)
    }

    @Bean
    @ConditionalOnBean(CoroutineMongoDatabase::class)
    @ConditionalOnMissingBean(name = ["mongoSuspendLeaderElector"])
    fun mongoSuspendLeaderElector(
        coroutineDb: CoroutineMongoDatabase,
        props: LeaderProperties,
        recorderProvider: ObjectProvider<SuspendSafeLeaderHistoryRecorder>,
    ): MongoSuspendLeaderElector = createSuspendBackendBean(
        operationName = "mongoSuspendLeaderElector",
    ) {
        MongoSuspendLeaderElector(
            coroutineDb.getCollection<Document>(props.mongo.singleCollection),
            electionOptions(props),
            recorderProvider.ifAvailable,
        )
    }

    @Bean
    @ConditionalOnBean(MongoDatabase::class, CoroutineMongoDatabase::class)
    @ConditionalOnMissingBean(name = ["mongoSuspendLeaderGroupElector"])
    fun mongoSuspendLeaderGroupElector(
        db: MongoDatabase,
        coroutineDb: CoroutineMongoDatabase,
        props: LeaderProperties,
        recorderProvider: ObjectProvider<SuspendSafeLeaderHistoryRecorder>,
    ): MongoSuspendLeaderGroupElector = createSuspendBackendBean(
        operationName = "mongoSuspendLeaderGroupElector",
    ) {
        MongoSuspendLeaderGroupElector(
            db.getCollection(props.mongo.groupCollection, Document::class.java),
            coroutineDb.getCollection<Document>(props.mongo.groupCollection),
            groupOptions(props),
            recorderProvider.ifAvailable,
        )
    }
}

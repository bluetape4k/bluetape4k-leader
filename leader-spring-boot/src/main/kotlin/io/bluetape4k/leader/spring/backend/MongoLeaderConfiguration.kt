package io.bluetape4k.leader.spring.backend

import com.mongodb.client.MongoDatabase
import com.mongodb.kotlin.client.coroutine.MongoDatabase as CoroutineMongoDatabase
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.mongodb.MongoLeaderElector
import io.bluetape4k.leader.mongodb.MongoLeaderElectionOptions
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElector
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElectionOptions
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderElector
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderGroupElector
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * MongoDB 백엔드 자동 구성.
 *
 * - Sync 빈: `com.mongodb.client.MongoDatabase` 빈 필요
 * - Suspend 빈: `com.mongodb.kotlin.client.coroutine.MongoDatabase` 빈 필요
 *
 * 컬렉션 이름은 `bluetape4k.leader.mongo.{single|group}-collection` 속성으로 지정합니다.
 *
 * Suspend 빈은 startup 시 스키마 인덱스 초기화를 위해 [runBlocking]을 사용합니다.
 * 한 번만 호출되며 startup 이후에는 영향을 주지 않습니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(com.mongodb.client.MongoCollection::class)
class MongoLeaderConfiguration {

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
    ): MongoLeaderGroupElector {
        val collection = db.getCollection(props.mongo.groupCollection, Document::class.java)
        return MongoLeaderGroupElector(collection, groupOptions(props))
    }

    @Bean
    @ConditionalOnBean(CoroutineMongoDatabase::class)
    @ConditionalOnMissingBean(name = ["mongoSuspendLeaderElector"])
    fun mongoSuspendLeaderElector(
        coroutineDb: CoroutineMongoDatabase,
        props: LeaderProperties,
        recorderProvider: ObjectProvider<SuspendSafeLeaderHistoryRecorder>,
    ): MongoSuspendLeaderElector = runBlocking {
        val collection = coroutineDb.getCollection<Document>(props.mongo.singleCollection)
        MongoSuspendLeaderElector(collection, electionOptions(props), recorderProvider.ifAvailable)
    }

    @Bean
    @ConditionalOnBean(MongoDatabase::class, CoroutineMongoDatabase::class)
    @ConditionalOnMissingBean(name = ["mongoSuspendLeaderGroupElector"])
    fun mongoSuspendLeaderGroupElector(
        db: MongoDatabase,
        coroutineDb: CoroutineMongoDatabase,
        props: LeaderProperties,
    ): MongoSuspendLeaderGroupElector = runBlocking {
        val syncCollection = db.getCollection(props.mongo.groupCollection, Document::class.java)
        val coroutineCollection = coroutineDb.getCollection<Document>(props.mongo.groupCollection)
        MongoSuspendLeaderGroupElector(syncCollection, coroutineCollection, groupOptions(props))
    }
}

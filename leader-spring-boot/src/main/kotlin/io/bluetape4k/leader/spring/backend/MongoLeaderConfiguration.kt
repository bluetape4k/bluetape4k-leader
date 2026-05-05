package io.bluetape4k.leader.spring.backend

import com.mongodb.client.MongoDatabase
import com.mongodb.kotlin.client.coroutine.MongoDatabase as CoroutineMongoDatabase
import io.bluetape4k.leader.mongodb.MongoLeaderElection
import io.bluetape4k.leader.mongodb.MongoLeaderElectionOptions
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElection
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElectionOptions
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderElection
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderGroupElection
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
import kotlinx.coroutines.runBlocking
import org.bson.Document
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
    @ConditionalOnMissingBean(name = ["mongoLeaderElection"])
    fun mongoLeaderElection(
        db: MongoDatabase,
        props: LeaderProperties,
    ): MongoLeaderElection {
        val collection = db.getCollection(props.mongo.singleCollection, Document::class.java)
        return MongoLeaderElection(collection, electionOptions(props))
    }

    @Bean
    @ConditionalOnBean(MongoDatabase::class)
    @ConditionalOnMissingBean(name = ["mongoLeaderGroupElection"])
    fun mongoLeaderGroupElection(
        db: MongoDatabase,
        props: LeaderProperties,
    ): MongoLeaderGroupElection {
        val collection = db.getCollection(props.mongo.groupCollection, Document::class.java)
        return MongoLeaderGroupElection(collection, groupOptions(props))
    }

    @Bean
    @ConditionalOnBean(CoroutineMongoDatabase::class)
    @ConditionalOnMissingBean(name = ["mongoSuspendLeaderElection"])
    fun mongoSuspendLeaderElection(
        coroutineDb: CoroutineMongoDatabase,
        props: LeaderProperties,
    ): MongoSuspendLeaderElection = runBlocking {
        val collection = coroutineDb.getCollection<Document>(props.mongo.singleCollection)
        MongoSuspendLeaderElection(collection, electionOptions(props))
    }

    @Bean
    @ConditionalOnBean(MongoDatabase::class, CoroutineMongoDatabase::class)
    @ConditionalOnMissingBean(name = ["mongoSuspendLeaderGroupElection"])
    fun mongoSuspendLeaderGroupElection(
        db: MongoDatabase,
        coroutineDb: CoroutineMongoDatabase,
        props: LeaderProperties,
    ): MongoSuspendLeaderGroupElection = runBlocking {
        val syncCollection = db.getCollection(props.mongo.groupCollection, Document::class.java)
        val coroutineCollection = coroutineDb.getCollection<Document>(props.mongo.groupCollection)
        MongoSuspendLeaderGroupElection(syncCollection, coroutineCollection, groupOptions(props))
    }
}

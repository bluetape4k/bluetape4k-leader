package io.bluetape4k.leader.mongodb

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.io.ObjectStreamClass

class MongoLeaderOptionsCompatibilityTest {

    @Test
    fun `기존 직렬화 UID를 유지한다`() {
        ObjectStreamClass.lookup(MongoLeaderElectionOptions::class.java).serialVersionUID shouldBeEqualTo
                MONGO_LEADER_ELECTION_OPTIONS_SERIAL_VERSION_UID
        ObjectStreamClass.lookup(MongoLeaderGroupElectionOptions::class.java).serialVersionUID shouldBeEqualTo
                MONGO_LEADER_GROUP_ELECTION_OPTIONS_SERIAL_VERSION_UID
    }

    companion object {
        private const val MONGO_LEADER_ELECTION_OPTIONS_SERIAL_VERSION_UID = 8621706450900702701L
        private const val MONGO_LEADER_GROUP_ELECTION_OPTIONS_SERIAL_VERSION_UID = -6752496615359943498L
    }
}

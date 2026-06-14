#!/usr/bin/env node

import { klass, relation, renderClassDiagram } from "./readme-diagrams/lib/class-diagram-renderer.mjs";

renderClassDiagram({
  slug: "leader-core-class-01",
  kind: "class",
  width: 1720,
  height: 1510,
  title: "Core Leader API Class Diagram",
  subtitle: "Public contracts, state snapshots, result semantics, listener events, and local single-JVM implementations.",
  desc: "Source-backed UML class diagram for leader-core public contracts and local implementations.",
  intent: "Help implementers choose the correct leader election contract by showing which public interfaces extend the state query contracts, where result and slot semantics attach, how listener events are published, and how local single-JVM implementations map to those contracts.",
  evidence: "kind=class; best-practice=class-diagram-style-v3; rejected-patterns=relationship-heavy-grid,inheritance-arrow-not-on-parent,undifferentiated-class-route-overlap",
  sourceRead: "leader-core/README.md; leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElector.kt; AsyncLeaderElector.kt; VirtualThreadLeaderElector.kt; LeaderGroupElector.kt; AsyncLeaderGroupElector.kt; coroutines/SuspendLeaderElector.kt; coroutines/SuspendLeaderGroupElector.kt; LeaderRunResult.kt; LeaderElectionListener.kt; local/LocalLeaderElector.kt; local/LocalLeaderGroupElector.kt",
  classes: [
    klass("singleState", "LeaderElectionState", "<<state query>>", ["+ state(lockName): LeaderState", "best-effort snapshot only"], 150, 172, 300, 130, "blue"),
    klass("groupState", "LeaderGroupElectionState", "<<group state query>>", ["+ maxLeaders: Int", "+ activeCount(lockName): Int", "+ availableSlots(lockName): Int", "+ state(lockName): LeaderGroupState"], 1124, 172, 338, 158, "teal"),

    klass("asyncElector", "AsyncLeaderElector", "<<async contract>>", ["+ runAsyncIfLeader(lockName)", "+ runAsyncIfLeader(slot)", "+ runAsyncIfLeaderResult(slot)"], 150, 424, 318, 148, "green"),
    klass("virtualElector", "VirtualThreadLeaderElector", "<<virtual-thread contract>>", ["+ runAsyncIfLeader(lockName)", "+ runAsyncIfLeader(slot)", "+ runAsyncIfLeaderResult(slot)"], 518, 424, 356, 148, "olive"),
    klass("asyncGroup", "AsyncLeaderGroupElector", "<<async group contract>>", ["+ runAsyncIfLeader(lockName)", "+ runAsyncIfLeader(slot)", "+ runAsyncIfLeaderResult(slot)"], 1050, 424, 366, 148, "teal"),

    klass("leaderElector", "LeaderElector", "<<blocking contract>>", ["+ runIfLeader(lockName): T?", "+ runIfLeaderResult(lockName)", "+ runIfLeader(slot)"], 150, 770, 318, 148, "green"),
    klass("suspendElector", "SuspendLeaderElector", "<<coroutine contract>>", ["+ suspend runIfLeader(lockName)", "+ runIfLeaderResultSuspend(slot)", "+ state(lockName): LeaderState"], 518, 770, 356, 148, "purple"),
    klass("groupElector", "LeaderGroupElector", "<<blocking group contract>>", ["+ runIfLeader(lockName): T?", "+ runIfLeaderResult(lockName)", "+ runIfLeader(slot)"], 1050, 770, 366, 148, "teal"),
    klass("suspendGroup", "Suspend Group Elector", "<<coroutine group contract>>", ["SuspendLeaderGroupElector", "+ suspend runIfLeader(lockName)", "+ runIfLeaderResultSuspend(slot)", "+ activeCount / availableSlots"], 1434, 770, 236, 194, "purple"),

    klass("slot", "LeaderSlot", "<<identity carrier>>", ["+ lockName: String", "+ leaderId: String", "slot overload stamps audit identity"], 722, 182, 324, 148, "amber"),
    klass("result", "LeaderRunResult", "<<sealed result>>", ["Elected(value, leaderId)", "Skipped", "ActionFailed(cause)"], 708, 610, 330, 148, "pink"),
    klass("events", "LeaderElection Events", "<<listener support>>", ["LeaderElectionEventPublisher.events", "LeaderElectionListenerRegistry", "LeaderElectionListenerSupport"], 700, 1000, 358, 158, "indigo"),

    klass("localSingle", "Local Single-JVM Family", "<<implementations>>", ["LocalLeaderElector -> LeaderElector", "LocalAsync -> AsyncLeaderElector", "LocalVirtualThread -> VirtualThreadLeaderElector", "LocalSuspend -> SuspendLeaderElector"], 190, 1030, 430, 164, "gray"),
    klass("localGroup", "Local Group Family", "<<implementations>>", ["LocalLeaderGroup -> LeaderGroupElector", "LocalAsyncGroup -> AsyncLeaderGroupElector", "LocalSuspendGroup -> SuspendLeaderGroupElector", "Strategic local variants wrap policy"], 1080, 1030, 430, 164, "gray"),
  ],
  relations: [
    relation("asyncElector", "singleState", "inherit", [{ x: 309, y: 424 }, { x: 309, y: 302 }]),
    relation("leaderElector", "asyncElector", "inherit", [{ x: 309, y: 770 }, { x: 309, y: 572 }]),
    relation("virtualElector", "singleState", "inherit", [{ x: 696, y: 424 }, { x: 696, y: 372 }, { x: 382, y: 372 }, { x: 382, y: 302 }]),
    relation("suspendElector", "singleState", "inherit", [{ x: 696, y: 770 }, { x: 696, y: 632 }, { x: 500, y: 632 }, { x: 500, y: 236 }, { x: 450, y: 236 }]),

    relation("asyncGroup", "groupState", "inherit", [{ x: 1233, y: 424 }, { x: 1233, y: 330 }]),
    relation("groupElector", "asyncGroup", "inherit", [{ x: 1233, y: 770 }, { x: 1233, y: 572 }]),
    relation("suspendGroup", "groupState", "inherit", [{ x: 1552, y: 770 }, { x: 1552, y: 650 }, { x: 1520, y: 650 }, { x: 1520, y: 251 }, { x: 1462, y: 251 }]),

    relation("leaderElector", "result", "result", [{ x: 420, y: 770 }, { x: 420, y: 724 }, { x: 680, y: 724 }, { x: 680, y: 680 }, { x: 708, y: 680 }], "result API"),
    relation("groupElector", "result", "result", [{ x: 1098, y: 770 }, { x: 1098, y: 724 }, { x: 1180, y: 724 }, { x: 1180, y: 640 }, { x: 1038, y: 640 }], "slot result"),
    relation("slot", "leaderElector", "slot", [{ x: 722, y: 256 }, { x: 500, y: 256 }, { x: 500, y: 700 }, { x: 420, y: 700 }, { x: 420, y: 770 }], "slot overloads"),
    relation("slot", "groupElector", "slot", [{ x: 1010, y: 330 }, { x: 1010, y: 360 }, { x: 1440, y: 360 }, { x: 1440, y: 730 }, { x: 1390, y: 730 }, { x: 1390, y: 770 }], "audit id"),
    relation("events", "singleState", "event", [{ x: 700, y: 1078 }, { x: 650, y: 1078 }, { x: 650, y: 950 }, { x: 112, y: 950 }, { x: 112, y: 236 }, { x: 150, y: 236 }], "state events"),

    relation("localSingle", "leaderElector", "local", [{ x: 360, y: 1030 }, { x: 360, y: 918 }], "implements"),
    relation("localSingle", "suspendElector", "local", [{ x: 620, y: 1080 }, { x: 660, y: 1080 }, { x: 660, y: 918 }], "coroutines"),
    relation("localGroup", "groupElector", "local", [{ x: 1251, y: 1030 }, { x: 1251, y: 918 }], "implements"),
    relation("localGroup", "suspendGroup", "local", [{ x: 1510, y: 1080 }, { x: 1552, y: 1080 }, { x: 1552, y: 964 }], "coroutines"),
  ],
  legend: [
    { kind: "inherit", label: "generalization / interface inheritance" },
    { kind: "result", label: "LeaderRunResult return family" },
    { kind: "slot", label: "LeaderSlot audit identity overloads" },
    { kind: "local", label: "local single-JVM implementation mapping" },
    { kind: "event", label: "listener and event publication support" },
  ],
});

renderClassDiagram({
  slug: "leader-exposed-jdbc-class-01",
  kind: "class",
  width: 1720,
  height: 1580,
  title: "Exposed JDBC Leader Class Diagram",
  subtitle: "Public electors delegate to token locks, Exposed tables, schema initialization, and optional history recording.",
  desc: "Source-backed UML class diagram for the Exposed JDBC leader module.",
  intent: "Help module readers understand how the JDBC implementation maps core leader contracts to Exposed-based lock classes and tables: single leader uses one token row, group leader uses slot rows, virtual-thread support delegates to the blocking elector, and history recording writes best-effort audit rows.",
  evidence: "kind=class; best-practice=class-diagram-style-v3; rejected-patterns=relationship-heavy-grid,card-penetrating-connector,inheritance-arrow-not-on-parent",
  sourceRead: "leader-exposed-jdbc/README.md; leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderElector.kt; ExposedJdbcLeaderGroupElector.kt; ExposedJdbcVirtualThreadLeaderElector.kt; lock/ExposedJdbcLock.kt; lock/ExposedJdbcGroupLock.kt; lock/ExposedJdbcSchemaInitializer.kt; leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/tables/LeaderLockTable.kt; LeaderGroupLockTable.kt; LeaderLockHistoryTable.kt",
  classes: [
    klass("leaderContract", "LeaderElector", "<<core interface>>", ["+ runIfLeader(lockName)", "+ runAsyncIfLeader(lockName)"], 150, 170, 300, 128, "blue"),
    klass("virtualContract", "VirtualThreadLeaderElector", "<<core interface>>", ["+ runAsyncIfLeader(lockName)", "VirtualFuture<T?> result"], 540, 170, 360, 128, "olive"),
    klass("groupContract", "LeaderGroupElector", "<<core interface>>", ["+ maxLeaders: Int", "+ runIfLeader(lockName)", "+ state(lockName)"], 1120, 170, 340, 148, "teal"),

    klass("jdbcElector", "ExposedJdbcLeaderElector", "<<JDBC implementation>>", ["implements LeaderElector", "creates ExposedJdbcLock", "starts lease auto-extender", "records optional history"], 120, 430, 386, 170, "green"),
    klass("virtualElector", "ExposedJdbc VirtualThread", "<<adapter implementation>>", ["ExposedJdbcVirtualThreadLeaderElector", "delegate: ExposedJdbcLeaderElector", "wraps delegate in virtualFuture"], 560, 430, 360, 150, "olive"),
    klass("jdbcGroupElector", "ExposedJdbcLeaderGroupElector", "<<JDBC group implementation>>", ["implements LeaderGroupElector", "randomized slot iteration", "creates ExposedJdbcGroupLock", "state uses LeaderGroupLockTable"], 1060, 430, 420, 170, "teal"),

    klass("jdbcLock", "ExposedJdbcLock", "<<single lock delegate>>", ["token: Base58 fencing token", "UPDATE expired row", "INSERT new row on missing lock", "SELECT token ownership"], 120, 720, 420, 178, "amber"),
    klass("jdbcGroupLock", "ExposedJdbcGroupLock", "<<slot lock delegate>>", ["(lockName, slot) composite owner", "UPDATE expired slot", "INSERT slot row", "SELECT token + slot ownership"], 1040, 720, 430, 178, "amber"),
    klass("schemaInit", "ExposedJdbcSchemaInitializer", "<<schema bootstrap>>", ["ensureSchema(db)", "SchemaUtils.createMissingTablesAndColumns", "runs once per Database URL"], 650, 720, 390, 150, "purple"),
    klass("historySink", "ExposedLeaderHistorySink", "<<audit sink>>", ["record ACQUIRED", "record COMPLETED / FAILED", "cleanup old rows"], 650, 930, 390, 150, "indigo"),

    klass("lockTable", "LeaderLockTable", "<<Exposed table>>", ["PK lockName", "lockOwner", "token", "lockedAt / lockedUntil"], 120, 1040, 420, 170, "gray"),
    klass("historyTable", "LeaderLockHistoryTable", "<<Exposed table>>", ["id PK", "lockName + token", "status lifecycle", "slotId for group audit"], 650, 1148, 390, 170, "gray"),
    klass("groupTable", "LeaderGroupLockTable", "<<Exposed table>>", ["PK(lockName, slot)", "lockOwner", "token", "lockedAt / lockedUntil"], 1040, 1040, 430, 170, "gray"),
  ],
  relations: [
    relation("jdbcElector", "leaderContract", "inherit", [{ x: 313, y: 430 }, { x: 313, y: 298 }]),
    relation("virtualElector", "virtualContract", "inherit", [{ x: 740, y: 430 }, { x: 740, y: 298 }]),
    relation("jdbcGroupElector", "groupContract", "inherit", [{ x: 1270, y: 430 }, { x: 1270, y: 318 }]),

    relation("virtualElector", "jdbcElector", "dependency", [{ x: 560, y: 505 }, { x: 506, y: 505 }]),
    relation("jdbcElector", "jdbcLock", "local", [{ x: 313, y: 600 }, { x: 313, y: 720 }], "creates"),
    relation("jdbcGroupElector", "jdbcGroupLock", "local", [{ x: 1270, y: 600 }, { x: 1270, y: 720 }], "per slot"),

    relation("jdbcLock", "lockTable", "uses", [{ x: 313, y: 898 }, { x: 313, y: 1040 }], "UPDATE INSERT SELECT"),
    relation("jdbcGroupLock", "groupTable", "uses", [{ x: 1255, y: 898 }, { x: 1255, y: 1040 }], "slot rows"),
    relation("schemaInit", "lockTable", "dependency", [{ x: 650, y: 795 }, { x: 585, y: 795 }, { x: 585, y: 930 }, { x: 365, y: 930 }, { x: 365, y: 1040 }], "creates"),
    relation("schemaInit", "groupTable", "dependency", [{ x: 930, y: 720 }, { x: 930, y: 680 }, { x: 1505, y: 680 }, { x: 1505, y: 1000 }, { x: 1255, y: 1000 }, { x: 1255, y: 1040 }], "creates"),

    relation("jdbcElector", "historySink", "event", [{ x: 120, y: 545 }, { x: 78, y: 545 }, { x: 78, y: 1005 }, { x: 650, y: 1005 }], "optional history"),
    relation("jdbcGroupElector", "historySink", "event", [{ x: 1480, y: 545 }, { x: 1532, y: 545 }, { x: 1532, y: 1005 }, { x: 1040, y: 1005 }], "group audit"),
    relation("historySink", "historyTable", "uses", [{ x: 845, y: 1080 }, { x: 845, y: 1148 }], "writes"),
  ],
  legend: [
    { kind: "inherit", label: "core interface implementation" },
    { kind: "local", label: "elector creates lock delegate" },
    { kind: "uses", label: "Exposed table read/write dependency" },
    { kind: "dependency", label: "schema bootstrap dependency" },
    { kind: "event", label: "optional history recording path" },
  ],
});

renderClassDiagram({
  slug: "leader-exposed-r2dbc-class-01",
  kind: "class",
  width: 1720,
  height: 1600,
  title: "Exposed R2DBC Leader Class Diagram",
  subtitle: "Coroutine electors use R2DBC suspend transactions, insertIgnore locks, schema bootstrap, and optional audit history.",
  desc: "Source-backed UML class diagram for the Exposed R2DBC leader module.",
  intent: "Help coroutine service readers understand how the R2DBC implementation maps suspend leader contracts to non-blocking Exposed R2DBC lock classes and tables: single leader uses a token row, group leader iterates slot rows, factories and extension functions create suspend electors, and recordHistory controls audit writes.",
  evidence: "kind=class; best-practice=class-diagram-style-v3; rejected-patterns=relationship-heavy-grid,card-penetrating-connector,inheritance-arrow-not-on-parent",
  sourceRead: "leader-exposed-r2dbc/README.md; leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2DbcSuspendLeaderElector.kt; ExposedR2DbcSuspendLeaderGroupElector.kt; ExposedR2DbcSuspendLeaderElectorFactory.kt; ExposedR2DbcSuspendLeaderGroupElectorFactory.kt; lock/ExposedR2dbcLock.kt; lock/ExposedR2dbcGroupLock.kt; lock/ExposedR2dbcSchemaInitializer.kt; history/ExposedSuspendLeaderHistorySink.kt; leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/tables/LeaderLockTable.kt; LeaderGroupLockTable.kt; LeaderLockHistoryTable.kt",
  classes: [
    klass("suspendContract", "SuspendLeaderElector", "<<core suspend interface>>", ["+ suspend runIfLeader(lockName)", "+ runIfLeaderResultSuspend(slot)", "+ state(lockName): LeaderState"], 120, 170, 350, 148, "purple"),
    klass("factoryContract", "Suspend Factories", "<<core SPI interfaces>>", ["SuspendLeaderElectorFactory", "SuspendLeaderGroupElectorFactory", "create(options): suspend elector"], 575, 170, 392, 148, "blue"),
    klass("groupContract", "SuspendLeaderGroupElector", "<<core suspend group>>", ["+ maxLeaders: Int", "+ suspend runIfLeader(lockName)", "+ activeCount / availableSlots"], 1120, 170, 380, 148, "purple"),

    klass("r2dbcElector", "R2DBC Suspend Elector", "<<R2DBC implementation>>", ["ExposedR2DbcSuspendLeaderElector", "implements SuspendLeaderElector", "creates ExposedR2dbcLock", "NonCancellable unlock path"], 100, 430, 410, 170, "green"),
    klass("factoryImpl", "R2DBC Factory Entrypoints", "<<factory + extensions>>", ["ExposedR2DbcSuspendLeaderElectorFactory", "ExposedR2DbcSuspendLeaderGroupElectorFactory", "R2dbcDatabase.suspendRunIfLeader*", "invokes schema-safe electors"], 610, 430, 430, 170, "blue"),
    klass("r2dbcGroupElector", "R2DBC Suspend Group", "<<R2DBC group implementation>>", ["ExposedR2DbcSuspendLeaderGroupElector", "randomized slot iteration", "activeCountSuspend DB query", "recordHistory gates audit writes"], 1090, 430, 430, 170, "teal"),

    klass("r2dbcLock", "ExposedR2dbcLock", "<<single suspend lock>>", ["token: Base58 fencing token", "suspendTransaction per attempt", "UPDATE expired row", "insertIgnore then SELECT token"], 100, 720, 430, 178, "amber"),
    klass("schemaInit", "ExposedR2dbcSchemaInitializer", "<<coroutine schema bootstrap>>", ["Mutex + ConcurrentHashMap guard", "SchemaUtils.createMissingTablesAndColumns", "requires H2 MODE for insertIgnore"], 650, 710, 430, 168, "purple"),
    klass("r2dbcGroupLock", "ExposedR2dbcGroupLock", "<<slot suspend lock>>", ["(lockName, slot) composite owner", "suspendTransaction per slot", "insertIgnore slot row", "SELECT token + slot ownership"], 1090, 720, 430, 178, "amber"),

    klass("lockTable", "LeaderLockTable", "<<Exposed table>>", ["PK lockName", "lockOwner", "token", "lockedAt / lockedUntil"], 100, 1050, 430, 170, "gray"),
    klass("historySink", "Exposed Suspend History", "<<audit sink + inline group audit>>", ["ExposedSuspendLeaderHistorySink", "SuspendSafeLeaderHistoryRecorder", "group elector writes when recordHistory", "best-effort ACQUIRED/COMPLETED/FAILED"], 650, 1010, 430, 190, "indigo"),
    klass("groupTable", "LeaderGroupLockTable", "<<Exposed table>>", ["PK(lockName, slot)", "lockOwner", "token", "lockedAt / lockedUntil"], 1090, 1050, 430, 170, "gray"),
    klass("historyTable", "LeaderLockHistoryTable", "<<Exposed table>>", ["id PK", "lockName + token", "status lifecycle", "slot / slotId for group audit"], 650, 1270, 430, 170, "gray"),
  ],
  relations: [
    relation("r2dbcElector", "suspendContract", "inherit", [{ x: 305, y: 430 }, { x: 305, y: 318 }]),
    relation("factoryImpl", "factoryContract", "inherit", [{ x: 825, y: 430 }, { x: 825, y: 318 }]),
    relation("r2dbcGroupElector", "groupContract", "inherit", [{ x: 1305, y: 430 }, { x: 1305, y: 318 }]),

    relation("factoryImpl", "r2dbcElector", "dependency", [{ x: 610, y: 500 }, { x: 510, y: 500 }], "creates"),
    relation("factoryImpl", "r2dbcGroupElector", "dependency", [{ x: 1040, y: 500 }, { x: 1090, y: 500 }], "creates"),
    relation("r2dbcElector", "r2dbcLock", "local", [{ x: 305, y: 600 }, { x: 305, y: 720 }], "creates"),
    relation("r2dbcGroupElector", "r2dbcGroupLock", "local", [{ x: 1305, y: 600 }, { x: 1305, y: 720 }], "per slot"),

    relation("r2dbcLock", "lockTable", "uses", [{ x: 305, y: 898 }, { x: 305, y: 1050 }]),
    relation("r2dbcGroupLock", "groupTable", "uses", [{ x: 1305, y: 898 }, { x: 1305, y: 1050 }]),
    relation("schemaInit", "lockTable", "dependency", [{ x: 650, y: 794 }, { x: 586, y: 794 }, { x: 586, y: 990 }, { x: 315, y: 990 }, { x: 315, y: 1050 }]),
    relation("schemaInit", "groupTable", "dependency", [{ x: 865, y: 710 }, { x: 865, y: 660 }, { x: 1600, y: 660 }, { x: 1600, y: 1010 }, { x: 1305, y: 1010 }, { x: 1305, y: 1050 }]),

    relation("r2dbcElector", "historySink", "event", [{ x: 100, y: 545 }, { x: 68, y: 545 }, { x: 68, y: 950 }, { x: 700, y: 950 }, { x: 700, y: 1010 }], "optional recorder"),
    relation("r2dbcGroupElector", "historySink", "event", [{ x: 1520, y: 545 }, { x: 1552, y: 545 }, { x: 1552, y: 970 }, { x: 1030, y: 970 }, { x: 1030, y: 1010 }], "recordHistory"),
    relation("historySink", "historyTable", "uses", [{ x: 865, y: 1200 }, { x: 865, y: 1270 }], "writes"),
  ],
  legend: [
    { kind: "inherit", label: "core suspend interface implementation" },
    { kind: "local", label: "elector creates suspend lock delegate" },
    { kind: "uses", label: "Exposed R2DBC table read/write dependency" },
    { kind: "dependency", label: "schema/factory dependency" },
    { kind: "event", label: "optional suspend history recording path" },
  ],
});

renderClassDiagram({
  slug: "leader-hazelcast-class-01",
  kind: "class",
  width: 1840,
  height: 1990,
  title: "Hazelcast Leader Class Diagram",
  subtitle: "Public electors map core contracts to token-based IMap locks, slot keys, extension delegates, and TTL ownership checks.",
  desc: "Source-backed UML class diagram for the Hazelcast leader module.",
  intent: "Help Hazelcast users understand that this module does not use CP Subsystem locks: public blocking, async, virtual-thread-safe, and coroutine electors delegate to token-owned Hazelcast IMap entries; group election simulates maxLeaders with slot keys; extension delegates and watchdog renewal share the same lock reference; and near-cache or too-short leaseTime can break ownership assumptions.",
  evidence: "kind=class; best-practice=class-diagram-style-v3; rejected-patterns=relationship-heavy-grid,card-penetrating-connector,inheritance-arrow-not-on-parent,undifferentiated-class-route-overlap",
  sourceRead: "leader-hazelcast/README.md; leader-hazelcast/src/main/kotlin/io/bluetape4k/leader/hazelcast/HazelcastLeaderElector.kt; HazelcastLeaderGroupElector.kt; HazelcastSuspendLeaderElector.kt; HazelcastSuspendLeaderGroupElector.kt; HazelcastLeaderElectorFactory.kt; HazelcastLeaderGroupElectorFactory.kt; lock/HazelcastLock.kt; lock/HazelcastSuspendLock.kt; internal/HazelcastLockExtendDelegate.kt; HazelcastSlotExtendDelegate.kt; HazelcastSuspendLockExtendDelegate.kt; HazelcastSuspendSlotExtendDelegate.kt; HazelcastBackendErrorClassifier.kt",
  classes: [
    klass("leaderContract", "LeaderElector", "<<core interface>>", ["+ runIfLeader(lockName): T?", "+ runAsyncIfLeader(lockName)", "+ state(lockName): LeaderState"], 90, 170, 330, 148, "blue"),
    klass("suspendContract", "SuspendLeaderElector", "<<core suspend interface>>", ["+ suspend runIfLeader(lockName)", "+ runIfLeaderResultSuspend(slot)", "coroutine context handle"], 510, 170, 360, 148, "purple"),
    klass("groupContract", "LeaderGroupElector", "<<core group interface>>", ["+ maxLeaders: Int", "+ runIfLeader(lockName): T?", "+ activeCount / availableSlots"], 1010, 170, 350, 148, "teal"),
    klass("suspendGroupContract", "SuspendLeaderGroupElector", "<<core suspend group>>", ["+ maxLeaders: Int", "+ suspend runIfLeader(lockName)", "+ state(lockName): LeaderGroupState"], 1430, 170, 350, 148, "purple"),

    klass("hazelcastLeader", "HazelcastLeaderElector", "<<blocking + async implementation>>", ["uses bluetape4k:leader:locks", "creates HazelcastLock", "autoExtend watchdog optional", "async release is thread-unbound"], 70, 430, 390, 170, "green"),
    klass("hazelcastSuspend", "HazelcastSuspendLeaderElector", "<<coroutine implementation>>", ["uses same single lock map", "creates HazelcastSuspendLock", "withContext(handle element)", "NonCancellable release"], 500, 430, 390, 170, "purple"),
    klass("hazelcastGroup", "HazelcastLeaderGroupElector", "<<slot group implementation>>", ["uses group lock map", "iterates lockName:slot:N", "activeCount reads slot keys", "group watchdog disabled"], 990, 430, 390, 170, "teal"),
    klass("hazelcastSuspendGroup", "HazelcastSuspend Group", "<<coroutine slot group>>", ["HazelcastSuspendLeaderGroupElector", "ensureActive while trying slots", "creates suspend slot lock", "NonCancellable slot release"], 1420, 430, 360, 190, "teal"),

    klass("factories", "Factory Entrypoints", "<<factory + extensions>>", ["HazelcastLeaderElectorFactory", "HazelcastLeaderGroupElectorFactory", "HazelcastInstance.runIfLeader*", "HazelcastInstance.suspendRunIfLeader*"], 645, 660, 550, 170, "blue"),
    klass("singleDelegates", "Single Extend Delegates", "<<renewal delegates>>", ["HazelcastLockExtendDelegate", "HazelcastSuspendLockExtendDelegate", "share lock with LeaderLockHandle", "classify backend errors"], 140, 760, 500, 170, "indigo"),
    klass("slotDelegates", "Slot Extend Delegates", "<<group renewal delegates>>", ["HazelcastSlotExtendDelegate", "HazelcastSuspendSlotExtendDelegate", "extend lockName:slot:N", "manual LockExtender path"], 1200, 760, 500, 170, "indigo"),

    klass("hazelcastLock", "HazelcastLock", "<<IMap token lock>>", ["token: Base58 fencing value", "putIfAbsent(key, token, TTL)", "remove(key, token)", "replace + setTtl renewal"], 140, 1030, 500, 190, "amber"),
    klass("hazelcastSuspendLock", "HazelcastSuspendLock", "<<suspend IMap token lock>>", ["blocking IMap wrapped in Dispatchers.IO", "delay retry loop", "token check before unlock", "same extendDetailed contract"], 1200, 1030, 500, 190, "amber"),
    klass("hazelcastMap", "Hazelcast IMap Store", "<<runtime primitive>>", ["single map: bluetape4k:leader:locks", "group map: bluetape4k:leader:group:locks", "no CP Subsystem required", "near-cache must stay disabled"], 645, 1280, 550, 190, "gray"),
    klass("hazelcastClassifier", "HazelcastBackendErrorClassifier", "<<watchdog error policy>>", ["Retryable / TargetNotMember -> transient", "WrongTarget -> transient", "HazelcastException -> non-transient", "feeds CompositeBackendErrorClassifier"], 695, 1030, 450, 170, "gray"),
  ],
  relations: [
    relation("hazelcastLeader", "leaderContract", "inherit", [{ x: 265, y: 430 }, { x: 265, y: 318 }]),
    relation("hazelcastSuspend", "suspendContract", "inherit", [{ x: 695, y: 430 }, { x: 695, y: 318 }]),
    relation("hazelcastGroup", "groupContract", "inherit", [{ x: 1185, y: 430 }, { x: 1185, y: 318 }]),
    relation("hazelcastSuspendGroup", "suspendGroupContract", "inherit", [{ x: 1600, y: 430 }, { x: 1600, y: 318 }]),

    relation("factories", "hazelcastLeader", "dependency", [{ x: 645, y: 745 }, { x: 48, y: 745 }, { x: 48, y: 530 }, { x: 70, y: 530 }], "factory create"),
    relation("factories", "hazelcastGroup", "dependency", [{ x: 1185, y: 660 }, { x: 1185, y: 600 }], "group create"),
    relation("factories", "hazelcastSuspend", "dependency", [{ x: 790, y: 660 }, { x: 790, y: 600 }], "extensions"),
    relation("factories", "hazelcastSuspendGroup", "dependency", [{ x: 1140, y: 660 }, { x: 1140, y: 640 }, { x: 1600, y: 640 }, { x: 1600, y: 620 }], "suspend ext"),

    relation("hazelcastLeader", "singleDelegates", "local", [{ x: 265, y: 600 }, { x: 265, y: 760 }], "handle delegate"),
    relation("hazelcastSuspend", "singleDelegates", "local", [{ x: 695, y: 600 }, { x: 695, y: 640 }, { x: 560, y: 640 }, { x: 560, y: 760 }], "suspend delegate"),
    relation("hazelcastGroup", "slotDelegates", "slot", [{ x: 1185, y: 600 }, { x: 1185, y: 640 }, { x: 1275, y: 640 }, { x: 1275, y: 760 }], "slot delegate"),
    relation("hazelcastSuspendGroup", "slotDelegates", "slot", [{ x: 1600, y: 620 }, { x: 1600, y: 760 }], "suspend slot"),

    relation("singleDelegates", "hazelcastLock", "local", [{ x: 330, y: 930 }, { x: 330, y: 1030 }], "sync extend"),
    relation("singleDelegates", "hazelcastSuspendLock", "local", [{ x: 640, y: 850 }, { x: 900, y: 850 }, { x: 900, y: 1000 }, { x: 1450, y: 1000 }, { x: 1450, y: 1030 }], "suspend extend"),
    relation("slotDelegates", "hazelcastLock", "slot", [{ x: 1200, y: 850 }, { x: 930, y: 850 }, { x: 930, y: 1000 }, { x: 390, y: 1000 }, { x: 390, y: 1030 }], "slot lock"),
    relation("slotDelegates", "hazelcastSuspendLock", "slot", [{ x: 1450, y: 930 }, { x: 1450, y: 1030 }], "suspend slot lock"),

    relation("hazelcastLock", "hazelcastMap", "uses", [{ x: 390, y: 1220 }, { x: 390, y: 1250 }, { x: 850, y: 1250 }, { x: 850, y: 1280 }], "putIfAbsent TTL"),
    relation("hazelcastSuspendLock", "hazelcastMap", "uses", [{ x: 1450, y: 1220 }, { x: 1450, y: 1250 }, { x: 1050, y: 1250 }, { x: 1050, y: 1280 }], "Dispatchers.IO"),
    relation("hazelcastClassifier", "singleDelegates", "event", [{ x: 920, y: 1030 }, { x: 920, y: 970 }, { x: 390, y: 970 }, { x: 390, y: 930 }]),
    relation("hazelcastClassifier", "slotDelegates", "event", [{ x: 920, y: 1030 }, { x: 920, y: 970 }, { x: 1450, y: 970 }, { x: 1450, y: 930 }]),
  ],
  legend: [
    { kind: "inherit", label: "core interface implementation" },
    { kind: "dependency", label: "factory or extension creates elector" },
    { kind: "local", label: "single-leader lock and delegate path" },
    { kind: "slot", label: "group slot-key semaphore path" },
    { kind: "uses", label: "Hazelcast IMap operation dependency" },
    { kind: "event", label: "watchdog backend error policy" },
  ],
});

renderClassDiagram({
  slug: "leader-mongodb-class-01",
  kind: "class",
  width: 1900,
  height: 2140,
  title: "MongoDB Leader Class Diagram",
  subtitle: "Public electors map core contracts to Mongo TTL lock documents, token ownership, slot rows, coroutine collections, and optional history.",
  desc: "Source-backed UML class diagram for the MongoDB leader module.",
  intent: "Help MongoDB users understand how blocking and coroutine leader electors use MongoCollection and CoroutineMongoCollection differently, where TTL lock documents and group slot documents are written, how token ownership protects unlock and renewal, why suspend group state needs a matching sync collection namespace, and how optional history plus backend error classification attach to the same lock lifecycle.",
  evidence: "kind=class; best-practice=class-diagram-style-v3; rejected-patterns=relationship-heavy-grid,card-penetrating-connector,inheritance-arrow-not-on-parent,undifferentiated-class-route-overlap",
  sourceRead: "leader-mongodb/README.md; leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderElector.kt; MongoLeaderGroupElector.kt; MongoSuspendLeaderElector.kt; MongoSuspendLeaderGroupElector.kt; MongoLeaderElectorFactory.kt; MongoLeaderGroupElectorFactory.kt; MongoLeaderElectionOptions.kt; MongoLeaderGroupElectionOptions.kt; lock/MongoLock.kt; lock/MongoSuspendLock.kt; history/MongoLeaderHistorySink.kt; history/MongoLeaderHistoryIndexer.kt; internal/MongoLockExtendDelegate.kt; MongoSlotExtendDelegate.kt; MongoSuspendLockExtendDelegate.kt; MongoSuspendSlotExtendDelegate.kt; MongoBackendErrorClassifier.kt",
  classes: [
    klass("leaderContract", "LeaderElector", "<<core interface>>", ["+ runIfLeader(lockName): T?", "+ runAsyncIfLeader(lockName)", "+ state(lockName): LeaderState"], 80, 170, 340, 148, "blue"),
    klass("suspendContract", "SuspendLeaderElector", "<<core suspend interface>>", ["+ suspend runIfLeader(lockName)", "+ runIfLeaderResultSuspend(slot)", "coroutine context handle"], 520, 170, 370, 148, "purple"),
    klass("groupContract", "LeaderGroupElector", "<<core group interface>>", ["+ maxLeaders: Int", "+ runIfLeader(lockName): T?", "+ activeCount / availableSlots"], 1010, 170, 360, 148, "teal"),
    klass("suspendGroupContract", "SuspendLeaderGroupElector", "<<core suspend group>>", ["+ maxLeaders: Int", "+ suspend runIfLeader(lockName)", "+ state(lockName): LeaderGroupState"], 1430, 170, 370, 148, "purple"),

    klass("mongoLeader", "MongoLeaderElector", "<<blocking + async implementation>>", ["sync MongoCollection<Document>", "creates MongoLock", "autoExtend watchdog optional", "history recorder optional"], 60, 430, 400, 180, "green"),
    klass("mongoSuspend", "MongoSuspendLeaderElector", "<<coroutine implementation>>", ["CoroutineMongoCollection<Document>", "creates MongoSuspendLock", "withContext(handle element)", "NonCancellable unlock"], 500, 430, 410, 180, "purple"),
    klass("mongoGroup", "MongoLeaderGroupElector", "<<slot group implementation>>", ["sync group collection", "randomized slot iteration", "slotKey lockName:slot:N", "group watchdog disabled"], 990, 430, 400, 180, "teal"),
    klass("mongoSuspendGroup", "MongoSuspend Group", "<<coroutine slot group>>", ["MongoSuspendLeaderGroupElector", "sync collection for state queries", "coroutine collection for locks", "same namespace required"], 1420, 430, 390, 200, "teal"),

    klass("factories", "Factory And Options", "<<factory + module options>>", ["MongoLeaderElectorFactory", "MongoLeaderGroupElectorFactory", "MongoLeaderElectionOptions.retryDelay", "MongoLeaderGroupElectionOptions.maxLeaders"], 650, 660, 600, 180, "blue"),
    klass("singleDelegates", "Single Extend Delegates", "<<renewal delegates>>", ["MongoLockExtendDelegate", "MongoSuspendLockExtendDelegate", "share lock with LeaderLockHandle", "classify Mongo backend errors"], 110, 800, 520, 170, "indigo"),
    klass("slotDelegates", "Slot Extend Delegates", "<<group renewal delegates>>", ["MongoSlotExtendDelegate", "MongoSuspendSlotExtendDelegate", "extend lockName:slot:N", "manual LockExtender path"], 1270, 800, 520, 170, "indigo"),

    klass("mongoLock", "MongoLock", "<<findOneAndUpdate lock>>", ["token: Base58 fencing value", "expired filter + upsert AFTER", "E11000 means contended", "delete or minLease update"], 110, 1040, 520, 190, "amber"),
    klass("mongoSuspendLock", "MongoSuspendLock", "<<coroutine Mongo lock>>", ["coroutine driver retry loop", "ensureActive while acquiring", "token check before unlock", "same extendDetailed contract"], 1270, 1040, 520, 190, "amber"),
    klass("mongoCollections", "Mongo Lock Collections", "<<TTL document store>>", ["bluetape4k_leader_locks", "bluetape4k_leader_group_locks", "fields: _id, token, expireAt", "TTL index expireAfterSeconds=0"], 665, 1290, 570, 190, "gray"),
    klass("history", "Mongo History Sink", "<<optional audit store>>", ["MongoLeaderHistorySink", "collection bluetape4k_leader_history", "records ACQUIRED/COMPLETED/FAILED", "indexer adds TTL retention index"], 80, 1320, 520, 190, "indigo"),
    klass("classifier", "MongoBackendErrorClassifier", "<<watchdog error policy>>", ["timeout / socket -> transient", "not primary / recovering -> transient", "auth / write concern -> non-transient", "feeds CompositeBackendErrorClassifier"], 690, 1035, 520, 190, "gray"),
    klass("dualCollections", "Suspend Group Dual Collections", "<<namespace invariant>>", ["sync MongoCollection: activeCount/state", "CoroutineMongoCollection: acquire/release", "collection namespace must match", "TTL monitor can lag up to 60 seconds"], 665, 1540, 570, 190, "pink"),
  ],
  relations: [
    relation("mongoLeader", "leaderContract", "inherit", [{ x: 260, y: 430 }, { x: 260, y: 318 }]),
    relation("mongoSuspend", "suspendContract", "inherit", [{ x: 705, y: 430 }, { x: 705, y: 318 }]),
    relation("mongoGroup", "groupContract", "inherit", [{ x: 1190, y: 430 }, { x: 1190, y: 318 }]),
    relation("mongoSuspendGroup", "suspendGroupContract", "inherit", [{ x: 1615, y: 430 }, { x: 1615, y: 318 }]),

    relation("factories", "mongoLeader", "dependency", [{ x: 650, y: 750 }, { x: 40, y: 750 }, { x: 40, y: 520 }, { x: 60, y: 520 }]),
    relation("factories", "mongoSuspend", "dependency", [{ x: 800, y: 660 }, { x: 800, y: 610 }]),
    relation("factories", "mongoGroup", "dependency", [{ x: 1190, y: 660 }, { x: 1190, y: 610 }]),
    relation("factories", "mongoSuspendGroup", "dependency", [{ x: 1250, y: 750 }, { x: 1840, y: 750 }, { x: 1840, y: 530 }, { x: 1810, y: 530 }]),

    relation("mongoLeader", "singleDelegates", "local", [{ x: 260, y: 610 }, { x: 260, y: 800 }], "handle delegate"),
    relation("mongoSuspend", "singleDelegates", "local", [{ x: 705, y: 610 }, { x: 705, y: 635 }, { x: 470, y: 635 }, { x: 470, y: 800 }], "suspend delegate"),
    relation("mongoGroup", "slotDelegates", "slot", [{ x: 1190, y: 610 }, { x: 1190, y: 650 }, { x: 1430, y: 650 }, { x: 1430, y: 800 }], "slot delegate"),
    relation("mongoSuspendGroup", "slotDelegates", "slot", [{ x: 1615, y: 630 }, { x: 1615, y: 800 }], "suspend slot"),

    relation("singleDelegates", "mongoLock", "local", [{ x: 370, y: 970 }, { x: 370, y: 1040 }], "sync extend"),
    relation("singleDelegates", "mongoSuspendLock", "local", [{ x: 630, y: 885 }, { x: 950, y: 885 }, { x: 950, y: 1000 }, { x: 1530, y: 1000 }, { x: 1530, y: 1040 }]),
    relation("slotDelegates", "mongoLock", "slot", [{ x: 1270, y: 885 }, { x: 950, y: 885 }, { x: 950, y: 1000 }, { x: 370, y: 1000 }, { x: 370, y: 1040 }]),
    relation("slotDelegates", "mongoSuspendLock", "slot", [{ x: 1530, y: 970 }, { x: 1530, y: 1040 }], "suspend slot"),

    relation("mongoLock", "mongoCollections", "uses", [{ x: 370, y: 1230 }, { x: 370, y: 1260 }, { x: 860, y: 1260 }, { x: 860, y: 1290 }], "findOneAndUpdate"),
    relation("mongoSuspendLock", "mongoCollections", "uses", [{ x: 1530, y: 1230 }, { x: 1530, y: 1260 }, { x: 1040, y: 1260 }, { x: 1040, y: 1290 }], "coroutine driver"),
    relation("mongoSuspendGroup", "dualCollections", "dependency", [{ x: 1810, y: 570 }, { x: 1860, y: 570 }, { x: 1860, y: 1635 }, { x: 1235, y: 1635 }]),
    relation("dualCollections", "mongoCollections", "uses", [{ x: 950, y: 1540 }, { x: 950, y: 1480 }]),

    relation("mongoLeader", "history", "event", [{ x: 60, y: 550 }, { x: 28, y: 550 }, { x: 28, y: 1415 }, { x: 80, y: 1415 }]),
    relation("mongoGroup", "history", "event", [{ x: 1190, y: 430 }, { x: 1190, y: 390 }, { x: 28, y: 390 }, { x: 28, y: 1415 }, { x: 80, y: 1415 }]),
    relation("classifier", "singleDelegates", "event", [{ x: 950, y: 1035 }, { x: 950, y: 990 }, { x: 370, y: 990 }, { x: 370, y: 970 }]),
    relation("classifier", "slotDelegates", "event", [{ x: 950, y: 1035 }, { x: 950, y: 990 }, { x: 1530, y: 990 }, { x: 1530, y: 970 }]),
  ],
  legend: [
    { kind: "inherit", label: "core interface implementation" },
    { kind: "dependency", label: "factory, options, or namespace invariant" },
    { kind: "local", label: "single-leader token lock path" },
    { kind: "slot", label: "group slot-document semaphore path" },
    { kind: "uses", label: "Mongo collection read/write dependency" },
    { kind: "event", label: "history or watchdog error-policy path" },
  ],
});

renderClassDiagram({
  slug: "leader-redis-lettuce-class-01",
  kind: "class",
  width: 1900,
  height: 2140,
  title: "Redis Lettuce Leader Class Diagram",
  subtitle: "Public electors map core contracts to Redis SET NX PX locks, ZSET slot-token groups, Lua scripts, and Lettuce error policy.",
  desc: "Source-backed UML class diagram for the Redis Lettuce leader module.",
  intent: "Help Redis Lettuce users understand how the blocking, async, and coroutine electors split between single-key token locks and the group ZSET slot-token primitive, why group election does not use the removed semaphore classes, how RedisScriptRunner and server-side TIME Lua scripts provide atomic release/status/extend behavior, where audit leader IDs are stored, and how watchdog extension delegates classify Lettuce backend failures.",
  evidence: "kind=class; best-practice=class-diagram-style-v3; rejected-patterns=relationship-heavy-grid,card-penetrating-connector,inheritance-arrow-not-on-parent,undifferentiated-class-route-overlap",
  sourceRead: "leader-redis-lettuce/README.md; leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceLeaderElector.kt; LettuceLeaderGroupElector.kt; LettuceSuspendLeaderElector.kt; LettuceSuspendLeaderGroupElector.kt; LettuceLeaderElectorFactory.kt; LettuceLeaderGroupElectorFactory.kt; LettuceSuspendLeaderElectorFactory.kt; LettuceSuspendLeaderGroupElectorFactory.kt; lock/LettuceLock.kt; lock/LettuceSuspendLock.kt; semaphore/LettuceSlotTokenGroup.kt; script/RedisScript.kt; internal/LettuceLockExtendDelegate.kt; LettuceSlotExtendDelegate.kt; LettuceSuspendLockExtendDelegate.kt; LettuceSuspendSlotExtendDelegate.kt; LettuceBackendErrorClassifier.kt",
  classes: [
    klass("leaderContract", "LeaderElector", "<<core interface>>", ["+ runIfLeader(lockName): T?", "+ runAsyncIfLeader(lockName)", "+ state(lockName): LeaderState"], 80, 170, 340, 148, "blue"),
    klass("suspendContract", "SuspendLeaderElector", "<<core suspend interface>>", ["+ suspend runIfLeader(lockName)", "+ runIfLeaderResultSuspend(slot)", "coroutine context handle"], 520, 170, 370, 148, "purple"),
    klass("groupContract", "LeaderGroupElector", "<<core group interface>>", ["+ maxLeaders: Int", "+ runIfLeader(lockName): T?", "+ activeCount / availableSlots"], 1010, 170, 360, 148, "teal"),
    klass("suspendGroupContract", "SuspendLeaderGroupElector", "<<core suspend group>>", ["+ maxLeaders: Int", "+ suspend runIfLeader(lockName)", "+ state(lockName): LeaderGroupState"], 1430, 170, 370, 148, "purple"),

    klass("lettuceLeader", "LettuceLeaderElector", "<<blocking + async implementation>>", ["StatefulRedisConnection<String,String>", "creates LettuceLock", "autoExtend watchdog optional", "optional history recorder"], 60, 430, 400, 180, "green"),
    klass("lettuceSuspend", "LettuceSuspendLeaderElector", "<<coroutine implementation>>", ["uses Lettuce async commands", "creates LettuceSuspendLock", "withContext(handle element)", "NonCancellable unlock"], 500, 430, 410, 180, "purple"),
    klass("lettuceGroup", "LettuceLeaderGroupElector", "<<slot-token group implementation>>", ["reuses slotGroups map", "creates LettuceSlotTokenGroup", "token becomes slotId", "group watchdog disabled"], 990, 430, 400, 180, "teal"),
    klass("lettuceSuspendGroup", "LettuceSuspend Group", "<<coroutine slot group>>", ["LettuceSuspendLeaderGroupElector", "uses same SlotTokenGroup primitive", "tryAcquireSuspending", "NonCancellable slot release"], 1420, 430, 390, 200, "teal"),

    klass("factories", "Factory Entrypoints", "<<factory + extensions>>", ["LettuceLeaderElectorFactory", "LettuceLeaderGroupElectorFactory", "LettuceSuspendLeaderElectorFactory", "StatefulRedisConnection extensions"], 650, 660, 600, 180, "blue"),
    klass("singleDelegates", "Single Extend Delegates", "<<renewal delegates>>", ["LettuceLockExtendDelegate", "LettuceSuspendLockExtendDelegate", "share lock with LeaderLockHandle", "R2 watchdog skip deadline"], 110, 800, 520, 170, "indigo"),
    klass("slotDelegates", "Slot Extend Delegates", "<<group renewal delegates>>", ["LettuceSlotExtendDelegate", "LettuceSuspendSlotExtendDelegate", "extend slot token via server TIME", "manual LockExtender path"], 1270, 800, 520, 170, "indigo"),

    klass("lettuceLock", "LettuceLock", "<<Redis SET NX PX lock>>", ["token: Base58 fencing value", "SET key token NX PX lease", "Lua token unlock / PEXPIRE", "async retry uses same token"], 90, 1040, 500, 190, "amber"),
    klass("slotGroup", "LettuceSlotTokenGroup", "<<ZSET slot-token primitive>>", ["slotKey: lg:{lockName}", "member token, score expiryAtMs", "metaKey stores LeaderSlot leaderId", "no legacy semaphore dependency"], 665, 1030, 570, 220, "amber"),
    klass("lettuceSuspendLock", "LettuceSuspendLock", "<<coroutine Redis lock>>", ["asyncCommands.set NX PX", "delay retry + ensureActive", "Lua token unlock / PEXPIRE", "suspend native extendDetailed"], 1310, 1040, 500, 190, "amber"),

    klass("scriptRunner", "RedisScriptRunner", "<<Lua execution helper>>", ["RedisScript.sha1", "EVALSHA first", "NOSCRIPT falls back to EVAL", "sync / async / suspend entrypoints"], 665, 1330, 570, 180, "purple"),
    klass("redisKeys", "Redis Runtime Keys", "<<Redis storage contract>>", ["single lock key: SET NX PX", "group ZSET: lg:{lockName}", "group hash: lg:{lockName}:meta", "ACQUIRE evicts expired scores"], 665, 1580, 570, 200, "gray"),
    klass("classifier", "LettuceBackendErrorClassifier", "<<watchdog error policy>>", ["RedisCommandTimeout -> transient", "RedisConnection -> transient", "RedisCommandExecution -> non-transient", "other errors delegated"], 690, 845, 520, 155, "gray"),
  ],
  relations: [
    relation("lettuceLeader", "leaderContract", "inherit", [{ x: 260, y: 430 }, { x: 260, y: 318 }]),
    relation("lettuceSuspend", "suspendContract", "inherit", [{ x: 705, y: 430 }, { x: 705, y: 318 }]),
    relation("lettuceGroup", "groupContract", "inherit", [{ x: 1190, y: 430 }, { x: 1190, y: 318 }]),
    relation("lettuceSuspendGroup", "suspendGroupContract", "inherit", [{ x: 1615, y: 430 }, { x: 1615, y: 318 }]),

    relation("factories", "lettuceLeader", "dependency", [{ x: 650, y: 750 }, { x: 40, y: 750 }, { x: 40, y: 520 }, { x: 60, y: 520 }]),
    relation("factories", "lettuceSuspend", "dependency", [{ x: 800, y: 660 }, { x: 800, y: 610 }]),
    relation("factories", "lettuceGroup", "dependency", [{ x: 1190, y: 660 }, { x: 1190, y: 610 }]),
    relation("factories", "lettuceSuspendGroup", "dependency", [{ x: 1250, y: 750 }, { x: 1840, y: 750 }, { x: 1840, y: 530 }, { x: 1810, y: 530 }]),

    relation("lettuceLeader", "singleDelegates", "local", [{ x: 260, y: 610 }, { x: 260, y: 800 }], "handle delegate"),
    relation("lettuceSuspend", "singleDelegates", "local", [{ x: 705, y: 610 }, { x: 705, y: 635 }, { x: 470, y: 635 }, { x: 470, y: 800 }], "suspend delegate"),
    relation("lettuceGroup", "slotDelegates", "slot", [{ x: 1190, y: 610 }, { x: 1190, y: 650 }, { x: 1430, y: 650 }, { x: 1430, y: 800 }], "slot delegate"),
    relation("lettuceSuspendGroup", "slotDelegates", "slot", [{ x: 1615, y: 630 }, { x: 1615, y: 800 }], "suspend slot"),

    relation("singleDelegates", "lettuceLock", "local", [{ x: 350, y: 970 }, { x: 350, y: 1040 }], "sync extend"),
    relation("singleDelegates", "lettuceSuspendLock", "local", [{ x: 630, y: 900 }, { x: 640, y: 900 }, { x: 640, y: 1015 }, { x: 1560, y: 1015 }, { x: 1560, y: 1040 }]),
    relation("slotDelegates", "slotGroup", "slot", [{ x: 1530, y: 970 }, { x: 1530, y: 1015 }, { x: 1100, y: 1015 }, { x: 1100, y: 1030 }]),
    relation("lettuceGroup", "slotGroup", "slot", [{ x: 1190, y: 430 }, { x: 1190, y: 390 }, { x: 1850, y: 390 }, { x: 1850, y: 1015 }, { x: 1120, y: 1015 }, { x: 1120, y: 1030 }]),
    relation("lettuceSuspendGroup", "slotGroup", "slot", [{ x: 1810, y: 530 }, { x: 1850, y: 530 }, { x: 1850, y: 1015 }, { x: 1180, y: 1015 }, { x: 1180, y: 1030 }]),

    relation("lettuceLock", "scriptRunner", "uses", [{ x: 340, y: 1230 }, { x: 340, y: 1280 }, { x: 850, y: 1280 }, { x: 850, y: 1330 }], "unlock / extend"),
    relation("lettuceSuspendLock", "scriptRunner", "uses", [{ x: 1560, y: 1230 }, { x: 1560, y: 1280 }, { x: 1050, y: 1280 }, { x: 1050, y: 1330 }], "suspend Lua"),
    relation("slotGroup", "scriptRunner", "uses", [{ x: 950, y: 1250 }, { x: 950, y: 1330 }], "acquire release status"),
    relation("scriptRunner", "redisKeys", "uses", [{ x: 950, y: 1510 }, { x: 950, y: 1580 }], "EVALSHA / EVAL"),
    relation("lettuceLock", "redisKeys", "dependency", [{ x: 590, y: 1135 }, { x: 632, y: 1135 }, { x: 632, y: 1680 }, { x: 665, y: 1680 }]),
    relation("slotGroup", "redisKeys", "slot", [{ x: 1050, y: 1250 }, { x: 1050, y: 1300 }, { x: 1260, y: 1300 }, { x: 1260, y: 1680 }, { x: 1235, y: 1680 }], "ZSET + meta hash"),

    relation("classifier", "singleDelegates", "event", [{ x: 690, y: 885 }, { x: 630, y: 885 }]),
    relation("classifier", "slotDelegates", "event", [{ x: 1210, y: 885 }, { x: 1270, y: 885 }]),
  ],
  legend: [
    { kind: "inherit", label: "core interface implementation" },
    { kind: "dependency", label: "factory, Redis key, or direct command path" },
    { kind: "local", label: "single-leader token lock path" },
    { kind: "slot", label: "group ZSET slot-token path" },
    { kind: "uses", label: "Redis Lua script execution dependency" },
    { kind: "event", label: "watchdog backend error-policy path" },
  ],
});

renderClassDiagram({
  slug: "leader-redis-redisson-class-01",
  kind: "class",
  width: 1900,
  height: 2140,
  title: "Redis Redisson Leader Class Diagram",
  subtitle: "Public electors map core contracts to explicit-lease RLock ownership, expirable semaphore permits, audit maps, and Redisson error policy.",
  desc: "Source-backed UML class diagram for the Redis Redisson leader module.",
  intent: "Help Redis Redisson users understand how single-leader electors deliberately disable Redisson's built-in watchdog by always passing an explicit leaseTime, how coroutine locks replace thread identity with a PID-seeded Snowflake owner id, how group electors initialize and use RPermitExpirableSemaphore permits under lg:{lockName}, how audit leader IDs are stored in RMap, and how shared extension delegates feed LeaderLeaseAutoExtender and backend error classification.",
  evidence: "kind=class; best-practice=class-diagram-style-v3; rejected-patterns=relationship-heavy-grid,card-penetrating-connector,inheritance-arrow-not-on-parent,undifferentiated-class-route-overlap",
  sourceRead: "leader-redis-redisson/README.md; leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redisson/RedissonLeaderElector.kt; RedissonLeaderGroupElector.kt; RedissonSuspendLeaderElector.kt; RedissonSuspendLeaderGroupElector.kt; RedissonLeaderElectorFactory.kt; RedissonLeaderGroupElectorFactory.kt; RedissonSuspendLeaderElectorFactory.kt; RedissonSuspendLeaderGroupElectorFactory.kt; internal/RedissonLockExtendDelegate.kt; RedissonSuspendLockExtendDelegate.kt; RedissonSemaphoreExtendDelegate.kt; RedissonSuspendSemaphoreExtendDelegate.kt; RedissonBackendErrorClassifier.kt",
  classes: [
    klass("leaderContract", "LeaderElector", "<<core interface>>", ["+ runIfLeader(lockName): T?", "+ runAsyncIfLeader(lockName)", "+ state(lockName): LeaderState"], 80, 170, 340, 148, "blue"),
    klass("suspendContract", "SuspendLeaderElector", "<<core suspend interface>>", ["+ suspend runIfLeader(lockName)", "+ runIfLeaderResultSuspend(slot)", "coroutine context handle"], 520, 170, 370, 148, "purple"),
    klass("groupContract", "LeaderGroupElector", "<<core group interface>>", ["+ maxLeaders: Int", "+ runIfLeader(lockName): T?", "+ activeCount / availableSlots"], 1010, 170, 360, 148, "teal"),
    klass("suspendGroupContract", "SuspendLeaderGroupElector", "<<core suspend group>>", ["+ maxLeaders: Int", "+ suspend runIfLeader(lockName)", "+ state(lockName): LeaderGroupState"], 1430, 170, 370, 148, "purple"),

    klass("redissonLeader", "RedissonLeaderElector", "<<blocking + async implementation>>", ["RedissonClient.getLock(lockName)", "tryLock(wait, explicit lease)", "built-in watchdog disabled", "LeaderLeaseAutoExtender owns renewals"], 60, 430, 400, 190, "green"),
    klass("redissonSuspend", "RedissonSuspendLeaderElector", "<<coroutine implementation>>", ["uses RLock async API", "PID-seeded owner id", "withContext(handle element)", "NonCancellable unlock"], 500, 430, 410, 180, "purple"),
    klass("redissonGroup", "RedissonLeaderGroupElector", "<<expirable permit group>>", ["getPermitExpirableSemaphore", "trySetPermits(maxLeaders)", "permitId becomes slotId", "group watchdog disabled"], 990, 430, 400, 180, "teal"),
    klass("redissonSuspendGroup", "RedissonSuspend Group", "<<coroutine permit group>>", ["RedissonSuspendLeaderGroupElector", "trySetPermitsAsync", "tryAcquireAsync permitId", "NonCancellable release/extend"], 1420, 430, 390, 200, "teal"),

    klass("factories", "Factory Entrypoints", "<<factory + extensions>>", ["RedissonLeaderElectorFactory", "RedissonLeaderGroupElectorFactory", "RedissonSuspendLeaderElectorFactory", "RedissonClient runIfLeader* extensions"], 650, 660, 600, 180, "blue"),
    klass("singleDelegates", "Single Extend Delegates", "<<RLock renewal delegates>>", ["RedissonLockExtendDelegate", "RedissonSuspendLockExtendDelegate", "RKeys.expire renews lock key", "WrongThread on owner mismatch"], 110, 800, 520, 170, "indigo"),
    klass("permitDelegates", "Permit Extend Delegates", "<<semaphore renewal delegates>>", ["RedissonSemaphoreExtendDelegate", "RedissonSuspendSemaphoreExtendDelegate", "updateLeaseTime by permitId", "active flag tracks failed extend"], 1270, 800, 520, 170, "indigo"),

    klass("rlock", "Redisson RLock Path", "<<single Redis lock primitive>>", ["RLock.tryLock(wait, lease, id)", "RLock.unlock / unlockAsync(id)", "RKeys.expire for minLease/extend", "built-in watchdog never used"], 90, 1040, 500, 190, "amber"),
    klass("semaphore", "RPermitExpirableSemaphore", "<<group permit primitive>>", ["key: lg:{lockName}", "trySetPermits idempotent", "tryAcquire returns permitId", "updateLeaseTime delegates minLease"], 665, 1030, 570, 220, "amber"),
    klass("ownerId", "Coroutine Owner ID", "<<RLock owner identity>>", ["timestamp | pid%(2^10) | seq", "generated per runIfLeader call", "prevents coroutine false reentry", "zero Redis round-trip"], 1310, 1040, 500, 190, "pink"),

    klass("runtime", "Redisson Runtime Objects", "<<Redis-backed objects>>", ["single RLock key: lockName", "group semaphore: lg:{lockName}", "audit RMap: lg:{lockName}:audit", "permit TTL gives crash recovery"], 665, 1340, 570, 200, "gray"),
    klass("audit", "LeaderSlot Audit Map", "<<traceability side channel>>", ["RMap<String,String>", "fastPut permitId -> leaderId", "expire lease + 5s padding", "fastRemove on release"], 90, 1360, 520, 180, "indigo"),
    klass("classifier", "RedissonBackendErrorClassifier", "<<watchdog error policy>>", ["RedisTimeout -> transient", "RedisConnection -> transient", "other RedisException -> non-transient", "other errors delegated"], 690, 845, 520, 170, "gray"),
  ],
  relations: [
    relation("redissonLeader", "leaderContract", "inherit", [{ x: 260, y: 430 }, { x: 260, y: 318 }]),
    relation("redissonSuspend", "suspendContract", "inherit", [{ x: 705, y: 430 }, { x: 705, y: 318 }]),
    relation("redissonGroup", "groupContract", "inherit", [{ x: 1190, y: 430 }, { x: 1190, y: 318 }]),
    relation("redissonSuspendGroup", "suspendGroupContract", "inherit", [{ x: 1615, y: 430 }, { x: 1615, y: 318 }]),

    relation("factories", "redissonLeader", "dependency", [{ x: 650, y: 750 }, { x: 40, y: 750 }, { x: 40, y: 520 }, { x: 60, y: 520 }]),
    relation("factories", "redissonSuspend", "dependency", [{ x: 800, y: 660 }, { x: 800, y: 610 }]),
    relation("factories", "redissonGroup", "dependency", [{ x: 1190, y: 660 }, { x: 1190, y: 610 }]),
    relation("factories", "redissonSuspendGroup", "dependency", [{ x: 1250, y: 750 }, { x: 1840, y: 750 }, { x: 1840, y: 530 }, { x: 1810, y: 530 }]),

    relation("redissonLeader", "singleDelegates", "local", [{ x: 260, y: 620 }, { x: 260, y: 800 }], "handle delegate"),
    relation("redissonSuspend", "singleDelegates", "local", [{ x: 705, y: 610 }, { x: 705, y: 635 }, { x: 470, y: 635 }, { x: 470, y: 800 }], "suspend delegate"),
    relation("redissonGroup", "permitDelegates", "slot", [{ x: 1190, y: 610 }, { x: 1190, y: 650 }, { x: 1430, y: 650 }, { x: 1430, y: 800 }], "permit delegate"),
    relation("redissonSuspendGroup", "permitDelegates", "slot", [{ x: 1615, y: 630 }, { x: 1615, y: 800 }], "suspend permit"),

    relation("singleDelegates", "rlock", "local", [{ x: 350, y: 970 }, { x: 350, y: 1040 }], "RKeys.expire"),
    relation("redissonSuspend", "ownerId", "dependency", [{ x: 705, y: 610 }, { x: 705, y: 635 }, { x: 1260, y: 635 }, { x: 1260, y: 1000 }, { x: 1560, y: 1000 }, { x: 1560, y: 1040 }], "owner id"),
    relation("ownerId", "rlock", "dependency", [{ x: 1560, y: 1230 }, { x: 1560, y: 1265 }, { x: 340, y: 1265 }, { x: 340, y: 1230 }], "RLock owner"),
    relation("permitDelegates", "semaphore", "slot", [{ x: 1530, y: 970 }, { x: 1530, y: 1025 }, { x: 1100, y: 1025 }, { x: 1100, y: 1030 }]),
    relation("redissonGroup", "semaphore", "slot", [{ x: 1190, y: 430 }, { x: 1190, y: 390 }, { x: 1850, y: 390 }, { x: 1850, y: 1025 }, { x: 1120, y: 1025 }, { x: 1120, y: 1030 }]),
    relation("redissonSuspendGroup", "semaphore", "slot", [{ x: 1810, y: 530 }, { x: 1850, y: 530 }, { x: 1850, y: 1025 }, { x: 1180, y: 1025 }, { x: 1180, y: 1030 }]),

    relation("rlock", "runtime", "uses", [{ x: 340, y: 1230 }, { x: 340, y: 1280 }, { x: 850, y: 1280 }, { x: 850, y: 1340 }], "RLock key"),
    relation("semaphore", "runtime", "uses", [{ x: 950, y: 1250 }, { x: 950, y: 1340 }], "permit key"),
    relation("ownerId", "runtime", "dependency", [{ x: 1560, y: 1230 }, { x: 1560, y: 1280 }, { x: 1050, y: 1280 }, { x: 1050, y: 1340 }], "owner id stored"),
    relation("semaphore", "audit", "event", [{ x: 665, y: 1140 }, { x: 632, y: 1140 }, { x: 632, y: 1450 }, { x: 610, y: 1450 }], "audit RMap"),
    relation("audit", "runtime", "uses", [{ x: 610, y: 1450 }, { x: 665, y: 1450 }]),

    relation("classifier", "singleDelegates", "event", [{ x: 690, y: 885 }, { x: 630, y: 885 }]),
    relation("classifier", "permitDelegates", "event", [{ x: 1210, y: 885 }, { x: 1270, y: 885 }]),
  ],
  legend: [
    { kind: "inherit", label: "core interface implementation" },
    { kind: "dependency", label: "factory or Redisson owner identity dependency" },
    { kind: "local", label: "single-leader RLock path" },
    { kind: "slot", label: "group expirable-permit path" },
    { kind: "uses", label: "Redisson runtime object dependency" },
    { kind: "event", label: "audit or watchdog error-policy path" },
  ],
});

renderClassDiagram({
  slug: "leader-zookeeper-class-01",
  kind: "class",
  width: 1900,
  height: 2140,
  title: "ZooKeeper Leader Class Diagram",
  subtitle: "Public electors map core contracts to Curator mutex recipes, semaphore leases, session-expiry semantics, and owner-thread coroutine cleanup.",
  desc: "Source-backed UML class diagram for the ZooKeeper leader module.",
  intent: "Help ZooKeeper users understand how blocking, async, and coroutine electors map to Apache Curator recipes, why single-leader election uses a mutex recipe while group election uses semaphore leases, why leaseTime is accepted only for API compatibility rather than ZooKeeper TTL, how the suspend single-leader path keeps acquire and release on the same owner thread, and why extend delegates are passthrough liveness checks with LeaderLeaseAutoExtender disabled.",
  evidence: "kind=class; best-practice=class-diagram-style-v3; rejected-patterns=relationship-heavy-grid,card-penetrating-connector,inheritance-arrow-not-on-parent,undifferentiated-class-route-overlap",
  sourceRead: "leader-zookeeper/README.md; leader-zookeeper/src/main/kotlin/io/bluetape4k/leader/zookeeper/ZooKeeperLeaderElector.kt; ZooKeeperLeaderGroupElector.kt; ZooKeeperSuspendLeaderElector.kt; ZooKeeperSuspendLeaderGroupElector.kt; ZooKeeperLeaderElectorFactory.kt; ZooKeeperLeaderGroupElectorFactory.kt; ZooKeeperSuspendLeaderElectorFactory.kt; ZooKeeperSuspendLeaderGroupElectorFactory.kt; ZooKeeperPaths.kt; internal/ZooKeeperLockExtendDelegate.kt; ZooKeeperSuspendLockExtendDelegate.kt; ZooKeeperSlotExtendDelegate.kt; ZooKeeperSuspendSlotExtendDelegate.kt; ZooKeeperBackendErrorClassifier.kt",
  classes: [
    klass("leaderContract", "LeaderElector", "<<core interface>>", ["+ runIfLeader(lockName): T?", "+ runAsyncIfLeader(lockName)", "+ state(lockName): LeaderState"], 80, 170, 340, 148, "blue"),
    klass("suspendContract", "SuspendLeaderElector", "<<core suspend interface>>", ["+ suspend runIfLeader(lockName)", "+ runIfLeaderResultSuspend(slot)", "coroutine context handle"], 520, 170, 370, 148, "purple"),
    klass("groupContract", "LeaderGroupElector", "<<core group interface>>", ["+ maxLeaders: Int", "+ runIfLeader(lockName): T?", "+ activeCount / availableSlots"], 1010, 170, 360, 148, "teal"),
    klass("suspendGroupContract", "SuspendLeaderGroupElector", "<<core suspend group>>", ["+ maxLeaders: Int", "+ suspend runIfLeader(lockName)", "+ state(lockName): LeaderGroupState"], 1430, 170, 370, 148, "purple"),

    klass("zkLeader", "ZooKeeperLeaderElector", "<<blocking + async implementation>>", ["CuratorFramework client", "mutex recipe per lock path", "acquire(waitTimeMs)", "watchdog disabled by design"], 60, 430, 400, 190, "green"),
    klass("zkSuspend", "ZooKeeperSuspendLeaderElector", "<<coroutine implementation>>", ["single-thread owner dispatcher", "blocking acquire/release bridge", "NonCancellable cleanup", "closes dispatcher after run"], 500, 430, 410, 190, "purple"),
    klass("zkGroup", "ZooKeeperLeaderGroupElector", "<<semaphore group implementation>>", ["Curator semaphore recipe", "acquire Lease within waitTime", "participantNodes activeCount", "lease.nodeName becomes slotId"], 990, 430, 400, 190, "teal"),
    klass("zkSuspendGroup", "ZooKeeperSuspend Group", "<<coroutine semaphore group>>", ["ZooKeeperSuspendLeaderGroupElector", "Dispatchers.IO acquire", "NonCancellable lease.close", "markReleased before close"], 1420, 430, 390, 200, "teal"),

    klass("factories", "Factory Entrypoints", "<<factory + extensions>>", ["ZooKeeperLeaderElectorFactory", "ZooKeeperLeaderGroupElectorFactory", "ZooKeeperSuspendLeaderElectorFactory", "CuratorFramework runIfLeader* extensions"], 650, 660, 600, 180, "blue"),
    klass("singleDelegates", "Single Extend Delegates", "<<mutex liveness delegates>>", ["ZooKeeperLockExtendDelegate", "ZooKeeperSuspendLockExtendDelegate", "isAcquiredInThisProcess check", "returns Extended(Instant.MAX)"], 110, 800, 520, 170, "indigo"),
    klass("slotDelegates", "Slot Extend Delegates", "<<lease state delegates>>", ["ZooKeeperSlotExtendDelegate", "ZooKeeperSuspendSlotExtendDelegate", "AtomicBoolean released flag", "markReleased -> NotHeld"], 1270, 800, 520, 170, "indigo"),

    klass("mutex", "Curator Mutex Recipe", "<<single lock primitive>>", ["path from ZooKeeperPaths", "acquire(wait, MILLISECONDS)", "release in finally block", "ephemeral recipe nodes"], 90, 1040, 500, 190, "amber"),
    klass("semaphore", "Curator Semaphore Recipe", "<<group permit primitive>>", ["path from ZooKeeperPaths", "maxLeaders permits", "Lease nodeName used as slotId", "Lease.close releases permit"], 665, 1030, 570, 220, "amber"),
    klass("ownerThread", "Call-Scoped Owner Thread", "<<Curator thread ownership>>", ["new single-thread dispatcher", "daemon zookeeper-suspend-leader-*", "acquire/release same owner thread", "dispatcher closed after cleanup"], 1310, 1040, 500, 190, "pink"),

    klass("paths", "ZooKeeperPaths", "<<base path builder>>", ["default /leader-election", "default /leader-group-election", "trims trailing slash", "root base becomes /{lockName}"], 90, 1360, 520, 180, "gray"),
    klass("runtime", "ZooKeeper Runtime Semantics", "<<session-backed coordination>>", ["ZooKeeper session owns liveness", "session expiry removes recipe nodes", "leaseTime is not ZooKeeper TTL", "autoExtend warning but disabled"], 665, 1340, 570, 200, "gray"),
    klass("classifier", "ZooKeeperBackendErrorClassifier", "<<extender error policy>>", ["ConnectionLoss -> transient", "OperationTimeout -> transient", "SessionExpired -> non-transient", "SessionMoved -> non-transient"], 690, 845, 520, 170, "gray"),
  ],
  relations: [
    relation("zkLeader", "leaderContract", "inherit", [{ x: 260, y: 430 }, { x: 260, y: 318 }]),
    relation("zkSuspend", "suspendContract", "inherit", [{ x: 705, y: 430 }, { x: 705, y: 318 }]),
    relation("zkGroup", "groupContract", "inherit", [{ x: 1190, y: 430 }, { x: 1190, y: 318 }]),
    relation("zkSuspendGroup", "suspendGroupContract", "inherit", [{ x: 1615, y: 430 }, { x: 1615, y: 318 }]),

    relation("factories", "zkLeader", "dependency", [{ x: 650, y: 750 }, { x: 40, y: 750 }, { x: 40, y: 520 }, { x: 60, y: 520 }]),
    relation("factories", "zkSuspend", "dependency", [{ x: 800, y: 660 }, { x: 800, y: 620 }]),
    relation("factories", "zkGroup", "dependency", [{ x: 1190, y: 660 }, { x: 1190, y: 620 }]),
    relation("factories", "zkSuspendGroup", "dependency", [{ x: 1250, y: 750 }, { x: 1840, y: 750 }, { x: 1840, y: 530 }, { x: 1810, y: 530 }]),

    relation("zkLeader", "singleDelegates", "local", [{ x: 260, y: 620 }, { x: 260, y: 800 }], "handle delegate"),
    relation("zkSuspend", "singleDelegates", "local", [{ x: 705, y: 620 }, { x: 705, y: 635 }, { x: 470, y: 635 }, { x: 470, y: 800 }], "suspend delegate"),
    relation("zkGroup", "slotDelegates", "slot", [{ x: 1190, y: 620 }, { x: 1190, y: 650 }, { x: 1430, y: 650 }, { x: 1430, y: 800 }], "lease delegate"),
    relation("zkSuspendGroup", "slotDelegates", "slot", [{ x: 1615, y: 630 }, { x: 1615, y: 800 }], "suspend lease"),

    relation("singleDelegates", "mutex", "local", [{ x: 350, y: 970 }, { x: 350, y: 1040 }], "liveness check"),
    relation("slotDelegates", "semaphore", "slot", [{ x: 1530, y: 970 }, { x: 1530, y: 1025 }, { x: 1100, y: 1025 }, { x: 1100, y: 1030 }]),
    relation("zkLeader", "mutex", "local", [{ x: 260, y: 430 }, { x: 260, y: 390 }, { x: 40, y: 390 }, { x: 40, y: 1135 }, { x: 90, y: 1135 }]),
    relation("zkGroup", "semaphore", "slot", [{ x: 1190, y: 430 }, { x: 1190, y: 390 }, { x: 1850, y: 390 }, { x: 1850, y: 1025 }, { x: 1120, y: 1025 }, { x: 1120, y: 1030 }]),
    relation("zkSuspendGroup", "semaphore", "slot", [{ x: 1810, y: 530 }, { x: 1850, y: 530 }, { x: 1850, y: 1025 }, { x: 1180, y: 1025 }, { x: 1180, y: 1030 }]),

    relation("zkSuspend", "ownerThread", "dependency", [{ x: 705, y: 620 }, { x: 705, y: 635 }, { x: 1260, y: 635 }, { x: 1260, y: 1000 }, { x: 1560, y: 1000 }, { x: 1560, y: 1040 }], "owner thread"),
    relation("ownerThread", "mutex", "dependency", [{ x: 1560, y: 1230 }, { x: 1560, y: 1265 }, { x: 340, y: 1265 }, { x: 340, y: 1230 }], "same-thread release"),

    relation("paths", "mutex", "uses", [{ x: 350, y: 1360 }, { x: 350, y: 1230 }], "single path"),
    relation("semaphore", "runtime", "uses", [{ x: 950, y: 1250 }, { x: 950, y: 1340 }], "recipe nodes"),
    relation("mutex", "runtime", "uses", [{ x: 340, y: 1230 }, { x: 340, y: 1280 }, { x: 850, y: 1280 }, { x: 850, y: 1340 }], "session nodes"),
    relation("paths", "runtime", "uses", [{ x: 610, y: 1450 }, { x: 665, y: 1450 }]),
    relation("ownerThread", "runtime", "dependency", [{ x: 1560, y: 1230 }, { x: 1560, y: 1280 }, { x: 1050, y: 1280 }, { x: 1050, y: 1340 }]),

    relation("classifier", "singleDelegates", "event", [{ x: 690, y: 885 }, { x: 630, y: 885 }]),
    relation("classifier", "slotDelegates", "event", [{ x: 1210, y: 885 }, { x: 1270, y: 885 }]),
  ],
  legend: [
    { kind: "inherit", label: "core interface implementation" },
    { kind: "dependency", label: "factory, owner-thread, or cleanup dependency" },
    { kind: "local", label: "single-leader Curator mutex path" },
    { kind: "slot", label: "group Curator semaphore lease path" },
    { kind: "uses", label: "ZooKeeper path or session runtime dependency" },
    { kind: "event", label: "manual extender backend error-policy path" },
  ],
});

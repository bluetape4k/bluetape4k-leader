#!/usr/bin/env ruby

require "json"
require "yaml"

INVENTORY = ARGV.fetch(0, "build/manual/release-module-inventory.json")
OUTPUT = ARGV.fetch(1, "docs/manual/manifest.yaml")

TITLES = {
  "benchmark" => ["Leader election benchmarks", "리더 선출 벤치마크"],
  "bluetape4k-leader-bom" => ["Leader BOM", "Leader BOM"],
  "bluetape4k-leader-core" => ["Leader core library", "Leader 핵심 라이브러리"],
  "bluetape4k-leader-consul" => ["Consul backend", "Consul 백엔드"],
  "bluetape4k-leader-dynamodb" => ["DynamoDB backend", "DynamoDB 백엔드"],
  "bluetape4k-leader-etcd" => ["etcd backend", "etcd 백엔드"],
  "bluetape4k-leader-exposed-core" => ["Exposed shared backend support", "Exposed 공통 백엔드 지원"],
  "bluetape4k-leader-exposed-jdbc" => ["Exposed JDBC backend", "Exposed JDBC 백엔드"],
  "bluetape4k-leader-exposed-r2dbc" => ["Exposed R2DBC backend", "Exposed R2DBC 백엔드"],
  "bluetape4k-leader-hazelcast" => ["Hazelcast backend", "Hazelcast 백엔드"],
  "bluetape4k-leader-k8s" => ["Kubernetes Lease backend", "Kubernetes Lease 백엔드"],
  "bluetape4k-leader-ktor" => ["Ktor integration", "Ktor 연동"],
  "bluetape4k-leader-micrometer" => ["Micrometer integration", "Micrometer 연동"],
  "bluetape4k-leader-mongodb" => ["MongoDB backend", "MongoDB 백엔드"],
  "bluetape4k-leader-redis-lettuce" => ["Redis Lettuce backend", "Redis Lettuce 백엔드"],
  "bluetape4k-leader-redis-redisson" => ["Redis Redisson backend", "Redis Redisson 백엔드"],
  "bluetape4k-leader-spring-boot" => ["Spring Boot integration", "Spring Boot 연동"],
  "bluetape4k-leader-zookeeper" => ["ZooKeeper backend", "ZooKeeper 백엔드"],
  "batch-scheduler" => ["Batch scheduler workshop", "배치 스케줄러 워크숍"],
  "cache-warmer" => ["Cache warmer workshop", "캐시 워머 워크숍"],
  "consul-maintenance" => ["Consul maintenance workshop", "Consul 유지보수 워크숍"],
  "dynamodb-export" => ["DynamoDB export workshop", "DynamoDB 내보내기 워크숍"],
  "etcd-reconciler" => ["etcd reconciler workshop", "etcd 조정기 워크숍"],
  "k8s-lease" => ["Kubernetes Lease workshop", "Kubernetes Lease 워크숍"],
  "k8s-operator" => ["Kubernetes operator workshop", "Kubernetes 오퍼레이터 워크숍"],
  "ktor-app" => ["Ktor application workshop", "Ktor 애플리케이션 워크숍"],
  "migration-gate" => ["Migration gate workshop", "마이그레이션 게이트 워크숍"],
  "prometheus-dashboard" => ["Prometheus dashboard workshop", "Prometheus 대시보드 워크숍"],
  "rate-limiter" => ["Rate limiter workshop", "요청 제한기 워크숍"],
  "redisson-watchdog" => ["Redisson watchdog workshop", "Redisson watchdog 워크숍"],
  "strategic-election" => ["Strategic election workshop", "전략 기반 선출 워크숍"],
  "tenant-aggregator" => ["Tenant aggregator workshop", "테넌트 집계 워크숍"],
  "virtual-thread-runner" => ["Virtual-thread runner workshop", "가상 스레드 실행 워크숍"],
  "webhook-poller" => ["Webhook poller workshop", "Webhook 폴러 워크숍"],
  "zookeeper-scheduler" => ["ZooKeeper scheduler workshop", "ZooKeeper 스케줄러 워크숍"],
}.freeze

PREVIEW = %w[
  bluetape4k-leader-consul bluetape4k-leader-dynamodb bluetape4k-leader-etcd bluetape4k-leader-k8s
].freeze

def manual_id(row)
  row.fetch("kind") == "example" ? row.fetch("sourceDir").split("/").last : row.fetch("projectName")
end

def group_for(id, kind)
  return "workshops" if kind == "example"
  return "benchmarks" if kind == "benchmark"
  return "platform" if id.end_with?("-bom")
  return "foundation" if id.end_with?("-core") && !id.include?("exposed")
  return "frameworks" if id.match?(/spring-boot|ktor|micrometer/)
  "backends"
end

rows = JSON.parse(File.read(INVENTORY))
modules = rows.map do |row|
  id = manual_id(row)
  kind = row.fetch("kind")
  source = row.fetch("sourceDir")
  artifact = kind == "library" ? "io.github.bluetape4k.leader:#{row.fetch('projectName')}" : nil
  title = TITLES.fetch(id)
  source_paths = ["#{source}/src/main", "#{source}/README.md", "#{source}/README.ko.md"]
  test_paths = ["#{source}/src/test"]
  if id == "bluetape4k-leader-bom"
    source_paths = ["#{source}/build.gradle.kts"]
    test_paths = []
  elsif kind == "benchmark"
    source_paths = ["#{source}/src/benchmark", "#{source}/src/kubernetesBenchmark", "#{source}/README.md", "#{source}/README.ko.md"]
    test_paths = []
  end
  entry = {
    "id" => id,
    "title" => { "en" => title.first, "ko" => title.last },
    "gradlePath" => row.fetch("gradlePath"),
    "sourceDir" => source,
    "kind" => kind,
    "group" => group_for(id, kind),
    "status" => PREVIEW.include?(id) ? "preview" : "stable",
    "artifact" => artifact,
    "en" => "en/modules/#{id}.md",
    "ko" => "ko/modules/#{id}.md",
    "sourcePaths" => source_paths,
    "testPaths" => test_paths,
    "workshops" => kind == "example" ? [source] : [],
  }
  entry
end.sort_by { |entry| entry.fetch("id") }

overview_documents = %w[
  index.md getting-started.md architecture/repository-map.md architecture/runtime-model.md
  guides/learning-path.md guides/election-model-selection.md guides/execution-model-selection.md
  guides/backend-selection.md guides/lease-lifecycle.md guides/failure-and-cancellation.md
  guides/identity-state-and-history.md guides/spring-vs-ktor.md guides/observability-and-operations.md
  guides/testing.md guides/scheduled-job-migration.md
  core/result-semantics.md core/single-group-strategic.md core/execution-apis.md core/lease-extension.md
  backends/redis.md backends/exposed-sql.md backends/document-stores.md
  backends/control-plane-leases.md backends/cluster-coordination.md
  frameworks/spring-boot.md frameworks/ktor.md frameworks/micrometer.md
  benchmarks/interpreting-results.md
]

manifest = {
  "schemaVersion" => 2,
  "repository" => "bluetape4k/bluetape4k-leader",
  "releaseRef" => "0.5.0",
  "releaseCommit" => "721a9a3808f67489d2bdb8177734325981c24977",
  "overview" => {
    "documents" => {
      "en" => overview_documents.map { |path| "en/#{path}" },
      "ko" => overview_documents.map { |path| "ko/#{path}" },
    },
    "assets" => %w[
      assets/overview/repository-learning-map.svg assets/overview/repository-learning-map.png
      assets/architecture/election-lifecycle.svg assets/architecture/election-lifecycle.png
      assets/architecture/model-decision-map.svg assets/architecture/model-decision-map.png
      assets/backends/backend-selection-map.svg assets/backends/backend-selection-map.png
      assets/frameworks/framework-observability-flow.svg assets/frameworks/framework-observability-flow.png
      assets/visual-companions/leader-elector.en.png
      assets/visual-companions/leader-elector.ko.png
      assets/visual-companions/leader-group-elector.en.png
      assets/visual-companions/leader-group-elector.ko.png
    ],
  },
  "modules" => modules,
}

File.write(OUTPUT, YAML.dump(manifest))
puts "Leader manual manifest written: #{modules.length} projects, #{overview_documents.length * 2} overview documents."

#!/usr/bin/env ruby

require "cgi"
require "fileutils"
require "open3"

ROOT = File.expand_path("../..", __dir__)
ASSETS = File.join(ROOT, "docs/manual/assets")
W = 1600
H = 1040

COLORS = {
  cyan: "#9ed8ff", teal: "#5eead4", purple: "#c4b5fd", amber: "#f6c96b",
  rose: "#fda4af", text: "#f8fafc", muted: "#b6c4d6", dim: "#91a4bb",
}.freeze

def esc(value)
  CGI.escapeHTML(value.to_s)
end

def defs
  markers = COLORS.slice(:cyan, :teal, :purple, :amber, :rose).map do |name, color|
    <<~SVG
      <marker id="arrow-#{name}" viewBox="0 0 14 14" refX="13" refY="7" markerWidth="14" markerHeight="14" orient="auto" markerUnits="userSpaceOnUse">
        <path d="M0 0 L14 7 L0 14 Z" fill="#{color}" stroke="#{color}"/>
      </marker>
    SVG
  end.join
  <<~SVG
    <defs>
      <linearGradient id="canvas" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0b1220"/><stop offset="0.58" stop-color="#152235"/><stop offset="1" stop-color="#213149"/></linearGradient>
      <linearGradient id="panel" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#1f2937"/><stop offset="1" stop-color="#101827"/></linearGradient>
      <filter id="shadow" x="-20%" y="-20%" width="140%" height="140%"><feDropShadow dx="0" dy="9" stdDeviation="10" flood-color="#020617" flood-opacity="0.38"/></filter>
      <filter id="glow" x="-20%" y="-20%" width="140%" height="140%"><feDropShadow dx="0" dy="0" stdDeviation="3" flood-color="#67e8f9" flood-opacity="0.2"/></filter>
      #{markers}
    </defs>
  SVG
end

def text_lines(x, y, lines, size: 18, color: COLORS[:muted], anchor: "middle", gap: 28, family: "ui-monospace, SFMono-Regular, Menlo, monospace", weight: 500)
  content = lines.each_with_index.map do |line, index|
    %(<tspan x="#{x}" y="#{y + index * gap}">#{esc(line)}</tspan>)
  end.join
  %(<text text-anchor="#{anchor}" font-family="#{family}" font-size="#{size}" font-weight="#{weight}" fill="#{color}">#{content}</text>)
end

def card(x, y, width, height, title, lines = [], color: COLORS[:cyan], tag: nil)
  title_y = y + 43
  lines_y = y + 80
  tag_svg = tag ? %(<text x="#{x + width - 20}" y="#{y + 26}" text-anchor="end" font-family="ui-monospace, monospace" font-size="13" fill="#{color}">#{esc(tag)}</text>) : ""
  <<~SVG
    <g filter="url(#shadow)">
      <rect x="#{x}" y="#{y}" width="#{width}" height="#{height}" rx="22" fill="#172033" stroke="#{color}" stroke-width="4"/>
      #{tag_svg}
      #{text_lines(x + width / 2, title_y, [title], size: 27, color: color, family: "Architects Daughter, Comic Sans MS, cursive", weight: 700)}
      #{text_lines(x + width / 2, lines_y, lines, size: 15, color: COLORS[:muted], gap: 25)}
    </g>
  SVG
end

def edge(id, d, color: :cyan, dashed: false, width: 4)
  dash = dashed ? ' stroke-dasharray="10 8"' : ""
  %(<path id="#{id}" d="#{d}" fill="none" stroke="#{COLORS.fetch(color)}" stroke-width="#{width}" stroke-linecap="round" stroke-linejoin="round"#{dash} marker-end="url(#arrow-#{color})"/>)
end

def canvas(title, subtitle, description, body)
  <<~SVG
    <svg xmlns="http://www.w3.org/2000/svg" width="#{W}" height="#{H}" viewBox="0 0 #{W} #{H}" role="img" aria-labelledby="title desc">
      <title id="title">#{esc(title)}</title><desc id="desc">#{esc(description)}</desc>
      #{defs}
      <rect width="#{W}" height="#{H}" fill="url(#canvas)"/>
      <rect x="42" y="34" width="1516" height="972" rx="34" fill="url(#panel)" stroke="#536377" stroke-width="4" filter="url(#shadow)"/>
      #{text_lines(800, 96, [title], size: 48, color: COLORS[:text], family: "Architects Daughter, Comic Sans MS, cursive", weight: 700)}
      #{text_lines(800, 137, [subtitle], size: 18, color: COLORS[:muted])}
      #{body}
    </svg>
  SVG
end

def repository_learning_map
  connectors = [
    edge("deps-core", "M800 270 V325", color: :purple),
    edge("core-model", "M800 445 V490 H300 V525", color: :cyan),
    edge("core-backend", "M800 445 V525", color: :teal),
    edge("core-framework", "M800 445 V490 H1300 V525", color: :amber),
    edge("model-workshops", "M300 675 V720 H570 V760", color: :cyan),
    edge("backend-workshops", "M800 675 V760", color: :teal),
    edge("framework-workshops", "M1300 675 V720 H1030 V760", color: :amber),
  ].join
  body = <<~SVG
    <g id="connectors" filter="url(#glow)">#{connectors}</g>
    #{card(360, 175, 880, 95, "bluetape4k-dependencies", ["the only version selected by the application"], color: COLORS[:purple])}
    #{card(510, 325, 580, 120, "leader-core", ["election contracts / options / state", "events / history / lease extension"], color: COLORS[:cyan])}
    #{card(105, 525, 390, 150, "Election models", ["single leader", "group slots", "strategic candidate scoring"], color: COLORS[:cyan])}
    #{card(605, 525, 390, 150, "Backend families", ["Redis / SQL / document", "control plane / cluster coordination"], color: COLORS[:teal])}
    #{card(1105, 525, 390, 150, "Framework and ops", ["Spring Boot / Ktor", "Micrometer / health / history"], color: COLORS[:amber])}
    #{card(185, 760, 1230, 145, "17 runnable workshops", ["start with batch-scheduler, then choose a backend and execution model", "observe election results, contention, lease ownership, metrics, and recovery"], color: COLORS[:rose])}
    #{text_lines(800, 955, ["manual path: choose -> run -> observe -> diagnose -> operate"], size: 17, color: COLORS[:dim])}
  SVG
  canvas("Learn Leader Election from the Boundary Inward", "Leader 0.4 / 17 libraries / 17 workshops / 1 benchmark", "Repository and learning map for the Leader 0.4 manual.", body)
end

def election_lifecycle
  xs = [90, 335, 580, 825, 1070, 1315]
  titles = ["Contend", "Acquire", "Run", "Observe", "Extend", "Release"]
  lines = [
    ["same lock name", "tenant namespace"], ["backend atomicity", "lease + owner token"],
    ["Elected or Skipped", "action owns work"], ["state / events", "history / metrics"],
    ["before expiry", "ownership re-check"], ["finally block", "token-safe unlock"],
  ]
  colors = %i[cyan teal purple amber rose teal]
  cards = xs.each_with_index.map { |x, i| card(x, 360, 195, 150, titles[i], lines[i], color: COLORS[colors[i]]) }.join
  edges = xs.each_cons(2).each_with_index.map do |(a, b), i|
    edge("step-#{i}", "M#{a + 195} 435 H#{b - 12}", color: colors[i])
  end.join
  body = <<~SVG
    #{cards}
    <g id="main-flow">#{edges}</g>
    #{card(180, 180, 500, 135, "Contention is a normal outcome", ["Skipped means another node owns the work", "do not treat it as an application failure"], color: COLORS[:cyan])}
    #{card(920, 180, 500, 135, "Action failure remains visible", ["LeaderRunResult distinguishes ActionFailed", "exceptions are not collapsed into contention"], color: COLORS[:rose])}
    <g id="recovery" filter="url(#glow)">
      #{edge("retry", "M1412 510 V640 H188 V530", color: :amber, dashed: true)}
      #{edge("cancel", "M922 510 V700 H800 V780", color: :rose, dashed: true)}
    </g>
    #{card(560, 780, 480, 115, "Cancellation boundary", ["cancel the action and stop extending the lease", "release only while ownership is still valid"], color: COLORS[:rose])}
    #{text_lines(800, 955, ["retry is a new election attempt, never an assumption of retained leadership"], size: 17, color: COLORS[:dim])}
  SVG
  canvas("Election Is a Lease Lifecycle", "Every successful run has an ownership boundary and an observable outcome", "Lifecycle from contention through lease release, including failure and retry paths.", body)
end

def model_decision_map
  body = <<~SVG
    <g id="connectors" filter="url(#glow)">
      #{edge("start-scope", "M800 280 V340", color: :purple)}
      #{edge("scope-single", "M800 455 V500 H280 V545", color: :cyan)}
      #{edge("scope-group", "M800 455 V545", color: :teal)}
      #{edge("scope-strategic", "M800 455 V500 H1320 V545", color: :amber)}
      #{edge("single-exec", "M280 690 V745 H550 V790", color: :cyan)}
      #{edge("group-exec", "M800 690 V790", color: :teal)}
      #{edge("strategic-exec", "M1320 690 V745 H1050 V790", color: :amber)}
    </g>
    #{card(430, 175, 740, 105, "How many nodes may run?", ["decide concurrency before choosing a backend"], color: COLORS[:purple])}
    #{card(540, 340, 520, 115, "What selects the owner?", ["one lock / fixed slots / scored candidates"], color: COLORS[:purple])}
    #{card(90, 545, 380, 145, "Single leader", ["exactly one active owner", "batch / migration / polling"], color: COLORS[:cyan])}
    #{card(610, 545, 380, 145, "Group election", ["bounded parallel slots", "partitioned or tenant work"], color: COLORS[:teal])}
    #{card(1130, 545, 380, 145, "Strategic election", ["candidate registry + scorer", "capacity or locality aware"], color: COLORS[:amber])}
    #{card(310, 790, 480, 120, "Execution API", ["blocking / CompletableFuture / virtual thread", "choose by caller ownership and cancellation"], color: COLORS[:cyan])}
    #{card(810, 790, 480, 120, "Coroutine API", ["SuspendLeaderElector for suspend work", "structured cancellation stays visible"], color: COLORS[:teal])}
    #{text_lines(800, 966, ["backend choice comes next: match atomicity, time source, failure model, and operations"], size: 17, color: COLORS[:dim])}
  SVG
  canvas("Choose the Election Model Before the Backend", "scope -> ownership rule -> execution API -> backend", "Decision map for single, group, and strategic leader election and their execution APIs.", body)
end

def backend_selection_map
  body = <<~SVG
    <g id="connectors" filter="url(#glow)">
      #{edge("start-latency", "M800 270 V335 H190 V390", color: :cyan)}
      #{edge("start-sql", "M800 270 V335 H495 V390", color: :teal)}
      #{edge("start-doc", "M800 270 V390", color: :purple)}
      #{edge("start-control", "M800 270 V335 H1105 V390", color: :amber)}
      #{edge("start-cluster", "M800 270 V335 H1410 V390", color: :rose)}
    </g>
    #{card(430, 175, 740, 95, "What infrastructure already owns coordination?", ["prefer the system your operators can observe and recover"], color: COLORS[:purple])}
    #{card(70, 390, 240, 210, "Redis", ["Lettuce: explicit APIs", "Redisson: watchdog", "low-latency TTL locks", "STABLE"], color: COLORS[:cyan])}
    #{card(375, 390, 240, 210, "Exposed SQL", ["JDBC or R2DBC", "database server time", "schema + transactions", "STABLE"], color: COLORS[:teal])}
    #{card(680, 390, 240, 210, "Document", ["MongoDB: stable", "DynamoDB: preview", "conditional ownership", "logical expiry"], color: COLORS[:purple])}
    #{card(985, 390, 240, 210, "Control plane", ["etcd / Consul / K8s", "native lease semantics", "operator credentials", "PREVIEW"], color: COLORS[:amber])}
    #{card(1290, 390, 240, 210, "Cluster", ["Hazelcast IMap", "ZooKeeper Curator", "membership/session", "STABLE"], color: COLORS[:rose])}
    #{card(125, 680, 420, 165, "Compare semantics", ["atomic acquire / owner token", "time source / lease renewal", "unlock safety / group slots"], color: COLORS[:cyan])}
    #{card(590, 680, 420, 165, "Compare operations", ["credentials / topology", "monitoring / outage behavior", "clock assumptions / cleanup"], color: COLORS[:teal])}
    #{card(1055, 680, 420, 165, "Prove with a workshop", ["run the matching example", "observe contention and expiry", "verify recovery and metrics"], color: COLORS[:amber])}
    <g id="comparison-flow" filter="url(#glow)">
      #{edge("semantics-ops", "M545 762 H578", color: :cyan)}
      #{edge("ops-proof", "M1010 762 H1043", color: :teal)}
    </g>
    #{text_lines(800, 935, ["preview means the API exists in 0.4, but the operational contract is still intentionally narrower"], size: 17, color: COLORS[:dim])}
  SVG
  canvas("Pick the Backend You Can Operate", "selection is an ownership and recovery decision, not only a latency decision", "Backend selection map across Redis, SQL, document, control-plane, and cluster coordination families.", body)
end

def framework_observability_flow
  body = <<~SVG
    <g id="connectors" filter="url(#glow)">
      #{edge("trigger-spring", "M800 285 V335 H390 V390", color: :cyan)}
      #{edge("trigger-ktor", "M800 285 V335 H1210 V390", color: :amber)}
      #{edge("spring-elector", "M390 540 V590 H800 V635", color: :cyan)}
      #{edge("ktor-elector", "M1210 540 V590 H800 V635", color: :amber)}
      #{edge("elector-backend", "M800 755 V800 H360 V845", color: :teal)}
      #{edge("elector-events", "M800 755 V800 H1240 V845", color: :purple)}
      #{edge("events-metrics", "M1240 930 H1040", color: :purple)}
      #{edge("backend-release", "M360 930 H560", color: :teal)}
    </g>
    #{card(500, 180, 600, 105, "Application-owned trigger", ["scheduled job / request-independent background task"], color: COLORS[:purple])}
    #{card(170, 390, 440, 150, "Spring Boot", ["auto-configuration + CTW aspect", "annotations / SpEL / health", "private methods are not intercepted"], color: COLORS[:cyan])}
    #{card(990, 390, 440, 150, "Ktor", ["LeaderElection plugin", "leaderScheduled suspend action", "management registry + lifecycle"], color: COLORS[:amber])}
    #{card(520, 635, 560, 120, "LeaderElector boundary", ["Elected / Skipped / ActionFailed", "cancellation and lease ownership remain explicit"], color: COLORS[:teal])}
    #{card(120, 845, 480, 115, "Selected backend", ["atomic acquire / renew / release", "resource lifecycle belongs to the application"], color: COLORS[:teal])}
    #{card(620, 845, 360, 115, "Release gate", ["finally + owner token", "stop extender before unlock"], color: COLORS[:rose])}
    #{card(1000, 845, 480, 115, "Events and Micrometer", ["state / listener / history", "meters, health, dashboards, alerts"], color: COLORS[:purple])}
  SVG
  canvas("Framework Convenience Must Preserve Ownership", "Spring Boot and Ktor converge on the same elector, backend, and observability contracts", "Flow from application triggers through Spring or Ktor to an elector, backend, release gate, and Micrometer observations.", body)
end

DIAGRAMS = {
  "overview/repository-learning-map" => repository_learning_map,
  "architecture/election-lifecycle" => election_lifecycle,
  "architecture/model-decision-map" => model_decision_map,
  "backends/backend-selection-map" => backend_selection_map,
  "frameworks/framework-observability-flow" => framework_observability_flow,
}.freeze

DIAGRAMS.each do |relative, svg|
  svg_path = File.join(ASSETS, "#{relative}.svg")
  png_path = File.join(ASSETS, "#{relative}.png")
  FileUtils.mkdir_p(File.dirname(svg_path))
  File.write(svg_path, svg)
  stdout, stderr, status = Open3.capture3("rsvg-convert", "-w", (W * 2).to_s, "-h", (H * 2).to_s, "-o", png_path, svg_path)
  abort("render failed for #{relative}: #{stdout}#{stderr}") unless status.success?
end

puts "Rendered #{DIAGRAMS.length} dark Leader diagrams as SVG and 2x PNG pairs."

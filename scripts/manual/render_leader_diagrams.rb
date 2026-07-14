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
      <rect class="card" x="#{x}" y="#{y}" width="#{width}" height="#{height}" rx="22" fill="#172033" stroke="#{color}" stroke-width="4"/>
      #{tag_svg}
      #{text_lines(x + width / 2, title_y, [title], size: 27, color: color, family: "Architects Daughter, Comic Sans MS, cursive", weight: 700)}
      #{text_lines(x + width / 2, lines_y, lines, size: 15, color: COLORS[:muted], gap: 25)}
    </g>
  SVG
end

def rounded_path(points, radius: 14)
  raise ArgumentError, "a route needs at least two points" if points.length < 2

  commands = ["M#{points.first.join(' ')}"]
  points.each_cons(3) do |from, corner, to|
    incoming = [corner[0] - from[0], corner[1] - from[1]]
    outgoing = [to[0] - corner[0], to[1] - corner[1]]
    raise ArgumentError, "routes must be orthogonal" unless incoming.one?(&:zero?) && outgoing.one?(&:zero?)

    in_length = incoming.map(&:abs).max
    out_length = outgoing.map(&:abs).max
    bend = [radius, in_length / 2.0, out_length / 2.0].min
    in_unit = incoming.map { |value| value.zero? ? 0 : value / value.abs }
    out_unit = outgoing.map { |value| value.zero? ? 0 : value / value.abs }
    approach = [corner[0] - in_unit[0] * bend, corner[1] - in_unit[1] * bend]
    departure = [corner[0] + out_unit[0] * bend, corner[1] + out_unit[1] * bend]
    commands << "L#{approach.join(' ')}"
    commands << "Q#{corner.join(' ')} #{departure.join(' ')}"
  end
  commands << "L#{points.last.join(' ')}"
  commands.join(" ")
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
    edge("core-model", rounded_path([[735, 445], [735, 470], [300, 470], [300, 525]]), color: :cyan),
    edge("core-backend", "M800 445 V525", color: :teal),
    edge("core-framework", rounded_path([[865, 445], [865, 500], [1300, 500], [1300, 525]]), color: :amber),
    edge("model-workshops", rounded_path([[300, 675], [300, 710], [570, 710], [570, 760]]), color: :cyan),
    edge("backend-workshops", "M800 675 V760", color: :teal),
    edge("framework-workshops", rounded_path([[1300, 675], [1300, 730], [1030, 730], [1030, 760]]), color: :amber),
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
    #{card(180, 165, 500, 135, "Contention is a normal outcome", ["Skipped means another node owns the work", "do not treat it as an application failure"], color: COLORS[:cyan])}
    #{card(920, 165, 500, 135, "Action failure remains visible", ["LeaderRunResult distinguishes ActionFailed", "exceptions are not collapsed into contention"], color: COLORS[:rose])}
    <g id="recovery" filter="url(#glow)">
      #{edge("retry", rounded_path([[1412, 360], [1412, 325], [188, 325], [188, 360]], radius: 12), color: :amber, dashed: true)}
      #{edge("cancel", rounded_path([[677, 510], [677, 700], [760, 700], [760, 780]]), color: :rose, dashed: true)}
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
      #{edge("scope-single", rounded_path([[700, 455], [700, 490], [280, 490], [280, 545]]), color: :cyan)}
      #{edge("scope-group", "M800 455 V545", color: :teal)}
      #{edge("scope-strategic", rounded_path([[900, 455], [900, 510], [1320, 510], [1320, 545]]), color: :amber)}
      #{edge("single-exec", rounded_path([[280, 690], [280, 735], [550, 735], [550, 790]]), color: :cyan)}
      #{edge("group-exec", rounded_path([[800, 690], [800, 750], [930, 750], [930, 790]]), color: :teal)}
      #{edge("strategic-exec", rounded_path([[1320, 690], [1320, 720], [1170, 720], [1170, 790]]), color: :amber)}
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
      #{edge("start-latency", rounded_path([[680, 270], [680, 310], [190, 310], [190, 390]]), color: :cyan)}
      #{edge("start-sql", rounded_path([[740, 270], [740, 325], [495, 325], [495, 390]]), color: :teal)}
      #{edge("start-doc", "M800 270 V390", color: :purple)}
      #{edge("start-control", rounded_path([[860, 270], [860, 350], [1105, 350], [1105, 390]]), color: :amber)}
      #{edge("start-cluster", rounded_path([[920, 270], [920, 325], [1410, 325], [1410, 390]]), color: :rose)}
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
      #{edge("semantics-ops", "M545 762 H590", color: :cyan)}
      #{edge("ops-proof", "M1010 762 H1055", color: :teal)}
    </g>
    #{text_lines(800, 935, ["preview means the API exists in 0.4, but the operational contract is still intentionally narrower"], size: 17, color: COLORS[:dim])}
  SVG
  canvas("Pick the Backend You Can Operate", "selection is an ownership and recovery decision, not only a latency decision", "Backend selection map across Redis, SQL, document, control-plane, and cluster coordination families.", body)
end

def framework_observability_flow
  body = <<~SVG
    <g id="connectors" filter="url(#glow)">
      #{edge("trigger-spring", rounded_path([[700, 285], [700, 330], [390, 330], [390, 390]]), color: :cyan)}
      #{edge("trigger-ktor", rounded_path([[900, 285], [900, 345], [1210, 345], [1210, 390]]), color: :amber)}
      #{edge("spring-elector", rounded_path([[390, 540], [390, 580], [700, 580], [700, 635]]), color: :cyan)}
      #{edge("ktor-elector", rounded_path([[1210, 540], [1210, 595], [900, 595], [900, 635]]), color: :amber)}
      #{edge("elector-backend", rounded_path([[720, 755], [720, 790], [310, 790], [310, 845]]), color: :teal)}
      #{edge("elector-events", rounded_path([[880, 755], [880, 805], [1290, 805], [1290, 845]]), color: :purple)}
      #{edge("release-events", "M950 920 H1060", color: :purple)}
      #{edge("backend-release", "M540 900 H650", color: :teal)}
    </g>
    #{card(500, 180, 600, 105, "Application-owned trigger", ["scheduled job / request-independent background task"], color: COLORS[:purple])}
    #{card(170, 390, 440, 150, "Spring Boot", ["auto-configuration + CTW aspect", "annotations / SpEL / health", "private methods are not intercepted"], color: COLORS[:cyan])}
    #{card(990, 390, 440, 150, "Ktor", ["LeaderElection plugin", "leaderScheduled suspend action", "management registry + lifecycle"], color: COLORS[:amber])}
    #{card(520, 635, 560, 120, "LeaderElector boundary", ["Elected / Skipped / ActionFailed", "cancellation and lease ownership remain explicit"], color: COLORS[:teal])}
    #{card(80, 845, 460, 115, "Selected backend", ["atomic acquire / renew / release", "resource lifecycle belongs to the application"], color: COLORS[:teal])}
    #{card(650, 845, 300, 115, "Release gate", ["finally + owner token", "stop extender before unlock"], color: COLORS[:rose])}
    #{card(1060, 845, 460, 115, "Events and Micrometer", ["state / listener / history", "meters, health, dashboards, alerts"], color: COLORS[:purple])}
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

requested = ARGV.empty? ? DIAGRAMS.keys : ARGV
unknown = requested - DIAGRAMS.keys
abort("Unknown diagram(s): #{unknown.join(', ')}") unless unknown.empty?

requested.each do |relative|
  svg = DIAGRAMS.fetch(relative)
  svg_path = File.join(ASSETS, "#{relative}.svg")
  png_path = File.join(ASSETS, "#{relative}.png")
  FileUtils.mkdir_p(File.dirname(svg_path))
  File.write(svg_path, svg)
  stdout, stderr, status = Open3.capture3("cairosvg", svg_path, "-o", png_path, "-s", "2")
  abort("render failed for #{relative}: #{stdout}#{stderr}") unless status.success?
end

puts "Rendered #{requested.length} dark Leader diagrams as SVG and 2x PNG pairs with CairoSVG."

#!/usr/bin/env ruby

require "fileutils"
require "pathname"
require "yaml"

module ManualDocs
  class ManualGenerator
    GenerationResult = Struct.new(:created, :skipped, keyword_init: true) do
      def created_count
        created.length
      end

      def skipped_count
        skipped.length
      end
    end

    REQUIRED_SECTIONS = %w[
      problem when-to-use coordinates concepts quick-start api-by-task patterns
      integrations configuration failures operations testing workshops limitations sources
    ].freeze
    TEMPLATES = { "library" => "module.md", "example" => "example.md", "benchmark" => "benchmark.md" }.freeze
    HEADINGS = {
      "en" => {
        "problem" => "Problem", "when-to-use" => "When to use it", "coordinates" => "Coordinates",
        "concepts" => "Core concepts", "quick-start" => "Quick start", "api-by-task" => "API by task",
        "patterns" => "Recommended patterns", "integrations" => "Integrations", "configuration" => "Configuration",
        "failures" => "Failure modes", "operations" => "Operations", "testing" => "Testing",
        "workshops" => "Workshops and learning path", "limitations" => "Limitations", "sources" => "Sources",
      },
      "ko" => {
        "problem" => "제공하는 기능", "when-to-use" => "사용하기 좋은 경우", "coordinates" => "의존성 좌표",
        "concepts" => "핵심 개념", "quick-start" => "빠르게 시작하기", "api-by-task" => "작업별 API",
        "patterns" => "권장 패턴", "integrations" => "연동", "configuration" => "설정",
        "failures" => "실패 유형과 해결 방법", "operations" => "운영", "testing" => "테스트",
        "workshops" => "학습 경로와 예제", "limitations" => "제약 사항", "sources" => "근거 자료",
      },
    }.freeze
    TYPE_CONTENT = {
      "library" => {
        "en" => "Maven coordinate: `{{artifact}}`. API-oriented quick start: begin with the smallest stable-release API example, then expand it by task.",
        "ko" => "Maven 좌표: `{{artifact}}`. API 중심의 빠른 시작: 안정판에서 확인한 가장 작은 예제부터 실행한 뒤 작업별 사용법으로 넓혀 가세요.",
      },
      "example" => {
        "en" => "Prerequisites: prepare the required service. Run: `./gradlew {{gradle_task}}`. Observable result: verify the test report. Diagnosis: inspect the service state and Gradle logs when the result differs.",
        "ko" => "사전 준비: 예제에 필요한 서비스를 준비합니다. 실행 명령: `./gradlew {{gradle_task}}`. 확인할 결과: 테스트 보고서를 확인합니다. 문제 진단: 결과가 다르면 서비스 상태와 Gradle 로그부터 살펴봅니다.",
      },
      "benchmark" => {
        "en" => "Workload: define the measured operation. Environment: record hardware and runtime settings. Metric direction: state whether higher or lower is better. Representative result: report a reproducible sample. Caveats: compare equivalent conditions. What this does not prove: production performance under a different workload or environment.",
        "ko" => "워크로드: 측정할 작업을 명확히 정합니다. 실행 환경: 하드웨어와 런타임 설정을 기록합니다. 지표 방향: 값이 높거나 낮을 때 무엇이 더 좋은지 밝힙니다. 대표 결과: 재현 가능한 표본을 제시합니다. 주의 사항: 같은 조건끼리 비교해야 합니다. 입증하지 못하는 것: 다른 워크로드나 환경에서의 운영 성능까지 보장하지는 않습니다.",
      },
    }.freeze

    def initialize(repository_root:, manifest_path:)
      @repository_root = File.expand_path(repository_root)
      @manifest_path = File.expand_path(manifest_path)
      @manual_root = File.dirname(@manifest_path)
    end

    def generate
      manifest = YAML.safe_load(File.read(@manifest_path))
      created = []
      skipped = []
      manifest.fetch("modules").sort_by { |entry| entry.fetch("id") }.each do |entry|
        template = read_template(entry.fetch("kind"))
        %w[en ko].each do |locale|
          relative = entry.fetch(locale)
          output = safe_output(relative)
          if File.exist?(output)
            skipped << output
            next
          end
          FileUtils.mkdir_p(File.dirname(output))
          File.write(output, render(template, entry, locale))
          created << output
        end
      end
      GenerationResult.new(created: created.freeze, skipped: skipped.freeze)
    end

    private

    def read_template(kind)
      name = TEMPLATES.fetch(kind) { raise ArgumentError, "unsupported manual kind: #{kind}" }
      File.read(File.join(@manual_root, "templates", name))
    end

    def safe_output(relative)
      unless relative.is_a?(String) && !relative.empty? && !Pathname.new(relative).absolute? && Pathname.new(relative).each_filename.none? { |part| part == ".." }
        raise ArgumentError, "unsafe manual output path: #{relative}"
      end
      output = File.expand_path(relative, @manual_root)
      raise ArgumentError, "unsafe manual output path: #{relative}" unless output.start_with?(@manual_root + File::SEPARATOR)
      raise ArgumentError, "unsafe manual output path: #{relative}" unless safe_output_chain?(output)
      output
    end

    def safe_output_chain?(output)
      repository_real = File.realpath(@repository_root)
      manual_metadata = File.lstat(@manual_root)
      return false if manual_metadata.symlink? || !manual_metadata.directory?
      manual_real = File.realpath(@manual_root)
      return false unless within?(manual_real, repository_real)

      relative = Pathname.new(output).relative_path_from(Pathname.new(@manual_root))
      current = @manual_root
      relative.each_filename do |part|
        current = File.join(current, part)
        metadata = lstat_or_nil(current)
        next unless metadata
        return false if metadata.symlink?
        return false unless within?(File.realpath(current), manual_real)
      end
      true
    rescue SystemCallError, ArgumentError
      false
    end

    def lstat_or_nil(path)
      File.lstat(path)
    rescue Errno::ENOENT, Errno::ENOTDIR
      nil
    end

    def within?(path, boundary)
      expanded = File.expand_path(path)
      root = File.expand_path(boundary)
      expanded == root || expanded.start_with?(root + File::SEPARATOR)
    end

    def render(template, entry, locale)
      headings = HEADINGS.fetch(locale)
      remaining_sections = REQUIRED_SECTIONS.drop(1).map do |id|
        "## #{headings.fetch(id)} {##{id}}\n\n#{placeholder(locale)}\n"
      end.join("\n")
      artifact = entry["artifact"]
      replacements = {
        "id" => entry.fetch("id"),
        "title" => localized_title(entry, locale),
        "locale" => locale,
        "kind" => entry.fetch("kind"),
        "gradlePath" => entry.fetch("gradlePath"),
        "sourceDir" => entry.fetch("sourceDir"),
        "artifact" => artifact.nil? ? "null" : artifact,
        "heading_problem" => "## #{headings.fetch('problem')}",
        "kind_intro" => kind_intro(entry.fetch("kind"), locale),
        "type_specific_content" => type_content(entry, locale),
        "remaining_sections" => remaining_sections.rstrip,
      }
      REQUIRED_SECTIONS.each do |section|
        token = section.tr("-", "_")
        replacements["heading_#{token}"] = "## #{headings.fetch(section)}"
        replacements["content_#{token}"] = section_content(entry, locale, section)
      end
      replacements.reduce(template) { |rendered, (key, value)| rendered.gsub("{{#{key}}}", value) }
    end

    def localized_title(entry, locale)
      title = entry["title"]
      return title.fetch(locale) if title.is_a?(Hash)
      return title if title.is_a?(String) && !title.empty?
      entry.fetch("id").split("-").map(&:capitalize).join(" ")
    end

    def type_content(entry, locale)
      TYPE_CONTENT.fetch(entry.fetch("kind")).fetch(locale)
        .gsub("{{artifact}}", entry["artifact"].to_s)
        .gsub("{{gradle_task}}", "#{entry.fetch('gradlePath')}:test")
    end

    def kind_intro(kind, locale)
      labels = {
        "library" => { "en" => "Library module", "ko" => "라이브러리 모듈" },
        "example" => { "en" => "Runnable example", "ko" => "실행 가능한 예제" },
        "benchmark" => { "en" => "Performance benchmark", "ko" => "성능 벤치마크" },
      }
      labels.fetch(kind).fetch(locale)
    end

    def section_content(entry, locale, section)
      content = placeholder(locale)
      kind = entry.fetch("kind")
      if section == "sources"
        label = locale == "ko" ? "Gradle 빌드 파일" : "Gradle build file"
        return "[#{label}](../../../../#{entry.fetch('sourceDir')}/build.gradle.kts)"
      end
      if kind == "library"
        content = locale == "ko" ? "Maven 좌표: `#{entry.fetch('artifact')}`" : "Maven coordinate: `#{entry.fetch('artifact')}`" if section == "coordinates"
        content = locale == "ko" ? "안정판 API를 중심으로 가장 작은 사용 예제를 작성합니다." : "Start with the smallest API-oriented quick start backed by the stable release." if section == "quick-start"
      elsif kind == "example"
        content = locale == "ko" ? "이 예제는 별도 라이브러리 좌표를 제공하지 않습니다." : "This runnable example does not publish a library coordinate." if section == "coordinates"
        content = locale == "ko" ? "사전 준비를 마친 뒤 `./gradlew #{entry.fetch('gradlePath')}:test`로 예제 시나리오를 실행합니다." : "Complete the prerequisites, then run the example scenarios with `./gradlew #{entry.fetch('gradlePath')}:test`." if section == "quick-start"
        content = locale == "ko" ? "예상한 출력이 보이지 않으면 서비스 상태와 Gradle 로그부터 확인합니다." : "Verify the observable result; inspect the service state and Gradle logs for failure diagnosis." if section == "failures"
      elsif kind == "benchmark"
        content = locale == "ko" ? "워크로드와 실행 환경을 고정하고 함께 기록합니다." : "Define the workload and record the execution environment." if section == "concepts"
        content = locale == "ko" ? "지표의 방향과 재현 가능한 대표 결과를 함께 제시합니다." : "State the metric direction and a reproducible representative result." if section == "operations"
        content = locale == "ko" ? "조건이 다른 결과를 직접 비교하지 마세요. 이 벤치마크만으로 운영 성능을 입증할 수는 없습니다." : "Caveats: do not compare unlike conditions. This benchmark does not prove production performance." if section == "limitations"
      end
      content
    end

    def placeholder(locale)
      locale == "ko" ? "안정판 소스를 바탕으로 내용을 보강할 예정입니다." : "This section will be completed from the stable release source."
    end
  end
end

if $PROGRAM_NAME == __FILE__
  result = ManualDocs::ManualGenerator.new(
    repository_root: Dir.pwd, manifest_path: ARGV.fetch(0, "docs/manual/manifest.yaml"),
  ).generate
  puts "Created #{result.created_count} manual scaffolds; skipped #{result.skipped_count} existing files."
end

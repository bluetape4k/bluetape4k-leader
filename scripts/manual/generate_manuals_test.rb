require "fileutils"
require "minitest/autorun"
require "tmpdir"
require "yaml"

require_relative "generate_manuals"

class GenerateManualsTest < Minitest::Test
  SECTIONS = %w[
    problem when-to-use coordinates concepts quick-start api-by-task patterns
    integrations configuration failures operations testing workshops limitations sources
  ].freeze

  def test_selects_kind_templates_and_renders_complete_bilingual_frontmatter
    with_generator(%w[library example benchmark]) do |root, generator|
      result = generator.generate

      assert_equal 6, result.created_count
      assert_equal 0, result.skipped_count
      {
        "library" => ["module-template", "io.github.bluetape4k:library"],
        "example" => ["example-template", nil],
        "benchmark" => ["benchmark-template", nil],
      }.each do |kind, (marker, artifact)|
        %w[en ko].each do |locale|
          content = File.read(File.join(root, "docs/manual/#{locale}/modules/#{kind}.md"))
          metadata = YAML.safe_load(content.match(/\A---\n(.*?)\n---\n/m)[1])
          assert_equal kind, metadata.fetch("manualId")
          assert_equal kind, metadata.fetch("id")
          assert_equal "#{kind}-#{locale}", metadata.fetch("title")
          assert_equal locale, metadata.fetch("locale")
          assert_equal kind, metadata.fetch("kind")
          assert_equal ":#{kind}", metadata.fetch("gradlePath")
          assert_equal "modules/#{kind}", metadata.fetch("sourceDir")
          assert_equal "fixture-release", metadata.fetch("releaseRef")
          artifact.nil? ? assert_nil(metadata["artifact"]) : assert_equal(artifact, metadata["artifact"])
          assert_includes content, marker
          SECTIONS.each { |anchor| assert_equal 1, content.scan(/\{##{Regexp.escape(anchor)}\}/).length }
          assert_includes content, locale == "ko" ? "제공하는 기능" : "Problem"
        end
      end
    end
  end

  def test_kind_templates_include_their_required_operational_content
    with_generator(%w[library example benchmark]) do |root, generator|
      generator.generate

      library = File.read(File.join(root, "docs/manual/en/modules/library.md"))
      assert_includes library, "Maven coordinate"
      assert_includes library, "API-oriented quick start"

      example = File.read(File.join(root, "docs/manual/en/modules/example.md"))
      %w[Prerequisites Gradle Observable Diagnosis].each { |term| assert_includes example, term }

      example_ko = File.read(File.join(root, "docs/manual/ko/modules/example.md"))
      ["명령으로 실행합니다", "실행 결과를 확인하고", "Gradle 로그부터 살펴봅니다"].each do |term|
        assert_includes example_ko, term
      end
      %w[workload client credential mock].each { |term| refute_includes example_ko, term }

      benchmark = File.read(File.join(root, "docs/manual/en/modules/benchmark.md"))
      ["Workload", "Environment", "Metric direction", "Representative result", "Caveats", "does not prove"].each do |term|
        assert_includes benchmark, term
      end
    end
  end

  def test_real_templates_render_explicit_type_guidance_in_both_locales
    Dir.mktmpdir("manual-generator-real-templates") do |root|
      template_root = File.join(root, "docs/manual/templates")
      FileUtils.mkdir_p(template_root)
      FileUtils.cp_r(Dir[File.expand_path("../../docs/manual/templates/*.md", __dir__)], template_root)
      manifest = File.join(root, "docs/manual/manifest.yaml")
      File.write(manifest, YAML.dump({ "releaseRef" => "fixture-release", "modules" => %w[library example benchmark].map { |kind| entry(kind) } }))

      ManualDocs::ManualGenerator.new(repository_root: root, manifest_path: manifest).generate

      expectations = {
        "library" => {
          "en" => ["Maven coordinate:", "API-oriented quick start"],
          "ko" => ["Maven 좌표:", "API 중심의 빠른 시작"],
        },
        "example" => {
          "en" => ["Prerequisites:", "Run:", "Observable result:", "Diagnosis:"],
          "ko" => [
            "사전 준비:", "명령으로 실행합니다", "실행 결과를 확인하고", "Gradle 로그부터 살펴봅니다",
            "실행한 작업", "태그 값의 종류", "특권 모드", "모의 객체", "작업 본문", "코어 실행 모델",
          ],
        },
        "benchmark" => {
          "en" => ["Workload:", "Environment:", "Metric direction:", "Representative result:", "Caveats:", "What this does not prove:"],
          "ko" => ["워크로드:", "실행 환경:", "지표 방향:", "대표 결과:", "주의 사항:", "입증하지 못하는 것:"],
        },
      }
      expectations.each do |kind, locales|
        locales.each do |locale, required|
          content = File.read(File.join(root, "docs/manual/#{locale}/modules/#{kind}.md"))
          required.each { |phrase| assert_includes content, phrase }
        end
      end
    end
  end

  def test_preserves_authored_bytes_and_second_run_performs_zero_writes
    with_generator(["library"]) do |root, generator|
      authored = File.join(root, "docs/manual/en/modules/library.md")
      FileUtils.mkdir_p(File.dirname(authored))
      bytes = "---\nmanualId: library\n---\n수기로 작성한 내용\x00\n".b
      File.binwrite(authored, bytes)

      first = generator.generate
      assert_equal 1, first.created_count
      assert_equal 1, first.skipped_count
      assert_equal bytes, File.binread(authored)

      snapshots = Dir[File.join(root, "docs/manual/{en,ko}/modules/*.md")].to_h { |path| [path, File.binread(path)] }
      second = generator.generate
      assert_equal 0, second.created_count
      assert_equal 2, second.skipped_count
      assert_equal snapshots, snapshots.keys.to_h { |path| [path, File.binread(path)] }
    end
  end

  def test_rejects_an_unsafe_output_path
    with_generator(["library"], en: "../../../outside.md") do |_root, generator|
      error = assert_raises(ArgumentError) { generator.generate }
      assert_equal "unsafe manual output path: ../../../outside.md", error.message
    end
  end

  def test_rejects_a_symlinked_output_directory_that_escapes_the_manual_root
    Dir.mktmpdir("manual-generator") do |root|
      Dir.mktmpdir("outside-generator") do |outside|
        generator = prepare_generator(root, [entry("library")])
        FileUtils.rm_rf(File.join(root, "docs/manual/en"))
        File.symlink(outside, File.join(root, "docs/manual/en"))
        error = assert_raises(ArgumentError) { generator.generate }
        assert_equal "unsafe manual output path: en/modules/library.md", error.message
      end
    end
  end

  def test_rejects_a_dangling_output_symlink_without_creating_the_external_file
    Dir.mktmpdir("manual-generator") do |root|
      Dir.mktmpdir("outside-generator") do |outside|
        generator = prepare_generator(root, [entry("library")])
        FileUtils.mkdir_p(File.join(root, "docs/manual/en/modules"))
        external = File.join(outside, "not-created.md")
        File.symlink(external, File.join(root, "docs/manual/en/modules/library.md"))
        error = assert_raises(ArgumentError) { generator.generate }
        assert_equal "unsafe manual output path: en/modules/library.md", error.message
        refute File.exist?(external)
      end
    end
  end

  private

  def with_generator(kinds, en: nil)
    Dir.mktmpdir("manual-generator") do |root|
      entries = kinds.map { |kind| entry(kind) }
      entries.first["en"] = en if en
      yield root, prepare_generator(root, entries)
    end
  end

  def prepare_generator(root, entries)
    template_root = File.join(root, "docs/manual/templates")
    FileUtils.mkdir_p(template_root)
    %w[module example benchmark].each do |name|
      File.write(File.join(template_root, "#{name}.md"), fixture_template("#{name}-template"))
    end
    manifest = File.join(root, "docs/manual/manifest.yaml")
    File.write(manifest, YAML.dump({ "releaseRef" => "fixture-release", "modules" => entries }))
    ManualDocs::ManualGenerator.new(repository_root: root, manifest_path: manifest)
  end

  def entry(kind)
    {
      "id" => kind,
      "title" => { "en" => "#{kind}-en", "ko" => "#{kind}-ko" },
      "kind" => kind,
      "gradlePath" => ":#{kind}",
      "sourceDir" => "modules/#{kind}",
      "artifact" => kind == "library" ? "io.github.bluetape4k:library" : nil,
      "en" => "en/modules/#{kind}.md",
      "ko" => "ko/modules/#{kind}.md",
    }
  end

  def fixture_template(marker)
    <<~MARKDOWN
      ---
      manualId: "{{id}}"
      id: "{{id}}"
      title: "{{title}}"
      locale: "{{locale}}"
      kind: "{{kind}}"
      gradlePath: "{{gradlePath}}"
      sourceDir: "{{sourceDir}}"
      releaseRef: "{{releaseRef}}"
      artifact: {{artifact}}
      ---
      {{heading_problem}} {#problem}
      #{marker}
      {{type_specific_content}}
      {{remaining_sections}}
    MARKDOWN
  end
end

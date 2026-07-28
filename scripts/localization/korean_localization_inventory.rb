require "json"
require "optparse"
require "pathname"

module KoreanLocalization
  DOC_EXTENSIONS = %w[.md .adoc .rst .txt].freeze
  KOTLIN_EXTENSION = ".kt"
  EXCLUDED_DIRS = %w[
    .git
    .gradle
    .omx
    .claude
    build
  ].freeze
  EXCLUDED_DOC_FILES = %w[
    AGENTS.md
    CLAUDE.md
  ].freeze
  EXCLUDED_MANUAL_PREFIXES = %w[
    docs/manual/en/
    docs/manual/ko/
  ].freeze
  DOCUMENT_TEXT_MINIMUM = 80
  KDOC_TEXT_MINIMUM = 24

  Candidate = Struct.new(:path, :bucket, :kind, keyword_init: true) do
    def to_h
      { path: path, bucket: bucket, kind: kind }
    end
  end
  Violation = Struct.new(:path, :kind, :line, :reason, keyword_init: true) do
    def to_h
      { path: path, kind: kind, line: line, reason: reason }
    end
  end

  class Inventory
    def initialize(root)
      @root = Pathname.new(root).expand_path
    end

    def documentation_candidates
      files.select { |path| documentation_candidate?(path) }
           .map { |path| Candidate.new(path: path, bucket: bucket_for(path), kind: "documentation") }
           .sort_by(&:path)
    end

    def kotlin_candidates
      files.select { |path| kotlin_candidate?(path) }
           .map { |path| Candidate.new(path: path, bucket: path.split("/").first, kind: "kotlin") }
           .sort_by(&:path)
    end

    def summary
      docs = documentation_candidates
      kotlin = kotlin_candidates
      {
        "documentation_total" => docs.length,
        "kotlin_total" => kotlin.length,
        "documentation_by_bucket" => count_by_bucket(docs),
        "kotlin_by_bucket" => count_by_bucket(kotlin),
        "exclusions" => {
          "readme_files" => "README*",
          "operating_docs" => EXCLUDED_DOC_FILES,
          "runtime_dirs" => [".omx", ".claude"],
          "manual_pairs" => EXCLUDED_MANUAL_PREFIXES,
        },
      }
    end

    private

    def files
      @files ||= Dir.glob("**/*", File::FNM_DOTMATCH, base: @root.to_s)
                    .reject { |path| path == "." || path == ".." }
                    .reject { |path| excluded_path?(path) }
                    .select { |path| File.file?(@root.join(path)) }
    end

    def excluded_path?(path)
      parts = path.split("/")
      return true if parts.any? { |part| EXCLUDED_DIRS.include?(part) }
      return true if parts.each_cons(2).any? { |left, right| left == "build" || right == "build" }

      false
    end

    def documentation_candidate?(path)
      ext = File.extname(path)
      basename = File.basename(path)
      return false unless DOC_EXTENSIONS.include?(ext)
      return false if basename.start_with?("README")
      return false if EXCLUDED_DOC_FILES.include?(basename)
      return false if EXCLUDED_MANUAL_PREFIXES.any? { |prefix| path.start_with?(prefix) }

      true
    end

    def kotlin_candidate?(path)
      File.extname(path) == KOTLIN_EXTENSION
    end

    def bucket_for(path)
      parts = path.split("/")
      parts.length == 1 ? parts.first : "#{parts[0]}/#{parts[1]}"
    end

    def count_by_bucket(candidates)
      candidates.group_by(&:bucket)
                .transform_values(&:length)
                .sort_by { |bucket, count| [-count, bucket] }
                .to_h
    end
  end

  class Validator
    def initialize(root)
      @root = Pathname.new(root).expand_path
      @inventory = Inventory.new(@root)
    end

    def documentation_violations
      @inventory.documentation_candidates.map do |candidate|
        text = normalized_document_text(@root.join(candidate.path).read)
        next if korean_enough?(text)
        next if text.scan(/[[:alpha:]]/).length < DOCUMENT_TEXT_MINIMUM

        Violation.new(
          path: candidate.path,
          kind: "documentation",
          line: 1,
          reason: "document has substantial prose without Korean text",
        )
      end.compact
    end

    def kdoc_violations
      @inventory.kotlin_candidates.flat_map do |candidate|
        source = @root.join(candidate.path).read
        violations = kdoc_blocks(source).map do |block|
          text = normalized_kdoc_text(block.fetch(:text))
          next if korean_enough?(text)
          next if text.scan(/[[:alpha:]]/).length < KDOC_TEXT_MINIMUM

          Violation.new(
            path: candidate.path,
            kind: "kdoc",
            line: block.fetch(:line),
            reason: "KDoc block has substantial prose without Korean text",
          )
        end.compact
        violations + undocumented_contract_violations(candidate.path, source)
      end
    end

    private

    def normalized_document_text(text)
      text = text.gsub(/```.*?```/m, " ")
      text = text.gsub(/`[^`]+`/, " ")
      text = text.gsub(%r{https?://\S+}, " ")
      text.gsub(/[[:space:]]+/, " ").strip
    end

    def normalized_kdoc_text(text)
      text = text.gsub(/^\s*\* ?/, "")
      text = text.gsub(/@[a-zA-Z]+\s+\w+/, "")
      text = text.gsub(/`[^`]+`/, " ")
      text.gsub(/[[:space:]]+/, " ").strip
    end

    def korean_enough?(text)
      text.match?(/[가-힣]/)
    end

    def kdoc_blocks(source)
      blocks = []
      source.to_enum(:scan, %r{/\*\*.*?\*/}m).each do
        match = Regexp.last_match
        line = source[0...match.begin(0)].count("\n") + 1
        blocks << { text: match[0], line: line }
      end
      blocks
    end

    def undocumented_contract_violations(path, source)
      lines = source.lines
      lines.each_with_index.map do |line, index|
        next unless line.match?(/^\s*(internal\s+)?data\s+class\s+\w+\s*\(/) ||
                    line.match?(/^\s*internal\s+class\s+\w+\s*\(/)
        next if preceding_kdoc?(lines, index)

        Violation.new(
          path: path,
          kind: "kdoc",
          line: index + 1,
          reason: "internal class or data class constructor lacks Korean KDoc/property documentation",
        )
      end.compact
    end

    def preceding_kdoc?(lines, index)
      cursor = index - 1
      cursor -= 1 while cursor >= 0 && lines[cursor].strip.empty?
      cursor >= 0 && lines[cursor].include?("*/")
    end
  end

  class Cli
    def self.run(argv)
      new(argv).run
    end

    def initialize(argv)
      @argv = argv
      @options = {
        root: Dir.pwd,
        format: "json",
        kind: "all",
        fail_on_violations: false,
      }
    end

    def run
      command = @argv.shift || "summary"
      parser.parse!(@argv)

      case command
      when "list"
        output_list
      when "summary"
        output_summary
      when "validate"
        output_validation
      else
        warn "Unknown command: #{command}"
        return 2
      end
    end

    private

    def parser
      OptionParser.new do |opts|
        opts.banner = "Usage: ruby scripts/localization/korean_localization_inventory.rb [summary|list|validate] [options]"
        opts.on("--root PATH", "Repository root, default current directory") { |value| @options[:root] = value }
        opts.on("--format FORMAT", "json, markdown, or text") { |value| @options[:format] = value }
        opts.on("--kind KIND", "all, documentation, or kdoc") { |value| @options[:kind] = value }
        opts.on("--fail-on-violations", "Exit non-zero when validation finds violations") do
          @options[:fail_on_violations] = true
        end
      end
    end

    def inventory
      @inventory ||= Inventory.new(@options.fetch(:root))
    end

    def validator
      @validator ||= Validator.new(@options.fetch(:root))
    end

    def candidates
      case @options.fetch(:kind)
      when "documentation"
        inventory.documentation_candidates
      when "kdoc", "kotlin"
        inventory.kotlin_candidates
      else
        inventory.documentation_candidates + inventory.kotlin_candidates
      end
    end

    def output_list
      rows = candidates.map { |candidate| candidate.to_h }
      puts JSON.pretty_generate(rows)
      0
    end

    def output_summary
      summary = inventory.summary
      if @options.fetch(:format) == "markdown"
        puts markdown_summary(summary)
      else
        puts JSON.pretty_generate(summary)
      end
      0
    end

    def output_validation
      violations = []
      violations.concat(validator.documentation_violations) if %w[all documentation].include?(@options.fetch(:kind))
      violations.concat(validator.kdoc_violations) if %w[all kdoc kotlin].include?(@options.fetch(:kind))

      payload = {
        "violation_count" => violations.length,
        "violations" => violations.map(&:to_h),
      }
      puts JSON.pretty_generate(payload)
      violations.empty? || !@options.fetch(:fail_on_violations) ? 0 : 1
    end

    def markdown_summary(summary)
      lines = [
        "# Korean Localization Inventory",
        "",
        "## Scope Counts",
        "",
        "| Surface | Count |",
        "|---|---:|",
        "| Single-language documentation | #{summary.fetch('documentation_total')} |",
        "| Kotlin source files for KDoc/property review | #{summary.fetch('kotlin_total')} |",
        "",
        "## Documentation Buckets",
        "",
        "| Bucket | Count |",
        "|---|---:|",
      ]
      summary.fetch("documentation_by_bucket").each { |bucket, count| lines << "| `#{bucket}` | #{count} |" }
      lines += [
        "",
        "## Kotlin Buckets",
        "",
        "| Bucket | Count |",
        "|---|---:|",
      ]
      summary.fetch("kotlin_by_bucket").each { |bucket, count| lines << "| `#{bucket}` | #{count} |" }
      lines += [
        "",
        "## Exclusions",
        "",
        "- `README*` files.",
        "- `AGENTS.md` and `CLAUDE.md`.",
        "- `.omx` and `.claude` runtime/vendor surfaces.",
        "- `docs/manual/en/**` and `docs/manual/ko/**` as primary rewrite targets.",
      ]
      lines.join("\n")
    end
  end
end

exit KoreanLocalization::Cli.run(ARGV) if $PROGRAM_NAME == __FILE__

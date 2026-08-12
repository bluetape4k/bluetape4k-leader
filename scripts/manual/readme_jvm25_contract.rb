# frozen_string_literal: true

require "pathname"
require "yaml"

module ReadmeJvm25Contract
  README_FILES = %w[README.md README.ko.md].freeze
  MANUAL_PAGES = %w[
    docs/manual/en/architecture/repository-map.md
    docs/manual/ko/architecture/repository-map.md
  ].freeze
  OVERVIEW_SVG = "docs/images/readme-diagrams/root-readme-overview-01.svg"
  OVERVIEW_PNG = "docs/images/readme-diagrams/root-readme-overview-01.png"
  OVERVIEW_REFERENCE = "docs/images/readme-diagrams/root-readme-overview-01.png"

  module_function

  def errors(root: Dir.pwd)
    Contract.new(root).errors
  end

  class Contract
    def initialize(root)
      @root = Pathname.new(root).expand_path
    end

    def errors
      failures = []
      check_svg(failures)
      check_png(failures)
      check_readmes(failures)
      check_release_boundary(failures)
      failures
    end

    private

    def check_svg(failures)
      path = resolve(OVERVIEW_SVG)
      unless path.file?
        failures << "overview SVG is missing: #{OVERVIEW_SVG}"
        return nil
      end

      content = path.read
      failures << "overview SVG must label the virtual-thread chip as JVM 25+" unless content.scan(">JVM 25+<").length == 1
      failures << "overview SVG still contains stale JVM 21 text" if content.include?("JVM 21")
      content
    end

    def check_png(failures)
      path = resolve(OVERVIEW_PNG)
      unless path.file?
        failures << "overview PNG is missing: #{OVERVIEW_PNG}"
        return
      end

      header = path.binread(24)
      signature, chunk_length, chunk_type, width, height = header.unpack("a8Na4NN")
      unless signature == "\x89PNG\r\n\x1A\n".b && chunk_length == 13 && chunk_type == "IHDR"
        failures << "overview PNG has an invalid PNG/IHDR header"
        return
      end
      failures << "overview PNG must be a CairoSVG 2x render at 2800x1800, found #{width}x#{height}" unless [width, height] == [2800, 1800]
    rescue EOFError
      failures << "overview PNG is truncated: #{OVERVIEW_PNG}"
    end

    def check_readmes(failures)
      README_FILES.each do |relative|
        path = resolve(relative)
        unless path.file?
          failures << "README is missing: #{relative}"
          next
        end

        content = path.read
        failures << "#{relative} must embed #{OVERVIEW_REFERENCE}" unless content.include?(OVERVIEW_REFERENCE)
        failures << "#{relative} must advertise JVM 25 in the badge" unless content.include?("img.shields.io/badge/JVM-25-")
        failures << "#{relative} must require JVM 25+" unless content.include?("JVM 25+")
      end
    end

    def check_release_boundary(failures)
      manifest_path = resolve("docs/manual/manifest.yaml")
      unless manifest_path.file?
        failures << "manual manifest is missing: docs/manual/manifest.yaml"
        return
      end

      manifest = YAML.safe_load(manifest_path.read)
      unless manifest.is_a?(Hash)
        failures << "manual manifest must be a mapping"
        return
      end
      release_ref = manifest["releaseRef"]
      release_commit = manifest["releaseCommit"]
      unless release_ref.is_a?(String) && !release_ref.empty?
        failures << "manual manifest releaseRef must be a non-empty string"
        return
      end
      unless release_commit.is_a?(String) && release_commit.match?(/\A[0-9a-f]{40}\z/)
        failures << "manual manifest releaseCommit must be a full SHA"
        return
      end

      MANUAL_PAGES.each do |relative|
        path = resolve(relative)
        unless path.file?
          failures << "release-boundary manual page is missing: #{relative}"
          next
        end

        content = path.read
        failures << "#{relative} must identify release #{release_ref}" unless content.include?("`#{release_ref}`")
        failures << "#{relative} must explain that later Snapshot changes are excluded" unless content.match?(/not later Snapshot changes|이후 SNAPSHOT이 아니라/)
        failures << "#{relative} must pin the overview PNG to releaseCommit" unless content.include?("#{release_commit}/#{OVERVIEW_PNG}")
        failures << "#{relative} must pin the overview SVG to releaseCommit" unless content.include?("#{release_commit}/#{OVERVIEW_SVG}")
      end
    rescue Psych::SyntaxError => error
      failures << "manual manifest YAML is invalid: #{error.message.lines.first.to_s.strip}"
    end

    def resolve(relative)
      @root.join(relative).cleanpath
    end
  end
end

if $PROGRAM_NAME == __FILE__
  failures = ReadmeJvm25Contract.errors
  abort(failures.join("\n")) unless failures.empty?

  puts "README JVM 25 contract passed: bilingual badges/requirements, overview SVG/PNG pair, and pinned manual release boundary are aligned."
end

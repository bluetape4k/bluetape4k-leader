require "pathname"
require "yaml"

module ManualDocs
  SUPPORTED_SCHEMA_VERSION = 2
  REQUIRED_SECTIONS = %w[
    problem when-to-use coordinates concepts quick-start api-by-task
    patterns integrations configuration failures operations testing
    workshops limitations sources
  ].freeze
  VALID_KINDS = %w[library example benchmark].freeze
  MANUAL_ASSET_EXTENSIONS = %w[.png .svg].freeze

  class Validator
    REQUIRED_FIELDS = %w[id gradlePath sourceDir kind group artifact en ko sourcePaths testPaths workshops].freeze
    REQUIRED_CHAPTER_FIELDS = %w[id en ko].freeze
    PATH_FIELDS = %w[sourcePaths testPaths workshops].freeze
    LOCALES = { "en" => "English", "ko" => "Korean" }.freeze

    attr_reader :errors

    def initialize(inventory:, manifest_path:, repository_root:, expected_release:)
      @inventory = inventory
      @manifest_path = File.expand_path(manifest_path)
      @repository_root = File.expand_path(repository_root)
      @expected_release = expected_release
      @errors = validate.sort
    end

    private

    def validate
      return ["manual manifest not found: #{relative(@manifest_path)}"] unless File.file?(@manifest_path)

      manifest = YAML.safe_load(File.read(@manifest_path))
      return ["manual manifest must be a mapping"] unless manifest.is_a?(Hash)

      errors = validate_header(manifest)
      modules = manifest["modules"]
      return (errors << "manual manifest modules must be an array") unless modules.is_a?(Array)

      entries = modules.select { |entry| entry.is_a?(Hash) }
      errors.concat(validate_duplicates(entries))
      errors.concat(validate_inventory(entries))
      entries.each { |entry| errors.concat(validate_entry(entry)) }
      errors.concat(validate_overview(manifest["overview"]))
      errors.concat(validate_orphan_assets(entries, manifest["overview"]))
      errors
    rescue Psych::SyntaxError => error
      ["manual manifest YAML is invalid: #{error.problem}"]
    rescue StandardError => error
      ["manual manifest could not be read: #{error.message}"]
    end

    def validate_header(manifest)
      errors = []
      errors << "manual manifest schemaVersion must be #{SUPPORTED_SCHEMA_VERSION}" unless manifest["schemaVersion"] == SUPPORTED_SCHEMA_VERSION
      expected_ref = @expected_release.fetch("ref")
      expected_commit = @expected_release.fetch("commit")
      errors << "manual manifest releaseRef must be #{expected_ref}" unless manifest["releaseRef"] == expected_ref
      errors << "manual manifest releaseCommit must be #{expected_commit}" unless manifest["releaseCommit"] == expected_commit
      errors
    end

    def validate_duplicates(entries)
      duplicate_values(entries, "id").map { |id| "#{id}: duplicate id" } +
        duplicate_values(entries, "gradlePath").map { |path| "manifest: duplicate gradlePath #{path}" }
    end

    def validate_inventory(entries)
      return ["module inventory must be an array"] unless @inventory.is_a?(Array)

      errors = duplicate_values(@inventory, "gradlePath").map { |path| "inventory: duplicate gradlePath #{path}" }
      by_inventory = @inventory.each_with_object({}) { |row, result| result[row["gradlePath"]] ||= row if row.is_a?(Hash) }
      by_manifest = entries.each_with_object({}) { |entry, result| result[entry["gradlePath"]] ||= entry }
      (by_inventory.keys.compact - by_manifest.keys.compact).sort.each do |path|
        row = by_inventory.fetch(path)
        errors << "#{row['projectName'] || path.delete_prefix(':')}: missing from manifest"
      end
      (by_manifest.keys.compact - by_inventory.keys.compact).sort.each do |path|
        errors << "#{label(by_manifest.fetch(path))}: gradlePath #{path} is not present in inventory"
      end
      entries.each do |entry|
        row = by_inventory[entry["gradlePath"]]
        next unless row
        errors << "#{label(entry)}: sourceDir does not match inventory" unless entry["sourceDir"] == row["sourceDir"]
      end
      errors
    end

    def validate_entry(entry)
      errors = []
      REQUIRED_FIELDS.each { |field| errors << "#{label(entry)}: missing manifest field #{field}" unless entry.key?(field) }
      errors << "#{label(entry)}: invalid kind #{entry['kind'].inspect}" unless VALID_KINDS.include?(entry["kind"])
      errors << "#{label(entry)}: library artifact must be present" if entry["kind"] == "library" && blank?(entry["artifact"])
      if %w[example benchmark].include?(entry["kind"]) && !entry["artifact"].nil?
        errors << "#{label(entry)}: #{entry['kind']} artifact must be null"
      end
      LOCALES.each { |field, language| errors.concat(validate_document(entry, field, language)) }
      PATH_FIELDS.each { |field| errors.concat(validate_paths(entry, field)) }
      errors.concat(validate_chapters(entry))
      errors.concat(validate_assets(entry))
      errors
    end

    def validate_document(entry, field, language)
      path = entry[field]
      return ["#{label(entry)}: unsafe #{language} document path"] unless safe_relative?(path)

      absolute = File.expand_path(path, File.dirname(@manifest_path))
      return ["#{label(entry)}: unsafe #{language} document path"] unless within?(absolute, File.dirname(@manifest_path))
      return ["#{label(entry)}: missing #{language} document"] unless path_entry_exists?(absolute)
      return ["#{label(entry)}: unsafe #{language} document path"] unless safe_existing_file?(absolute, File.dirname(@manifest_path))

      content = File.read(absolute)
      errors = []
      frontmatter = content.match(/\A---\s*\n(.*?)\n---\s*(?:\n|\z)/m)
      metadata = frontmatter ? YAML.safe_load(frontmatter[1]) : {}
      errors << "#{label(entry)}: #{language} document manualId must be #{entry['id']}" unless metadata.is_a?(Hash) && metadata["manualId"] == entry["id"]
      sections = content.scan(/^\#{1,6}\s+.*\{#([a-z0-9-]+)\}\s*$/).flatten
      (REQUIRED_SECTIONS - sections).each do |section|
        errors << "#{label(entry)}: #{language} document missing required section #{section}"
      end
      errors.concat(validate_markdown_references(absolute, content, label(entry)))
      errors
    end

    def validate_chapters(entry)
      chapters = entry.fetch("chapters", [])
      return ["#{label(entry)}: chapters must be an array"] unless chapters.is_a?(Array)

      errors = duplicate_values(chapters, "id").map { |id| "#{label(entry)}: duplicate chapter id #{id}" }
      chapters.each_with_index do |chapter, index|
        unless chapter.is_a?(Hash)
          errors << "#{label(entry)}: chapter[#{index}] must be a mapping"
          next
        end
        chapter_label = "#{label(entry)}/#{chapter['id'] || 'chapter'}"
        REQUIRED_CHAPTER_FIELDS.each do |field|
          errors << "#{chapter_label}: missing manifest field #{field}" unless chapter.key?(field)
        end
        LOCALES.each do |field, language|
          errors.concat(validate_chapter_document(entry, chapter, field, language))
        end
      end
      errors
    end

    def validate_chapter_document(entry, chapter, field, language)
      chapter_label = "#{label(entry)}/#{chapter['id'] || 'chapter'}"
      path = chapter[field]
      return ["#{chapter_label}: unsafe #{language} document path"] unless safe_relative?(path)

      absolute = File.expand_path(path, File.dirname(@manifest_path))
      return ["#{chapter_label}: unsafe #{language} document path"] unless within?(absolute, File.dirname(@manifest_path))
      return ["#{chapter_label}: missing #{language} document"] unless path_entry_exists?(absolute)
      return ["#{chapter_label}: unsafe #{language} document path"] unless safe_existing_file?(absolute, File.dirname(@manifest_path))

      content = File.read(absolute)
      match = content.match(/\A---\s*\n(.*?)\n---\s*(?:\n|\z)/m)
      metadata = match ? YAML.safe_load(match[1]) : {}
      errors = []
      errors << "#{chapter_label}: #{language} manualId must be #{entry['id']}" unless metadata.is_a?(Hash) && metadata["manualId"] == entry["id"]
      errors << "#{chapter_label}: #{language} chapterId must be #{chapter['id']}" unless metadata.is_a?(Hash) && metadata["chapterId"] == chapter["id"]
      errors.concat(validate_markdown_references(absolute, content, chapter_label))
      errors
    end

    def validate_markdown_references(document_path, content, document_label)
      without_fences = content.gsub(/^```[^\n]*\n.*?^```\s*$/m, "")
      without_fences.scan(/!?\[[^\]]*\]\(([^)]+)\)/).flatten.each_with_object([]) do |raw, errors|
        target = markdown_target(raw)
        next unless target
        absolute = File.expand_path(target, File.dirname(document_path))
        unless within?(absolute, @repository_root)
          errors << "#{document_label}: unsafe Markdown reference #{raw}"
          next
        end
        unless File.exist?(absolute)
          errors << "#{document_label}: missing Markdown reference #{raw}"
          next
        end
        unless within?(File.realpath(absolute), File.realpath(@repository_root))
          errors << "#{document_label}: unsafe Markdown reference #{raw}"
        end
      end
    end

    def markdown_target(raw)
      value = raw.to_s.strip
      return nil if value.empty? || value.start_with?("#", "/") || value.match?(/\A[a-z][a-z0-9+.-]*:/i)
      value = value[1...-1] if value.start_with?("<") && value.end_with?(">")
      value = value.split(/\s+["']/).first
      value = value.split(/[?#]/).first
      value unless value.nil? || value.empty?
    end

    def validate_paths(entry, field)
      paths = entry[field]
      return ["#{label(entry)}: #{field} must be an array"] unless paths.is_a?(Array)

      paths.each_with_object([]) do |path, errors|
        unless safe_relative?(path)
          errors << "#{label(entry)}: unsafe #{field} path #{path}"
          next
        end
        absolute = File.expand_path(path, @repository_root)
        if !within?(absolute, @repository_root) || (File.exist?(absolute) && !within?(File.realpath(absolute), File.realpath(@repository_root)))
          errors << "#{label(entry)}: unsafe #{field} path #{path}"
        elsif !File.exist?(absolute)
          errors << "#{label(entry)}: missing #{field} path #{path}"
        end
      end
    end

    def validate_assets(entry)
      assets = entry.fetch("assets", [])
      return ["#{label(entry)}: assets must be an array"] unless assets.is_a?(Array)

      root = File.dirname(@manifest_path)
      errors = []
      assets.each do |asset|
        unless safe_relative?(asset) && asset.start_with?("assets/") && MANUAL_ASSET_EXTENSIONS.include?(File.extname(asset))
          errors << "#{label(entry)}: unsafe asset path #{asset}"
          next
        end
        absolute = File.expand_path(asset, root)
        if !path_entry_exists?(absolute)
          errors << "#{label(entry)}: missing asset #{asset}"
        elsif !safe_existing_file?(absolute, root)
          errors << "#{label(entry)}: unsafe asset path #{asset}"
        end
      end
      assets.grep(String).map { |asset| asset.delete_suffix(File.extname(asset)) }.uniq.each do |base|
        MANUAL_ASSET_EXTENSIONS.each do |extension|
          pair = "#{base}#{extension}"
          errors << "#{label(entry)}: missing paired asset #{pair}" unless assets.include?(pair) && File.file?(File.expand_path(pair, root))
        end
      end
      errors
    end

    def validate_overview(overview)
      return [] if overview.nil?
      return ["manual overview must be a mapping"] unless overview.is_a?(Hash)

      errors = []
      documents = overview["documents"]
      unless documents.is_a?(Hash)
        errors << "manual overview documents must be a mapping"
      else
        LOCALES.each do |locale, language|
          paths = documents[locale]
          unless paths.is_a?(Array)
            errors << "manual overview #{language} documents must be an array"
            next
          end
          paths.each do |path|
            unless safe_relative?(path)
              errors << "manual overview: unsafe #{language} document path #{path}"
              next
            end
            absolute = File.expand_path(path, File.dirname(@manifest_path))
            if !path_entry_exists?(absolute)
              errors << "manual overview: missing #{language} document #{path}"
            elsif !safe_existing_file?(absolute, File.dirname(@manifest_path))
              errors << "manual overview: unsafe #{language} document path #{path}"
            else
              errors.concat(validate_markdown_references(absolute, File.read(absolute), "manual overview/#{locale}"))
            end
          end
        end
        if documents["en"].is_a?(Array) && documents["ko"].is_a?(Array)
          english = documents["en"].map { |path| path.is_a?(String) ? path.delete_prefix("en/") : path }
          korean = documents["ko"].map { |path| path.is_a?(String) ? path.delete_prefix("ko/") : path }
          errors << "manual overview: English/Korean document inventory differs" unless english == korean
        end
      end

      assets = overview["assets"]
      unless assets.is_a?(Array)
        errors << "manual overview assets must be an array"
        return errors
      end
      root = File.dirname(@manifest_path)
      assets.each do |asset|
        unless safe_relative?(asset) && asset.start_with?("assets/") && MANUAL_ASSET_EXTENSIONS.include?(File.extname(asset))
          errors << "manual overview: unsafe asset path #{asset}"
          next
        end
        absolute = File.expand_path(asset, root)
        if !path_entry_exists?(absolute)
          errors << "manual overview: missing asset #{asset}"
        elsif !safe_existing_file?(absolute, root)
          errors << "manual overview: unsafe asset path #{asset}"
        end
      end
      assets.grep(String).map { |asset| asset.delete_suffix(File.extname(asset)) }.uniq.each do |base|
        MANUAL_ASSET_EXTENSIONS.each do |extension|
          pair = "#{base}#{extension}"
          errors << "manual overview: missing paired asset #{pair}" unless assets.include?(pair) && File.file?(File.expand_path(pair, root))
        end
      end
      errors
    end

    def validate_orphan_assets(entries, overview = nil)
      root = File.dirname(@manifest_path)
      actual = MANUAL_ASSET_EXTENSIONS.flat_map { |ext| Dir.glob(File.join(root, "assets/**/*#{ext}")) }
        .map { |path| Pathname.new(path).relative_path_from(Pathname.new(root)).to_s }
      registered = entries.flat_map { |entry| entry["assets"].is_a?(Array) ? entry["assets"] : [] }
      registered.concat(overview["assets"]) if overview.is_a?(Hash) && overview["assets"].is_a?(Array)
      (actual - registered).sort.map { |asset| "manual assets: orphan asset #{asset}" }
    end

    def duplicate_values(entries, field)
      entries.select { |entry| entry.is_a?(Hash) && !blank?(entry[field]) }
        .group_by { |entry| entry[field] }.select { |_value, matches| matches.length > 1 }.keys.sort
    end

    def safe_relative?(value)
      return false unless value.is_a?(String) && !value.empty?
      path = Pathname.new(value)
      !path.absolute? && path.each_filename.none? { |part| part == ".." }
    end

    def path_entry_exists?(path)
      File.lstat(path)
      true
    rescue Errno::ENOENT, Errno::ENOTDIR
      false
    rescue SystemCallError
      true
    end

    def safe_existing_file?(path, boundary)
      metadata = File.lstat(path)
      return false if metadata.symlink? || !metadata.file?
      within?(File.realpath(path), File.realpath(boundary))
    rescue SystemCallError
      false
    end

    def within?(path, root)
      path = File.expand_path(path)
      root = File.expand_path(root)
      path == root || path.start_with?(root + File::SEPARATOR)
    end

    def relative(path)
      Pathname.new(path).relative_path_from(Pathname.new(@repository_root)).to_s
    end

    def label(entry)
      entry["id"] || entry["gradlePath"] || "manifest module"
    end

    def blank?(value)
      value.nil? || value == ""
    end
  end
end

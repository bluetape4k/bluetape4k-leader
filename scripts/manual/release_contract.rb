require "open3"
require "pathname"
require "set"

module ManualDocs
  class ReleaseContract
    ValidationResult = Struct.new(:errors, :checked_count, keyword_init: true)
    TAG_PATTERN = /\Av?\d+\.\d+\.\d+\z/
    SHA_PATTERN = /\A[0-9a-f]{40}\z/i
    LINK_PATTERN = /!?\[[^\]]*\]\(\s*<?([^)>\s]+)>?(?:\s+["'][^)]*["'])?\s*\)/
    REFERENCE_DEFINITION_PATTERN = /^[ \t]{0,3}\[([^\]]+)\]:[ \t]*(?:\n[ \t]+)?(?:<([^>\r\n]+)>|([^ \t\r\n]+))/
    REFERENCE_USAGE_PATTERN = /!?\[([^\]]+)\](?:\[([^\]]*)\])?/

    def initialize(repository_root:, tag:, expected_sha:, git_runner: nil)
      @repository_root = File.expand_path(repository_root)
      @tag = tag
      @expected_sha = expected_sha
      @git_runner = git_runner || method(:run_git)
    end

    def errors
      validate.errors
    end

    def validate
      inputs = []
      inputs << "release tag must match v?MAJOR.MINOR.PATCH: #{@tag}" unless TAG_PATTERN.match?(@tag)
      inputs << "expected SHA must be a 40-character hexadecimal commit id: #{@expected_sha}" unless SHA_PATTERN.match?(@expected_sha)
      return result(inputs) unless inputs.empty?

      sha, success = @git_runner.call(["rev-parse", "--verify", "refs/tags/#{@tag}^{commit}"])
      return result(["release tag not found: refs/tags/#{@tag}"]) unless success
      sha = sha.strip
      return result(["release tag #{@tag} resolves to #{sha}, expected #{@expected_sha}"]) unless sha.casecmp?(@expected_sha)

      output, success = @git_runner.call(["ls-tree", "-r", "--name-only", sha])
      return result(["release inventory could not be read: #{sha}"]) unless success
      inventory = output.lines(chomp: true).to_set
      links = repository_links
      return result(["no repository-relative manual links found"]) if links.empty?

      errors = links.each_with_object([]) do |(file, line, target), result|
        candidate = Pathname.new(File.dirname(file)).join(target.split(/[?#]/, 2).first).cleanpath
        normalized = candidate.to_s
        if candidate.absolute? || normalized == ".." || normalized.start_with?("../")
          result << "#{file}:#{line}: unsafe release path: #{target}"
        elsif !inventory.include?(normalized)
          result << "#{file}:#{line}: release path not found: #{normalized}"
        end
      end
      result(errors.sort, links.length)
    end

    private

    def repository_links
      Dir.glob(File.join(@repository_root, "docs/manual/**/*.md")).sort.flat_map do |path|
        file = Pathname.new(path).relative_path_from(Pathname.new(@repository_root)).to_s
        content = File.read(path)
        inline = content.to_enum(:scan, LINK_PATTERN).map do
          match = Regexp.last_match
          [file, content[0...match.begin(0)].count("\n") + 1, match[1]]
        end
        definitions = content.to_enum(:scan, REFERENCE_DEFINITION_PATTERN).each_with_object({}) do |_captures, result|
          match = Regexp.last_match
          result[normalize_label(match[1])] ||= match[2] || match[3]
        end
        references = content.to_enum(:scan, REFERENCE_USAGE_PATTERN).each_with_object([]) do |_captures, result|
          match = Regexp.last_match
          next if ["(", ":"].include?(content[match.end(0)])
          name = match[2].nil? || match[2].empty? ? match[1] : match[2]
          target = definitions[normalize_label(name)]
          result << [file, content[0...match.begin(0)].count("\n") + 1, target] if target
        end
        (inline + references)
          .select { |_file, _line, target| repository_target?(file, target) }
          .sort_by { |entry| entry[1] }
      end
    end

    def repository_target?(relative_file, target)
      value = target.to_s.strip
      return false if value.empty? || value.start_with?("#", "/")
      return false if value.match?(/\A[a-z][a-z0-9+.-]*:/i)

      candidate = Pathname.new(File.dirname(relative_file)).join(value.split(/[?#]/, 2).first).cleanpath.to_s
      candidate != "docs/manual" && !candidate.start_with?("docs/manual/")
    end

    def normalize_label(label)
      label.gsub(/\s+/, " ").strip.downcase
    end

    def result(errors, count = 0)
      ValidationResult.new(errors: errors, checked_count: count)
    end

    def run_git(arguments)
      stdout, _stderr, status = Open3.capture3("git", "-C", @repository_root, *arguments)
      [stdout, status.success?]
    end
  end
end

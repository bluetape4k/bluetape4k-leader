#!/usr/bin/env ruby

require "fileutils"
require "json"
require "open3"
require "pathname"
require "set"

module ManualDocs
  class ReleaseInventoryError < StandardError; end

  class ReleaseInventory
    def initialize(repository_root:, tag:, expected_sha:, inventory_path:, output_path:, expected_count:, git_runner: nil)
      @repository_root = File.expand_path(repository_root)
      @tag = tag
      @expected_sha = expected_sha
      @inventory_path = File.expand_path(inventory_path)
      @output_path = File.expand_path(output_path)
      @expected_count = Integer(expected_count)
      @git_runner = git_runner || method(:run_git)
    end

    def write
      sha, success = @git_runner.call(["rev-parse", "--verify", "refs/tags/#{@tag}^{commit}"])
      raise ReleaseInventoryError, "release tag not found: refs/tags/#{@tag}" unless success
      sha = sha.strip
      raise ReleaseInventoryError, "release tag #{@tag} resolves to #{sha}, expected #{@expected_sha}" unless sha.casecmp?(@expected_sha)

      output, success = @git_runner.call(["ls-tree", "-r", "--name-only", sha])
      raise ReleaseInventoryError, "release inventory could not be read: #{sha}" unless success
      release_paths = output.lines(chomp: true).to_set
      current = JSON.parse(File.read(@inventory_path))
      raise ReleaseInventoryError, "module inventory must be an array" unless current.is_a?(Array)

      rows = current.select do |row|
        source = row.fetch("sourceDir")
        raise ReleaseInventoryError, "unsafe sourceDir in inventory: #{source}" unless safe_relative?(source)
        release_paths.include?(File.join(source, "build.gradle.kts"))
      end.sort_by { |row| row.fetch("gradlePath") }
      duplicate = rows.group_by { |row| row.fetch("gradlePath") }.find { |_path, matches| matches.length > 1 }
      raise ReleaseInventoryError, "duplicate Gradle path in release inventory: #{duplicate.first}" if duplicate
      raise ReleaseInventoryError, "release inventory is empty" if rows.empty?
      unless rows.length == @expected_count
        raise ReleaseInventoryError, "release inventory count #{rows.length}, expected #{@expected_count}"
      end

      FileUtils.mkdir_p(File.dirname(@output_path))
      File.binwrite(@output_path, JSON.pretty_generate(rows.map { |row| sort_keys(row) }) + "\n")
      rows
    rescue JSON::ParserError => error
      raise ReleaseInventoryError, "module inventory JSON is invalid: #{error.message}"
    end

    private

    def safe_relative?(value)
      value.is_a?(String) && !value.empty? && !Pathname.new(value).absolute? && Pathname.new(value).each_filename.none? { |part| part == ".." }
    end

    def sort_keys(hash)
      hash.keys.sort.each_with_object({}) { |key, result| result[key] = hash[key] }
    end

    def run_git(arguments)
      stdout, _stderr, status = Open3.capture3("git", "-C", @repository_root, *arguments)
      [stdout, status.success?]
    end
  end
end

if $PROGRAM_NAME == __FILE__
  abort("usage: ruby scripts/manual/release_inventory.rb TAG EXPECTED_SHA INPUT OUTPUT EXPECTED_COUNT") unless ARGV.length == 5
  tag, sha, input, output, count = ARGV
  rows = ManualDocs::ReleaseInventory.new(
    repository_root: Dir.pwd, tag: tag, expected_sha: sha,
    inventory_path: input, output_path: output, expected_count: count,
  ).write
  puts "Release inventory written: #{rows.length} projects."
end

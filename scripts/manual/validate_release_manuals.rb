#!/usr/bin/env ruby

require_relative "release_contract"

tag, expected_sha = ARGV
abort("usage: ruby scripts/manual/validate_release_manuals.rb TAG EXPECTED_SHA") unless ARGV.length == 2
result = ManualDocs::ReleaseContract.new(
  repository_root: File.expand_path("../..", __dir__), tag: tag, expected_sha: expected_sha,
).validate
abort(result.errors.join("\n")) unless result.errors.empty?
puts "Release manuals are compatible with #{tag} (#{expected_sha}): #{result.checked_count} checked, 0 missing."

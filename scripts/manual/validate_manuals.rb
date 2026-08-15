#!/usr/bin/env ruby

require "json"
require "yaml"
require_relative "manual_contract"

inventory_path = ARGV.fetch(0, "build/manual/module-inventory.json")
manifest_path = ARGV.fetch(1, "docs/manual/manifest.yaml")
inventory = JSON.parse(File.read(inventory_path))
manifest = YAML.safe_load(File.read(manifest_path))
abort("manual manifest must provide releaseRef and releaseCommit") unless manifest.is_a?(Hash)
expected_release = {
  "ref" => manifest.fetch("releaseRef"),
  "commit" => manifest.fetch("releaseCommit"),
}
errors = ManualDocs::Validator.new(
  inventory: inventory,
  manifest_path: manifest_path,
  repository_root: Dir.pwd,
  expected_release: expected_release,
).errors
abort(errors.join("\n")) unless errors.empty?
puts "Manuals are aligned."

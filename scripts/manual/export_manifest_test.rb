require "json"
require "minitest/autorun"
require "tmpdir"

require_relative "export_manifest"

class ExportManifestTest < Minitest::Test
  def test_emits_recursively_sorted_deterministic_json_with_final_lf
    Dir.mktmpdir("manifest-export") do |root|
      source = File.join(root, "manifest.yaml")
      output = File.join(root, "manifest.json")
      File.write(source, <<~YAML)
        modules: []
        releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
        repository: bluetape4k/bluetape4k-leader
        releaseRef: 0.4.0
        schemaVersion: 2
        title: 한국어 매뉴얼
      YAML
      exporter = ManualDocs::ManifestExporter.new(source_path: source, output_path: output)
      exporter.write
      parsed = JSON.parse(File.read(output))
      assert_equal %w[modules releaseCommit releaseRef repository schemaVersion title], parsed.keys
      assert_equal "bluetape4k/bluetape4k-leader", parsed.fetch("repository")
      assert_equal "한국어 매뉴얼", parsed.fetch("title")
      assert File.binread(output).end_with?("\n")
      assert exporter.current?
    end
  end

  def test_compacts_empty_containers_for_json_runtime_stability
    Dir.mktmpdir("manifest-export-empty-containers") do |root|
      source = File.join(root, "manifest.yaml")
      output = File.join(root, "manifest.json")
      File.write(source, <<~YAML)
        metadata: {}
        modules: []
      YAML

      exporter = ManualDocs::ManifestExporter.new(source_path: source, output_path: output)
      exporter.write
      rendered = File.read(output)

      assert_includes rendered, "\"metadata\": {}"
      assert_includes rendered, "\"modules\": []"
      refute_match(/\[\n(?:[ \t]*\n)*[ \t]*\]/, rendered)
      refute_match(/\{\n(?:[ \t]*\n)*[ \t]*\}/, rendered)
    end
  end
end
